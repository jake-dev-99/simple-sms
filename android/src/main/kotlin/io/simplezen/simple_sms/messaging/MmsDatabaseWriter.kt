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
// AOSP MMS PDU library at `io.simplezen.simple_sms.mms.codec`.
import io.simplezen.simple_sms.mms.codec.PduHeaders
import android.util.Log
import io.simplezen.simple_sms.messaging.OutboundSender.MessageRequestDetails
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

            // formatNumber returns null for non-parseable inputs
            // (shortcodes, alphanumeric senders). Preserve the raw
            // string in that case — it's still a valid display value
            // and downstream comparisons handle both forms.
            // Add Sender
            if(sender.isNotEmpty())
                add(MmsAddr(address = formatNumber(context, sender) ?: sender, type = PduHeaders.FROM, msgId = msgId, charset = charset))

            // Add CC
            if(ccList.isNotEmpty())
                addAll(ccList.map { cc ->
                    MmsAddr(address = formatNumber(context, cc) ?: cc, type = PduHeaders.CC, msgId = msgId, charset = charset)
                })

            // Add Recipients
            if(toList.isNotEmpty())
                addAll(toList.map { addr ->
                    MmsAddr(address = formatNumber(context, addr) ?: addr, type = PduHeaders.TO, msgId = msgId, charset = charset)
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

    /**
     * Inserts the outbound SMS row in the outbox (UNFY-299) and returns its
     * URI. It moves to sent or failed with its SENT result
     * ([recordSendResult]), or to failed when that result never comes
     * ([markStaleOutboxFailed], which only touches rows this app created).
     *
     * Throws [SecurityException] when the provider refuses the insert: it then
     * returns a placeholder row id 0 rather than null (the WRITE_SMS app-op is
     * denied unless this is the default SMS app), and nothing is sent.
     */
    fun insertSms(context: Context, requestDetails: MessageRequestDetails): Uri {
        val values = ContentValues().apply {
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
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
            // The platform provider stamps the calling package itself; set it
            // for providers that don't, since the stale-outbox sweep matches it.
            put(Telephony.Sms.CREATOR, context.packageName)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            requestDetails.subscriptionId?.let {
                put(Telephony.Sms.SUBSCRIPTION_ID, it)
            }
        }
        // The insert URI decides the row's type on the platform provider.
        val uri = context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
        if (uri == null || ContentUris.parseId(uri) <= 0) {
            throw SecurityException("SMS provider refused the outbox insert ($uri); is this the default SMS app?")
        }
        return uri
    }

    fun insertMmsParts(
        context: Context,
        newMsgId: Long,
        parts: List<MmsPart>,
    ): MutableList<Map<String, Any?>> {

        // Write MMS parts (text, image, etc.).
        //
        // The previous structure wrapped BOTH the `_data` blob write and the
        // `finalParts.add(...)` in the same `!mimeType.contains("text")` guard,
        // which silently dropped text/* parts from the list returned to Dart.
        // The text rows were still inserted into the provider, but the
        // realtime payload sent over `transferInboundMessage` had zero text
        // parts — so Dart-side `_extractTextBody` returned null, and inbound
        // MMS persisted with empty body until the next ContentResolver sync
        // re-queried the parts table.
        //
        // The blob write IS conditional (text parts carry their content in the
        // TEXT column via `MmsPart.contentValues`, not a file-backed `_data`).
        // The `finalParts.add` is NOT — every successfully-inserted part must
        // make it back to Dart.
        val mmsPartsUri = Mms.Part.getPartUriForMessage(newMsgId.toString())
        val finalParts: MutableList<Map<String, Any?>> = mutableListOf ()
        for (part in parts) {
            val newUri = context.contentResolver.insert(mmsPartsUri, part.contentValues)
            if (newUri == null) {
                Log.e(
                    TAG,
                    "Failed to insert MMS part: " +
                        "mime=${part.mimeType} cid=${part.contentId} cl=${part.contentLocation}"
                )
                continue
            }

            if (!part.mimeType.contains("text") && part.data.isNotEmpty()) {
                try {
                    context.contentResolver.openOutputStream(newUri)?.use { out ->
                        out.write(part.data)
                    } ?: Log.e(
                        TAG,
                        "Failed to open output stream for $newUri - data will be null"
                    )
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to write MMS part data")
                    Log.e(TAG, " >>> ${part.contentId}")
                    Log.e(TAG, " >>> ${part.contentLocation}")
                    Log.e(TAG, " >>> ${part.mimeType}")
                    Log.e(TAG, " >>> $e")
                }
            }

            val queried = Query(context)
                .query(QueryObj(contentUri = newUri.toString()))
                .firstOrNull()
            if (queried == null) {
                Log.e(TAG, "Inserted MMS part at $newUri but re-query returned no row")
                continue
            }
            val newPart = queried.toMutableMap().apply { put("uri", newUri) }
            finalParts.add(newPart)
            Log.d(TAG, "Inserted MMS part with ID: ${newPart[Mms.Part._ID]} mime=${part.mimeType}")
        }
        return finalParts
    }
}