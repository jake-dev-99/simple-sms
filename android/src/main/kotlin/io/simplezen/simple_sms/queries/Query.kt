package io.simplezen.simple_sms.queries

import android.Manifest
import android.R.attr.mimeType
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.DEFAULT_SUBSCRIPTION_ID
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.core.telephony.TelephonyManagerCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.simplezen.simple_sms.SimpleSmsPlugin
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.absoluteValue

data class QueryObj (
    val contentUri: String,
    val projection: List<String>? = null,
    val selection: String? = null,
    val selectionArgs: List<String>? = null,
    val sortOrder: String? = null
)

// CuriousPigeon
class Query(val context: Context ) : MethodChannel.MethodCallHandler {

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "query" -> {
                val queryObj = QueryObj(
                    call.argument<String>("contentUri") ?: "",
                    call.argument<List<String>>("projection"),
                    call.argument<String>("selection"),
                    call.argument<List<String>>("selectionArgs"),
                    call.argument<String>("sortOrder")
                )
                val data = query(queryObj)
                result.success(data)
            }

            "getDeviceInfo" -> {
                val deviceInfo = if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_PHONE_NUMBERS
                    ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_PHONE_STATE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    (result.error("PERMISSION_DENIED", "Permission denied", null))
                } else {
                    getDeviceInfo()
                }
                result.success(deviceInfo)
            }

            "getSimInfo" -> {
                val simInfo = getSimInfo()
                result.success(simInfo)
            }

            "getFile" -> {
                val uri = call.argument<String>("uri")
                if (uri.isNullOrBlank()) {
                    result.error("INVALID_URI", "URI is required", null)
                    return
                }

                try {
                    val filePath = queryToFile(QueryObj(contentUri = uri))
                    result.success(filePath)
                } catch (e: Exception) {
                    Log.e("Query", "Error creating MMS part temp file", e)
                    result.error("FILE_READ_ERROR", e.message, null)
                }
            }
            else ->  result.notImplemented()
        }
    }

    private fun queryToFile(query: QueryObj): String? {
        val resolver = context.contentResolver
        val contentUri = query.contentUri.toUri()
        var inputStream: InputStream? = null
        var fos: FileOutputStream? = null
        var tempFile: File? = null

        try {
            resolver.query(contentUri, null, null, null, null).use { cursor ->
                if (cursor?.moveToFirst() == false) {
                    return null
                } else {
                    val contentTypeIndex = cursor!!.getColumnIndex("ct")
                    if (contentTypeIndex != -1) {
                        val contentType : String? = cursor.getString(contentTypeIndex) ?: ""
                        if (contentType!!.isNotEmpty() &&
                            contentType.contains("smil") || contentType.contains("text")
                        ) {
                            return null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("loadMmsPartData", "Error checking content type", e)
            return null
        }

        try {
            // Create a unique temporary file in the cache directory
            // Consider adding an extension based on mime type if available
            tempFile = File.createTempFile("mms_part_", null, context.cacheDir)

            inputStream = resolver.openInputStream(contentUri)
            if (inputStream == null) {
                Log.w("saveMmsPartToFile", "Could not open input stream for URI: $contentUri")
                tempFile.delete() // Clean up empty file
                return null
            }

            fos = FileOutputStream(tempFile)
            inputStream.copyTo(fos) // Efficiently copy stream to file

            // Log.d("saveMmsPartToFile", "Saved MMS part to: ${tempFile.absolutePath}")
            return tempFile.absolutePath // Return the path of the saved file

        } catch (e: Exception) {
            Log.e("saveMmsPartToFile", "Error saving MMS part to file", e)
            tempFile?.delete() // Attempt to clean up failed file
            return null
        } finally {
            try {
                inputStream?.close()
                fos?.close()
            } catch (ioe: Exception) {
                Log.e("saveMmsPartToFile", "Error closing streams", ioe)
            }
        }
    }


    fun query(query : QueryObj): List<Map<String, Any?>> {
        return getCursorData(context, query).map { it.toSortedMap() }
    }

    @RequiresPermission(allOf = [Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.READ_PHONE_STATE])
    fun getDeviceInfo(): MutableMap<String, Any?> {
        val results : MutableMap<String, Any?> = mutableMapOf()

        try {
            var brand: String = Build.MANUFACTURER
            var model: String = Build.MODEL
            val os: String = Build.VERSION.SDK_INT.toString()
            val sims : List<Map<String, Any?>> = getSimInfo()

            if (model.lowercase().startsWith(brand.lowercase())) {
                model = model.replace(brand, "").trim()
            }
            results["brand"] = brand
            results["model"] = model
            results["os"] = os
            results["sims"] = sims
        } catch (e: Exception) {
            Log.e("getAllProviders", " <<< Error: ${e.message}")
            e.printStackTrace()
        }

        return results.toSortedMap()
    }

    @RequiresPermission(allOf = [Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS])
    fun getSimInfo(): List<Map<String, Any?>> {
        val simCards = mutableListOf<Map<String, Any?>>()

        try {
            val telephonyManager : TelephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            val subscriptionManager : SubscriptionManager =
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

            val subscriptions = subscriptionManager.activeSubscriptionInfoList ?:  emptyList()
            for (subscription in subscriptions) {
                Log.d("Query", "number " + subscription.number)
                Log.d("Query", "network name : " + subscription.carrierName)
                Log.d("Query", "country iso " + subscription.countryIso)

                val phoneNumber =
                    if(Build.VERSION .SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_PHONE_NUMBERS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            SimpleSmsPlugin.requestPermissions(
                                arrayOf(Manifest.permission.READ_PHONE_NUMBERS)
                            )
                        }
                        subscriptionManager.getPhoneNumber(DEFAULT_SUBSCRIPTION_ID)
                    } else {
                        subscription.number
                    }

                val simCard =
                    mapOf<String, Any?>(
                        "slot" to subscription.simSlotIndex,
                        "phoneNumber" to phoneNumber,
                        "externalId" to -1,
                        "state" to QueryHelper.simStateMap[telephonyManager.getSimState(subscription.simSlotIndex)]?.lowercase() ,
                        "operatorName" to telephonyManager.simOperatorName,
                        "countryIso" to telephonyManager.simCountryIso,
                        "imei" to TelephonyManagerCompat.getImei(telephonyManager),
//                                "serialNumber" to telephonyManager.getSimSerialNumber(),
                        "carrierName" to telephonyManager.simOperatorName,
                        "isNetworkRoaming" to telephonyManager.isNetworkRoaming
                    )
                simCards.add(simCard)
            }
            Log.d("SimsQuery", "SIM cards: $simCards")
            return simCards.map { it.toSortedMap() }

        } catch (e: Exception) {
            e.printStackTrace()
            val response =
                mapOf<String, Any?>(
                    "slot" to -1,
                    "externalId" to "-1",
                    "state" to "UNKNOWN",
                    "operatorName" to "UNKNOWN",
                    "countryIso" to "UNKNOWN",
                    "serialNumber" to "UNKNOWN",
                    "carrierName" to "UNKNOWN",
                    "displayName" to "UNKNOWN",
                    "error" to e.message.toString(),
                    "isNetworkRoaming" to false
                )
            return listOf(response.toSortedMap())
        }
    }


    fun getCursorData(context : Context, query : QueryObj): List<Map<String, Any?>> {

        val contentResolver = context.contentResolver

        val contentUri = query.contentUri.toUri()
        val projection = query.projection?.toTypedArray()
        val selection = query.selection
        val selectionArgs = query.selectionArgs?.toTypedArray()
        val sortOrder = query.sortOrder

        val cursor: Cursor = contentResolver.query(
            contentUri,
            projection,
            selection,
            selectionArgs,
            sortOrder)
            ?: return emptyList()

        val returnable: MutableList<Map<String, Any?>> = mutableListOf()

        cursor.use {
            while (it.moveToNext()) {
                val row = HashMap<String, Any?>()
                for (index in 0 until cursor.columnCount) {
                    val columnName = cursor.getColumnName(index)
                    val columnType = cursor.getType(index)
                    when (columnType) {
                        Cursor.FIELD_TYPE_NULL -> row[columnName] = null
                        Cursor.FIELD_TYPE_INTEGER -> row[columnName] = cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> row[columnName] = cursor.getFloat(index)
                        Cursor.FIELD_TYPE_STRING -> row[columnName] = cursor.getString(index)
                        Cursor.FIELD_TYPE_BLOB -> row[columnName] = cursor.getBlob(index)
                        else -> {
                            throw Exception("Unknown column type: $columnType")
                        }
                    }
                }
                if (row.isEmpty()) {
                    continue
                }
                returnable.add(row)
            }
        }
        return returnable
    }

//    private fun _printQueryResults(objectName: String, results: Map<String, Any?>) {
//        val sortedMap = results.toSortedMap(compareBy<String> { it }.thenBy { it.length })
//
//        Log.d("printQueryResults", " <<< ")
//        Log.d("printQueryResults", " <<< -------------------------------")
//        Log.d("printQueryResults", " <<<    >>>> $objectName Data ")
//        Log.d("printQueryResults", " <<< -------------------------------")
//        Log.d("printQueryResults", " <<< ")
//        for (key in sortedMap.keys) Log.d("printQueryResults", " <<< $key: ${sortedMap[key]}")
//        Log.d("printQueryResults", " <<< ")
//        Log.d("printQueryResults", " <<< ")
//        Log.d("printQueryResults", " <<< ")
//    }
}

private class QueryHelper() {
    companion object {
        val simStateMap: HashMap<Int, String> =
            hashMapOf(
                TelephonyManager.SIM_STATE_UNKNOWN to "UNKNOWN",
                TelephonyManager.SIM_STATE_ABSENT to "ABSENT",
                TelephonyManager.SIM_STATE_PIN_REQUIRED to "PIN_REQUIRED",
                TelephonyManager.SIM_STATE_PUK_REQUIRED to "PUK_REQUIRED",
                TelephonyManager.SIM_STATE_NETWORK_LOCKED to "NETWORK_LOCKED",
                TelephonyManager.SIM_STATE_READY to "READY",
                TelephonyManager.SIM_STATE_NOT_READY to "NOT_READY",
                TelephonyManager.SIM_STATE_PERM_DISABLED to "PERM_DISABLED",
                TelephonyManager.SIM_STATE_CARD_IO_ERROR to "CARD_IO_ERROR",
                TelephonyManager.SIM_STATE_CARD_RESTRICTED to "CARD_RESTRICTED"
            )
    }
}