package io.simplezen.simple_sms.device

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import io.simplezen.simple_sms.messaging.selectSubscriptionId
import io.simplezen.simple_sms.mms.MMSPart
import io.simplezen.simple_sms.mms.codec.PduPersister
import io.simplezen.simple_sms.mms.sending.Transaction
import java.io.File

/**
 * Persists an MMS draft (`msg_box = 3`) through the same `m_send_req` PDU a
 * send composes, so the draft is the message a send would transmit (UNFY-281).
 *
 * The PDU persister allocates the thread from the recipients; the caller reads
 * the thread back and rejects a draft that landed in another one.
 */
internal class MmsDraftWriter(private val context: Context) {
    /**
     * Persist [content] as a new draft addressed to [addresses] and return its
     * `_id`. The attachments keep their raw bytes and are followed by a
     * `text/plain` part for a non-blank body. The row is marked read and seen:
     * a draft is never unread or unseen, so it must not count toward the
     * conversation's unread state or notifications.
     *
     * @throws IllegalStateException when the provider keeps no row or rejects the read + seen update.
     */
    fun persist(addresses: List<String>, content: MmsDraftContent): Long {
        val subscriptionId = selectSubscriptionId(content.subscriptionId) { SmsManager.getDefaultSmsSubscriptionId() }
        val req = Transaction.composeSendReq(
            context,
            null,
            addresses.toTypedArray(),
            null,
            parts(content),
            subscriptionId,
        )
        val uri = PduPersister.getPduPersister(context).persist(
            req,
            Telephony.Mms.Draft.CONTENT_URI,
            true,
            true,
            null,
            subscriptionId,
        )
        val values = ContentValues().apply {
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
        }
        val draftId = ContentUris.parseId(uri)
        check(context.contentResolver.update(uri, values, null, null) > 0) {
            "The provider kept no row to mark draft $draftId read and seen"
        }
        return draftId
    }

    private fun parts(content: MmsDraftContent): List<MMSPart> =
        content.attachments.map { attachment ->
            MMSPart().apply {
                Name = File(attachment.path).name
                MimeType = attachment.mimeType
                Data = attachment.bytes
            }
        } + listOfNotNull(
            content.body?.takeIf { it.isNotBlank() }?.let { body ->
                MMSPart().apply {
                    Name = "text"
                    MimeType = "text/plain"
                    Data = body.toByteArray()
                }
            },
        )
}
