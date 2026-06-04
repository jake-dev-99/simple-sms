package com.klinker.android.send_message

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.Log
import androidx.annotation.RequiresPermission
import io.simplezen.simple_sms.queries.Query
import io.simplezen.simple_sms.queries.QueryObj
import java.lang.reflect.Method
import java.util.Random
import java.util.regex.Pattern

/**
 * Common helpers for data connectivity / sending messages. First-party Kotlin
 * port of the vendored Klinker `Utils.java`; behaviour preserved.
 *
 * Provider reads route through `simple_query` (`Query`/`QueryObj` →
 * `ContentQuery`) per the layering contract: [getOrCreateThreadId] (the
 * `mms-sms/threadID` allocation read) and [doesThreadIdExist]. The thread-id
 * allocation side-effect is preserved (ContentQuery still performs the
 * underlying `ContentResolver.query`).
 */
object Utils {
    /** Characters to compare against when checking 160-char (GSM-7) compatibility. */
    const val GSM_CHARACTERS_REGEX =
        "^[A-Za-z0-9 \\r\\n@Ł\$ĽčéůěňÇŘřĹĺΔ_ΦΓΛΩΠΨΣΘΞĆćßÉ!\"#\$%&'()*+,\\-./:;<=>?ĄÄÖŃÜ§żäöńüŕ^{}\\\\\\[~\\]|€]*\$"

    private const val TAG = "Utils"

    const val DEFAULT_SUBSCRIPTION_ID = 1

    /**
     * The current user's phone number (`TelephonyManager.getLine1Number`).
     *
     * The vendored source wrapped this in an empty `checkSelfPermission`
     * if-block that did nothing with the result; it has been dropped (no
     * behaviour change, and avoids a direct permission call per the layering
     * contract). The caller is permission-aware — `getLine1Number` surfaces a
     * `SecurityException` if the read permission is missing, as before.
     */
    @JvmStatic
    fun getMyPhoneNumber(context: Context): String? {
        val telephonyMgr = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyMgr.line1Number
    }

    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @JvmStatic
    fun getMyPhoneNumberFromSubscription(context: Context, subscriptionId: Int): String? {
        if (DEFAULT_SUBSCRIPTION_ID == subscriptionId) {
            return getMyPhoneNumber(context)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val subscriptionManager = SubscriptionManager.from(context)
            val subscriptionInfo = subscriptionManager.getActiveSubscriptionInfo(subscriptionId)
            if (subscriptionInfo != null) {
                return subscriptionInfo.number
            }
        }
        return getMyPhoneNumber(context)
    }

