package io.simplezen.simple_sms.messaging

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.webkit.MimeTypeMap
import io.simplezen.simple_permissions_android.PermissionGuards


// Utility method to get mime type from file path
internal fun getMimeType(filePath: String): String? {
    val fileExtension = MimeTypeMap.getFileExtensionFromUrl(filePath)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension)
}

// Utility method to get mime type from file path
internal fun getExtFromMimeType(mime: String): String? {
    return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
}

// Create the private file dirs
internal fun getDirFromMimeType(context : Context, mime: String): String {
    val dir = when {
        mime.contains("image") -> context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        mime.contains("video") -> context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        mime.contains("audio") -> context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        else -> context.getExternalFilesDir(Environment.DIRECTORY_RINGTONES)
    }
    return dir?.absolutePath ?: context.cacheDir.absolutePath
}

internal fun getSelfNumbers(context: Context): Set<String> {
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    // Bail if NONE of these three are granted — each is a distinct
    // path to the self-number across SDKs, so if the caller has any
    // one we try. Request flow belongs to simple_permissions_native.
    val anyGranted = PermissionGuards.isPermissionGranted(context, Manifest.permission.READ_SMS) ||
        PermissionGuards.isPermissionGranted(context, Manifest.permission.READ_PHONE_NUMBERS) ||
        PermissionGuards.isPermissionGranted(context, Manifest.permission.READ_PHONE_STATE)
    if (!anyGranted) return emptySet()

    // S2 #18: enumerate ALL active subscriptions on Tiramisu+, not just
    // the default SMS subscription. Dual-SIM users where the non-default
    // SIM received an MMS-to-self otherwise won't have their own number
    // stripped during thread-id derivation, leading to mis-grouping.
    //
    // If the active-subs list is empty or unreachable (some OEMs gate
    // it behind READ_PRIVILEGED_PHONE_STATE which we don't hold; some
    // single-SIM ROMs return an empty list intermittently), fall through
    // to the legacy `line1Number` path so we still return SOMETHING for
    // the receiving SIM. Returning an empty set here would regress
    // single-SIM thread bucketing on those devices.
    @SuppressLint("HardwareIds")
    fun line1Fallback(): Set<String> =
        listOfNotNull(telephonyManager.line1Number)
            .mapNotNull { formatNumber(context, it) }
            .toSet()

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val activeSubs: List<android.telephony.SubscriptionInfo> = try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        val fromActiveSubs = activeSubs
            .mapNotNull { subInfo ->
                runCatching { subscriptionManager.getPhoneNumber(subInfo.subscriptionId) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
            .mapNotNull { formatNumber(context, it) }
            .toSet()
        fromActiveSubs.ifEmpty { line1Fallback() }
    } else {
        line1Fallback()
    }
}

/**
 * Normalize a phone-number-like string to E.164.
 *
 * Returns `null` for inputs that aren't parseable as a phone number
 * (5-digit shortcodes, alphanumeric senders like "VERIZON", emails).
 * The previous non-null contract masked the underlying nullability of
 * `PhoneNumberUtils.formatNumberToE164` and crashed the inbound
 * receiver on every shortcode MMS — banks, 2FA codes, business
 * senders all silently dropped.
 *
 * Callers should preserve the original string when this returns null
 * (`formatNumber(ctx, raw) ?: raw`); the un-normalised form is still a
 * valid display value, and downstream comparisons should use
 * [compareNumbers] which handles both forms.
 */
internal fun formatNumber(context: Context, number: String): String? {
    if (number.isBlank()) return null
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val region = telephonyManager.networkCountryIso?.takeIf { it.isNotBlank() }?.uppercase()
        ?: return null
    return runCatching {
        PhoneNumberUtils.formatNumberToE164(number, region)
    }.getOrNull()
}

internal fun compareNumbers(context : Context, numVals : Set<String>, searchNum : String): Boolean {
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    val matched: List<Boolean> = numVals.map { num ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PhoneNumberUtils.areSamePhoneNumber(num, searchNum, telephonyManager.networkCountryIso.uppercase())
        } else {
            PhoneNumberUtils.compare(context, num, searchNum)
        }
    }
    return matched.contains(true)
}
