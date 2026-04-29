package io.simplezen.simple_sms.messaging

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony
import android.provider.Telephony.Mms
// Was: `android.support.mms.pdu.PduHeaders`, which lived inside the
// vendored `google_apps_messaging_core` Bugle module. That module has
// been removed; switch to the equivalent constants in our own vendored
// AOSP MMS PDU library at `com.google.android.mms.pdu_alt`.
import com.google.android.mms.pdu_alt.PduHeaders
import android.util.Log
import io.simplezen.simple_sms.messaging.OutboundMessagingHandler.MessageRequestDetails
import io.simplezen.simple_sms.models.MmsAddr
import io.simplezen.simple_sms.models.MmsObject
import io.simplezen.simple_sms.models.MmsPart
import io.simplezen.simple_sms.queries.Query
import io.simplezen.simple_sms.queries.QueryObj
import java.io.IOException


// MmsDatabaseWriter.kt
object MmsDatabaseWriter {
    private const val TAG = "MmsDatabaseWriter"

    fun insertMms(
        context: Context,
        mms: MmsObject,
    ): Map<String, Any?> {
        val newUri = context.contentResolver.insert(Mms.Inbox.CONTENT_URI, mms.contentValues)
            ?: throw IllegalStateException("Failed to insert MMS into database")

        val result = Query(context).query(QueryObj(contentUri = newUri.toString()))
        if (result.isEmpty()) {
            throw IllegalStateException("Inserted MMS but could not re-query: $newUri")
        }
        return result.first().toMutableMap().apply {
            put("uri", newUri)
        }
    }

    fun insertMmsAddrs(
        context: Context,
        msgId : Long,
        ccList: Set<String>,
        toList: Set<String>,
        sender: String,
        charset: Int
    ): List<Map<String, Any?>> {
        val addrs = mutableListOf<MmsAddr>().apply {

            // Add Sender
            if(sender.isNotEmpty())
                add(MmsAddr(address = formatNumber(context, sender), type = PduHeaders.FROM, msgId = msgId, charset = charset))

            // Add CC
            if(ccList.isNotEmpty())
                addAll(ccList.map { cc ->
                    MmsAddr(address = formatNumber(context, cc), type = PduHeaders.CC, msgId = msgId, charset = charset)
                })

            // Add Recipients
            if(toList.isNotEmpty())
                addAll(toList.map { addr ->
                    MmsAddr(address = formatNumber(context, addr), type = PduHeaders.TO, msgId = msgId, charset = charset)
                })
        }

        val result = mutableListOf<Map<String, Any?>>()
        for(addr in addrs){
            if(addr.address.isEmpty()) continue

            val contentUri = Mms.Addr.getAddrUriForMessage(msgId.toString())
            val newUri = context.contentResolver.insert(contentUri, addr.contentValues)
            if (newUri == null) {
                Log.w(TAG, "Failed to insert MMS address for message $msgId")
                continue
            }
            val addrId = ContentUris.parseId(newUri).toLong()

            val addrResult = Query(context).query(
                QueryObj(
                    contentUri = contentUri.toString(),
                    selection = "${BaseColumns._ID}=?",
                    selectionArgs = listOf(addrId.toString())
                ))
            if (addrResult.isEmpty()) continue
            val newAddr = addrResult.first().toMutableMap().apply {
                put("uri", newUri)
            }
            Log.d(TAG, "Inserted MMS address with ID: $addrId")
            result.add(newAddr)
        }
        return result
    }

     fun insertSms(context : Context, requestDetails : MessageRequestDetails) : Map<String, Any?> {
        val contentResolver : ContentResolver = context.contentResolver
        val values =  ContentValues().apply {
            val rawAddress = requestDetails.addresses.firstOrNull().orEmpty()
            val normalizedAddress = runCatching { formatNumber(context, rawAddress) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: rawAddress

            put(Telephony.Sms.ADDRESS, normalizedAddress)
            put(Telephony.Sms.BODY, requestDetails.body)
            put(Telephony.Sms.THREAD_ID, requestDetails.threadId)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            requestDetails.subscriptionId?.let {
                put(Telephony.Sms.SUBSCRIPTION_ID, it)
            }
        }
        val newUri = contentResolver.insert (Telephony.Sms.Sent.CONTENT_URI, values, null)
         val newPart = Query(context).query(QueryObj(contentUri = newUri.toString())).first().toMutableMap().apply {
                 put("uri", newUri)
             }
        return newPart
    }

    fun insertMmsParts(
        context: Context,
        newMsgId: Long,
        parts: List<MmsPart>,
    ): MutableList<Map<String, Any?>> {

        // Write MMS parts (text, image, etc.)
        val mmsPartsUri = Mms.Part.getPartUriForMessage(newMsgId.toString())
        val finalParts: MutableList<Map<String, Any?>> = mutableListOf ()
        for (part in parts) {
            var newUri = context.contentResolver.insert(mmsPartsUri, part.contentValues)

            // If data is not empty, write to the Uri
            if(newUri != null && !part.mimeType.contains("text") && part.data.isNotEmpty()) {
                try {
                    // Write the part data to the Uri
                    context.contentResolver.openOutputStream(newUri)?.use { out ->
                        out.write(part.data)
                    } ?: run {
                        Log.e(
                            TAG,
                            "Failed to open output stream for $newUri - data will be null"
                        )
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to write MMS part data")
                    Log.e(TAG, " >>> ${part.contentId}")
                    Log.e(TAG, " >>> ${part.contentLocation}")
                    Log.e(TAG, " >>> ${part.mimeType}")
                    Log.e(TAG, " >>> $e")
                }

                val newPart = Query(context).query(QueryObj(contentUri = newUri.toString())).first().toMutableMap().apply {
                        put("uri", newUri)
                    }
                finalParts.add(newPart)
                Log.d(TAG, "Inserted MMS part with ID: ${newPart[Mms.Part._ID]}")
            }
        }
        return finalParts
    }
}