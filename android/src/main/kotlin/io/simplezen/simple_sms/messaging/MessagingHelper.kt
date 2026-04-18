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
    val subscriptionManager  = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    // Bail if NONE of these three are granted — each is a distinct
    // path to the self-number across SDKs, so if the caller has any
    // one we try. Request flow belongs to simple_permissions_native.
    val anyGranted = PermissionGuards.isPermissionGranted(context, Manifest.permission.READ_SMS) ||
        PermissionGuards.isPermissionGranted(context, Manifest.permission.READ_PHONE_NUMBERS) ||
        PermissionGuards.isPermissionGranted(context, Manifest.permission.READ_PHONE_STATE)
    val phoneNums = if (!anyGranted) {
        return emptySet()
    } else {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOfNotNull(subscriptionManager.getPhoneNumber(SubscriptionManager.getDefaultSmsSubscriptionId()))
                .map { formatNumber(context, it) }
                .toSet()
        } else {
            @SuppressLint("HardwareIds")
            listOfNotNull(telephonyManager.line1Number)
                .map { formatNumber(context, it) }
                .toSet()
        }
    }
    return phoneNums
}

internal fun formatNumber(context : Context, number: String): String {
    // You may want to use Google's libphonenumber, or at minimum:
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    return PhoneNumberUtils.formatNumberToE164(number, telephonyManager.networkCountryIso.uppercase())
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