    /** Whether mobile data is enabled (reflection; null on failure). */
    @JvmStatic
    fun isMobileDataEnabled(context: Context): Boolean? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            val c = Class.forName(cm.javaClass.name)
            val m = c.getDeclaredMethod("getMobileDataEnabled")
            m.isAccessible = true
            m.invoke(cm) as Boolean?
        } catch (e: Exception) {
            Log.e(TAG, "exception thrown", e)
            null
        }
    }

    @JvmStatic
    fun isDataEnabled(telephonyManager: TelephonyManager): Boolean {
        return try {
            val c: Class<*> = telephonyManager.javaClass
            val m = c.getMethod("getDataEnabled")
            m.invoke(telephonyManager) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "exception thrown", e)
            true
        }
    }

    @JvmStatic
    fun isDataEnabled(telephonyManager: TelephonyManager, subId: Int): Boolean {
        return try {
            val c: Class<*> = telephonyManager.javaClass
            val m = c.getMethod("getDataEnabled", Int::class.javaPrimitiveType)
            m.invoke(telephonyManager, subId) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "exception thrown", e)
            isDataEnabled(telephonyManager)
        }
    }

    /** Toggles mobile data (reflection; pre-Lollipop and Lollipop+ paths). */
    @JvmStatic
    fun setMobileDataEnabled(context: Context, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            try {
                val conman =
                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val conmanClass = Class.forName(conman.javaClass.name)
                val iConnectivityManagerField = conmanClass.getDeclaredField("mService")
                iConnectivityManagerField.isAccessible = true
                val iConnectivityManager = iConnectivityManagerField.get(conman)
                val iConnectivityManagerClass = Class.forName(iConnectivityManager!!.javaClass.name)
                val setMobileDataEnabledMethod = iConnectivityManagerClass.getDeclaredMethod(
                    "setMobileDataEnabled",
                    java.lang.Boolean.TYPE,
                )
                setMobileDataEnabledMethod.isAccessible = true
                setMobileDataEnabledMethod.invoke(iConnectivityManager, enabled)
            } catch (e: Exception) {
                Log.e(TAG, "exception thrown", e)
            }
        } else {
            // TODO from the vendored source: this path can't actually work on
            // Lollipop+ (MODIFY_PHONE_STATE is system-level), kept for fidelity.
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                var c = Class.forName(tm.javaClass.name)
                var m: Method = c.getDeclaredMethod("getITelephony")
                m.isAccessible = true
                val telephonyService = m.invoke(tm)
                c = Class.forName(telephonyService!!.javaClass.name)
                m = c.getDeclaredMethod("setDataEnabled", java.lang.Boolean.TYPE)
                m.isAccessible = true
                m.invoke(telephonyService, enabled)
            } catch (e: Exception) {
                Log.e(TAG, "error enabling data on lollipop", e)
            }
        }
    }

    /** Number of SMS pages for [text] under [settings] (strip-unicode aware). */
    @JvmStatic
    fun getNumPages(settings: Settings, text: String): Int {
        var t = text
        if (settings.stripUnicode) {
            t = StripAccents.stripAccents(t)
        }
        val data = SmsMessage.calculateLength(t, false)
        return data[0]
    }

    /** Gets or creates the thread id for a single recipient. */
    @JvmStatic
    fun getOrCreateThreadId(context: Context, recipient: String): Long {
        val recipients = HashSet<String>()
        recipients.add(recipient)
        return getOrCreateThreadId(context, recipients)
    }

    /** Gets or creates the thread id for a set of recipients. */
    @JvmStatic
    fun getOrCreateThreadId(context: Context, recipients: Set<String>): Long {
        val uriBuilder = Uri.parse("content://mms-sms/threadID").buildUpon()
        for (r in recipients) {
            var recipient = r
            if (isEmailAddress(recipient)) {
                recipient = extractAddrSpec(recipient)
            }
            uriBuilder.appendQueryParameter("recipient", recipient)
        }
        val uri = uriBuilder.build()
        // Read (which allocates the thread id) via simple_query per the layering
        // contract (Rule 1: reads route through simple-query). ContentQuery does
        // the underlying ContentResolver.query, so the allocation side-effect is
        // preserved, and it manages the cursor.
        val rows = Query(context).query(QueryObj(contentUri = uri.toString(), projection = listOf("_id")))
        val id = (rows.firstOrNull()?.get("_id") as? Number)?.toLong()
        if (id != null) {
            return id
        }
        return Random().nextLong()
    }

    @JvmStatic
    fun doesThreadIdExist(context: Context, threadId: Long): Boolean {
        val uri = Uri.parse("content://mms-sms/conversations/$threadId/")
        // Existence check via simple_query (Rule 1); ContentQuery manages the cursor.
        return Query(context).query(
            QueryObj(contentUri = uri.toString(), projection = listOf("_id")),
        ).isNotEmpty()
    }

    private fun isEmailAddress(address: String?): Boolean {
        if (TextUtils.isEmpty(address)) {
            return false
        }
        val s = extractAddrSpec(address!!)
        return EMAIL_ADDRESS_PATTERN.matcher(s).matches()
    }

    private val EMAIL_ADDRESS_PATTERN: Pattern = Pattern.compile(
        "[a-zA-Z0-9\\+\\.\\_\\%\\-]{1,256}" +
            "\\@" +
            "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}" +
            "(" +
            "\\." +
            "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25}" +
            ")+",
    )

    private val NAME_ADDR_EMAIL_PATTERN: Pattern =
        Pattern.compile("\\s*(\"[^\"]*\"|[^<>\"]+)\\s*<([^<>]+)>\\s*")

    private fun extractAddrSpec(address: String): String {
        val match = NAME_ADDR_EMAIL_PATTERN.matcher(address)
        return if (match.matches()) {
            match.group(2)!!
        } else {
            address
        }
    }

    /** Builds the default [Settings] from the app's default SharedPreferences. */
    @JvmStatic
    fun getDefaultSendSettings(context: Context): Settings {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val sendSettings = Settings()

        sendSettings.mmsc = sharedPrefs.getString("mmsc_url", "") ?: ""
        sendSettings.proxy = sharedPrefs.getString("mms_proxy", "") ?: ""
        sendSettings.port = sharedPrefs.getString("mms_port", "") ?: ""
        sendSettings.agent = sharedPrefs.getString("mms_agent", "") ?: ""
        sendSettings.userProfileUrl = sharedPrefs.getString("mms_user_agent_profile_url", "") ?: ""
        sendSettings.uaProfTagName = sharedPrefs.getString("mms_user_agent_tag_name", "") ?: ""
        sendSettings.group = sharedPrefs.getBoolean("group_message", true)
        sendSettings.deliveryReports = sharedPrefs.getBoolean("delivery_reports", false)
        sendSettings.split = sharedPrefs.getBoolean("split_sms", false)
        sendSettings.splitCounter = sharedPrefs.getBoolean("split_counter", false)
        sendSettings.stripUnicode = sharedPrefs.getBoolean("strip_unicode", false)
        sendSettings.signature = sharedPrefs.getString("signature", "") ?: ""
        sendSettings.sendLongAsMms = true
        sendSettings.sendLongAsMmsAfter = 3

        return sendSettings
    }

    /** True on Android 4.4 (KitKat) or newer. */
    @JvmStatic
    fun hasKitKat(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT

    /** True if this app is the device's default SMS app (always true pre-KitKat). */
    @JvmStatic
    fun isDefaultSmsApp(context: Context): Boolean {
        if (hasKitKat()) {
            return context.packageName == Telephony.Sms.getDefaultSmsPackage(context)
        }
        return true
    }

    @JvmStatic
    fun isMmsOverWifiEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean("mms_over_wifi", false)

    @JvmStatic
    fun getDefaultSubscriptionId(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            SmsManager.getDefaultSmsSubscriptionId()
        } else {
            DEFAULT_SUBSCRIPTION_ID
        }
    }
}
