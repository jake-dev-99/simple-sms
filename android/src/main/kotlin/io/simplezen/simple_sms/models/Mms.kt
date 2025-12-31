package io.simplezen.simple_sms.models

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony.BaseMmsColumns.MESSAGE_BOX_INBOX
import android.provider.Telephony.Mms
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.RetrieveConf
import android.telephony.SmsManager

data class MmsObject(
    // Required fields
    var threadId : Long,
    var messageSize : Long,
    var messageBox: Int,
    var date: Long = System.currentTimeMillis(),
    var subscriptionId: Int = SmsManager.getDefaultSmsSubscriptionId(),
    var read: Int = 1,
    var seen: Int = 0,

    // Optional fields
    var mmsVersion: Int? = null,
    var messageType: Int? = null,
    var subject: EncodedStringValue? = null,
    var contentType: String? = null,
    var contentClass: String? = null,
    var contentLocation: EncodedStringValue? = null,
    var messageId: String? = null,
    var deliveryReport: Int? = null,
    var readReport: Int? = null,
    var status: Int? = null,
    var subjectCharSet: Int? = null,
    var retrieveText: EncodedStringValue? = null,
    var retrieveStatus: Int? = null,
    var textOnly: Int? = null,
    var transactionId: String? = null,
    var priority: Int? = null,
) {

    companion object {
        fun pduToMms(context : Context, pdu : RetrieveConf, threadId: Long, textOnly : Boolean,  messageSize: Long, subscriptionId: Int) : MmsObject {

            val date: Long = System.currentTimeMillis()
            val subscriptionId: Int = SmsManager.getDefaultSmsSubscriptionId()

            // Optional fields
            val contentType: String? = if (pdu.contentType != null) String(pdu.contentType) else null
            val contentClass: String? = if (pdu.messageClass != null) String(pdu.messageClass) else null
            val messageId: String? = if (pdu.messageId != null) String(pdu.messageId) else null
            val status: Int? = 0
            val transactionId: String? = if (pdu.transactionId != null) String(pdu.transactionId) else null
            val priority: Int? = pdu.priority

            return MmsObject(
                contentClass = contentClass,
                contentLocation = EncodedStringValue(""),
                contentType = contentType,
                status = status,
                read = 0,
                seen = 1,
                date = date,
                deliveryReport = pdu.deliveryReport,
                messageBox = MESSAGE_BOX_INBOX,
                messageId = messageId,
                messageSize = messageSize,
                messageType = pdu.messageType,
                mmsVersion = pdu.mmsVersion,
                priority = priority,
                readReport = pdu.readReport,
                retrieveStatus = pdu.retrieveStatus,
                retrieveText =  pdu.retrieveText,
                subject = pdu.subject,
                subjectCharSet = pdu.subject?.characterSet,
                subscriptionId = subscriptionId,
                textOnly = if(textOnly) 1 else 0,
                transactionId = transactionId,
                threadId = threadId
            )
        }
    }

    val contentValues: ContentValues
        get() = ContentValues().apply {

            // Required fields
            put(Mms.THREAD_ID, threadId)
            put(Mms.MESSAGE_SIZE, messageSize)
            put(Mms.MESSAGE_BOX, messageBox)
            put(Mms.DATE, date)
            put(Mms.DATE_SENT, date)
            put(Mms.SUBSCRIPTION_ID, subscriptionId)
            put(Mms.SEEN, seen)
            put(Mms.READ, read)

            if(mmsVersion != null)
                put(Mms.MMS_VERSION, mmsVersion)
            if(subject != null)
                put(Mms.SUBJECT, subject!!.string)
            if(subjectCharSet != null)
                put(Mms.SUBJECT_CHARSET, subjectCharSet)
            if(messageType != null)
                put(Mms.MESSAGE_TYPE, messageType)
            if(contentType != null)
                put(Mms.CONTENT_TYPE, contentType)
            if(contentClass != null)
                put(Mms.CONTENT_CLASS, contentClass)
            if(contentLocation != null)
                put(Mms.CONTENT_LOCATION, contentLocation!!.string)
            if(messageId != null)
                put(Mms.MESSAGE_ID, messageId)
            if(deliveryReport != null)
                put(Mms.DELIVERY_REPORT, deliveryReport)
            if(readReport != null)
                put(Mms.READ_REPORT, readReport)
            if(status != null)
                put(Mms.STATUS, status)
            if(retrieveText != null)
                put(Mms.RETRIEVE_TEXT, retrieveText!!.string)
            if(retrieveStatus != null)
                put(Mms.RETRIEVE_STATUS, retrieveStatus)
            if(textOnly != null)
                put(Mms.TEXT_ONLY, textOnly)
            if(textOnly != null)
                put(Mms.TEXT_ONLY, textOnly)
            if(transactionId != null)
                put(Mms.TRANSACTION_ID, transactionId)
            if(priority != null)
                put(Mms.PRIORITY, priority)
        }
}