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
import androidx.core.app.ActivityCompat


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
    return if(mime.contains("image"))
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!.absolutePath
    else if(mime.contains("video"))
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.absolutePath
    else if(mime.contains("audio"))
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!.absolutePath
    else
        context.getExternalFilesDir(Environment.DIRECTORY_RINGTONES)!!.absolutePath
}

internal fun getSelfNumbers(context: Context): Set<String> {
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val subscriptionManager  = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    // getLine1Number() may return null, so fallback to known numbers or preferences if needed
    val phoneNums = if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_NUMBERS
        ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) != PackageManager.PERMISSION_GRANTED
    ) {
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
