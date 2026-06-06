package com.klinker.android.send_message

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import com.android.mms.MmsConfig
import com.android.mms.util.DownloadManager
import com.android.mms.util.RateController
import com.google.android.mms.ContentType
import com.google.android.mms.InvalidHeaderValueException
import com.google.android.mms.MMSPart
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.SendReq
import com.google.android.mms.util_alt.SqliteWrapper
import io.simplezen.simple_sms.codec.SmilPresentationBuilder
import io.simplezen.simple_sms.queries.Query
import io.simplezen.simple_sms.queries.QueryObj
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Calendar
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Processes transaction requests for sending SMS/MMS. First-party Kotlin port of
 * the vendored Klinker `Transaction`; behaviour preserved.
 *
 * Provider access: all **reads** route through `simple_query`
 * (`Query`/`QueryObj` → `ContentQuery`) per the layering contract — the `_id`
 * lookups here and the `mms-sms/threadID` allocation in `Utils`. **Writes**
 * (`ContentResolver.insert` for the saved SMS row, `SqliteWrapper.update` to
 * flip an MMS to outbox) stay as direct provider calls: `simple_query` is a
 * read layer and the contract scopes the routing rule to reads.
 *
 * The static, mutable `settings` and `NOTIFY_SMS_FAILURE` (and the static
 * `buildPdu`/`sendMmsThroughSystem` helpers that read `settings`) mirror the
 * vendored design exactly.
 */
class Transaction(private val context: Context, settings: Settings) {

    private var explicitSentSmsReceiver: Intent? = null
    private var explicitSentMmsReceiver: Intent? = null
    private var explicitDeliveredSmsReceiver: Intent? = null

    private var saveMessage = true

    var SMS_SENT = ".SMS_SENT"
    var SMS_DELIVERED = ".SMS_DELIVERED"

    init {
        Transaction.settings = settings

        SMS_SENT = context.packageName + SMS_SENT
        SMS_DELIVERED = context.packageName + SMS_DELIVERED

        if (NOTIFY_SMS_FAILURE == ".NOTIFY_SMS_FAILURE") {
            NOTIFY_SMS_FAILURE = context.packageName + NOTIFY_SMS_FAILURE
        }
    }

    /** Sets context and initializes settings to default values. */
    constructor(context: Context) : this(context, Settings())

    /**
     * Send a new message depending on settings and the provided [Message].
     * If you want to send as MMS, call this from the UI thread.
     */
    fun sendNewMessage(
        message: Message,
        threadId: Long,
        sentMessageParcelable: Parcelable?,
        deliveredParcelable: Parcelable?,
    ) {
        this.saveMessage = message.save

        if (checkMMS(message)) {
            try {
                Looper.prepare()
            } catch (e: Exception) {
            }
            RateController.init(context)
            DownloadManager.init(context)

            if (!settings.group) {
                // send individual MMS to each person in the group of addresses
                for (address in message.addresses!!) {
                    sendMmsMessage(
                        message.text, message.fromAddress, arrayOf(address),
                        message.images, message.imageNames, message.getParts(), message.subject,
                        message.save, message.messageUri,
                    )
                }
            } else {
                sendMmsMessage(
                    message.text, message.fromAddress, message.addresses,
                    message.images, message.imageNames, message.getParts(), message.subject,
                    message.save, message.messageUri,
                )
            }
        } else {
            sendSmsMessage(
                message.text, message.addresses, threadId, message.delay,
                sentMessageParcelable, deliveredParcelable,
            )
        }
    }

    /** Send a new message; defaults the sent/delivered parcelables to empty bundles. */
    fun sendNewMessage(message: Message, threadId: Long) {
        this.sendNewMessage(message, threadId, Bundle(), Bundle())
    }

    fun setExplicitBroadcastForSentSms(intent: Intent): Transaction {
        explicitSentSmsReceiver = intent
        return this
    }

    fun setExplicitBroadcastForSentMms(intent: Intent): Transaction {
        explicitSentMmsReceiver = intent
        return this
    }

    fun setExplicitBroadcastForDeliveredSms(intent: Intent): Transaction {
        explicitDeliveredSmsReceiver = intent
        return this
    }

    private fun sendSmsMessage(
        text: String?,
        addresses: Array<String>?,
        threadId: Long,
        delay: Int,
        sentMessageParcelable: Parcelable?,
        deliveredParcelable: Parcelable?,
    ) {
        var text = text
        var threadId = threadId
        Log.v("send_transaction", "message text: $text")
        var messageUri: Uri? = null
        var messageId = 0
        if (saveMessage) {
            Log.v("send_transaction", "saving message")
            // add signature to original text to be saved in database (does not strip unicode for saving though)
            if (settings.signature != "") {
                text += "\n" + settings.signature
            }

            // save the message for each of the addresses
            for (i in addresses!!.indices) {
                val cal = Calendar.getInstance()
                val values = ContentValues()
                values.put("address", addresses[i])
                values.put("body", if (settings.stripUnicode) StripAccents.stripAccents(text!!) else text)
                values.put("date", cal.timeInMillis.toString() + "")
                values.put("read", 1)
                values.put("type", 4)

                // attempt to create correct thread id if one is not supplied
                if (threadId == NO_THREAD_ID || addresses.size > 1) {
                    threadId = Utils.getOrCreateThreadId(context, addresses[i])
                }

                Log.v("send_transaction", "saving message with thread id: $threadId")

                values.put("thread_id", threadId)
                messageUri = context.contentResolver.insert(Uri.parse("content://sms/"), values)

                Log.v("send_transaction", "inserted to uri: $messageUri")

                // Read the inserted row's _id via simple_query (Rule 1: reads
                // route through simple-query, not a bespoke ContentResolver.query).
                val idRows = Query(context).query(
                    QueryObj(contentUri = messageUri!!.toString(), projection = listOf("_id")),
                )
                if (idRows.isNotEmpty()) {
                    messageId = (idRows.first()["_id"] as? Number)?.toInt() ?: 0
                }

                Log.v("send_transaction", "message id: $messageId")

                // set up sent and delivered pending intents to be used with message request
                val sentIntent: Intent
                if (explicitSentSmsReceiver == null) {
                    sentIntent = Intent(SMS_SENT)
                    BroadcastUtils.addClassName(context, sentIntent, SMS_SENT)
                } else {
                    sentIntent = explicitSentSmsReceiver!!
                }

                sentIntent.putExtra("message_uri", if (messageUri == null) "" else messageUri.toString())
                sentIntent.putExtra(SENT_SMS_BUNDLE, sentMessageParcelable)
                // Android 12+ (S, API 31) requires explicit FLAG_IMMUTABLE / FLAG_MUTABLE
                // on every PendingIntent (see vendored note); IMMUTABLE is correct here
                // since SmsManager merges result extras at send-time.
                val sentPI = PendingIntent.getBroadcast(
                    context, messageId, sentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                val deliveredIntent: Intent
                if (explicitDeliveredSmsReceiver == null) {
                    deliveredIntent = Intent(SMS_DELIVERED)
                    BroadcastUtils.addClassName(context, deliveredIntent, SMS_DELIVERED)
                } else {
                    deliveredIntent = explicitDeliveredSmsReceiver!!
                }

                deliveredIntent.putExtra("message_uri", if (messageUri == null) "" else messageUri.toString())
                deliveredIntent.putExtra(DELIVERED_SMS_BUNDLE, deliveredParcelable)
                val deliveredPI = PendingIntent.getBroadcast(
                    context, messageId, deliveredIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                val sPI = ArrayList<PendingIntent?>()
                val dPI = ArrayList<PendingIntent?>()

                var body = text

                // edit the body of the text if unicode needs to be stripped
                if (settings.stripUnicode) {
                    body = StripAccents.stripAccents(body!!)
                }

                if (settings.preText != "") {
                    body = settings.preText + " " + body
                }

                val smsManager = SmsManagerFactory.createSmsManager(settings)
                Log.v("send_transaction", "found sms manager")

                if (settings.split) {
                    Log.v("send_transaction", "splitting message")
                    // figure out the length of supported message
                    val splitData = SmsMessage.calculateLength(body, false)

                    // total characters this message set can support, divided by the
                    // number of messages required, to get single-message length
                    var length = (body!!.length + splitData[2]) / splitData[0]
                    Log.v("send_transaction", "length: $length")

                    var counter = false
                    if (settings.splitCounter && body.length > length) {
                        counter = true
                        length -= 6
                    }

                    // get the split messages
                    val textToSend = splitByLength(body, length, counter)

                    // send each message part to each recipient attached to message
                    for (j in textToSend.indices) {
                        val parts = smsManager.divideMessage(textToSend[j])

                        for (k in parts.indices) {
                            sPI.add(if (saveMessage) sentPI else null)
                            dPI.add(if (settings.deliveryReports && saveMessage) deliveredPI else null)
                        }

                        Log.v("send_transaction", "sending split message")
                        sendDelayedSms(smsManager, addresses[i], parts, sPI, dPI, delay, messageUri)
                    }
                } else {
                    Log.v("send_transaction", "sending without splitting")
                    // send the message normally without forcing anything to be split
                    val parts = smsManager.divideMessage(body)

                    for (j in parts.indices) {
                        sPI.add(if (saveMessage) sentPI else null)
                        dPI.add(if (settings.deliveryReports && saveMessage) deliveredPI else null)
                    }

                    if (Utils.isDefaultSmsApp(context)) {
                        try {
                            Log.v("send_transaction", "sent message")
                            sendDelayedSms(smsManager, addresses[i], parts, sPI, dPI, delay, messageUri)
                        } catch (e: Exception) {
                            // whoops...
                            Log.v("send_transaction", "error sending message")
                            Log.e(TAG, "exception thrown", e)

                            try {
                                (context as Activity).window.decorView.findViewById<android.view.View>(
                                    android.R.id.content,
                                ).post {
                                    Toast.makeText(context, "Message could not be sent", Toast.LENGTH_LONG).show()
                                }
                            } catch (f: Exception) {
                            }
                        }
                    } else {
                        // not default app, so just fire it off right away for the hell of it
                        smsManager.sendMultipartTextMessage(addresses[i], null, parts, sPI, dPI)
                    }
                }
            }
        }
    }

    private fun sendDelayedSms(
        smsManager: SmsManager,
        address: String,
        parts: ArrayList<String>,
        sPI: ArrayList<PendingIntent?>,
        dPI: ArrayList<PendingIntent?>,
        delay: Int,
        messageUri: Uri?,
    ) {
        Thread {
            try {
                Thread.sleep(delay.toLong())
            } catch (e: Exception) {
            }

            if (checkIfMessageExistsAfterDelay(messageUri)) {
                Log.v("send_transaction", "message sent after delay")
                try {
                    smsManager.sendMultipartTextMessage(address, null, parts, sPI, dPI)
                } catch (e: Exception) {
                    Log.e(TAG, "exception thrown", e)
                }
            } else {
                Log.v("send_transaction", "message not sent after delay, no longer exists")
            }
        }.start()
    }

    private fun checkIfMessageExistsAfterDelay(messageUti: Uri?): Boolean {
        // Existence check via simple_query (Rule 1: reads route through
        // simple-query). ContentQuery manages the cursor.
        return Query(context).query(
            QueryObj(contentUri = messageUti!!.toString(), projection = listOf("_id")),
        ).isNotEmpty()
    }

    private fun sendMmsMessage(
        text: String?,
        fromAddress: String?,
        addresses: Array<String>?,
        image: Array<Bitmap>?,
        imageNames: Array<String>?,
        parts: List<Message.Part>?,
        subject: String?,
        save: Boolean,
        messageUri: Uri?,
    ) {
        // create the parts to send
        val data = ArrayList<MMSPart>()

        for (i in image!!.indices) {
            // turn bitmap into byte array to be stored
            val imageBytes = Message.bitmapToByteArray(image[i])

            val part = MMSPart()
            part.MimeType = "image/jpeg"
            part.Name = if (imageNames != null) imageNames[i] else "image_" + System.currentTimeMillis()
            part.Data = imageBytes
            data.add(part)
        }

        // add any extra media according to their mimeType set in the message
        //      eg. videos, audio, contact cards, location maybe?
        if (parts != null) {
            for (p in parts) {
                val part = MMSPart()
                if (p.getName() != null) {
                    part.Name = p.getName()!!
                } else {
                    part.Name = p.getContentType().split("/").toTypedArray()[0]
                }
                part.MimeType = p.getContentType()
                part.Data = p.getMedia()
                data.add(part)
            }
        }

        if (text != null && text != "") {
            // add text to the end of the part and send
            val part = MMSPart()
            part.Name = "text"
            part.MimeType = "text/plain"
            part.Data = text.toByteArray()
            data.add(part)
        }

        // minSdk is 30 and the simple-sms handler forces Settings.useSystemSending
        // = true, so the only reachable outbound path is the platform SmsManager
        // send. The Phase-3 outbound port removed the dead pre-Lollipop
        // MmsMessageSender path and the legacy service_alt transport. Since the
        // non-system transport is gone, useSystemSending=false can no longer be
        // honored — fail fast rather than silently route through the system
        // transport anyway. (In practice the handler always sets it true.)
        if (!settings.useSystemSending) {
            throw UnsupportedOperationException(
                "Non-system MMS sending was removed in the Phase-3 outbound port; " +
                    "Settings.useSystemSending=false is no longer supported.",
            )
        }
        sendMmsThroughSystem(context, subject, data, fromAddress, addresses, explicitSentMmsReceiver, save, messageUri)
    }

    class MessageInfo {
        @JvmField var token: Long = 0
        @JvmField var location: Uri? = null
        @JvmField var bytes: ByteArray? = null
    }

    // splits text and adds split counter when applicable
    internal fun splitByLength(s: String, chunkSize: Int, counter: Boolean): Array<String> {
        val arraySize = ceil(s.length.toDouble() / chunkSize).toInt()

        val returnArray = arrayOfNulls<String>(arraySize)

        var index = 0
        var i = 0
        while (i < s.length) {
            if (s.length - i < chunkSize) {
                returnArray[index++] = s.substring(i)
            } else {
                returnArray[index++] = s.substring(i, i + chunkSize)
            }
            i += chunkSize
        }

        if (counter && returnArray.size > 1) {
            for (j in returnArray.indices) {
                returnArray[j] = "(" + (j + 1) + "/" + returnArray.size + ") " + returnArray[j]
            }
        }

        @Suppress("UNCHECKED_CAST")
        return returnArray as Array<String>
    }

    /**
     * Whether a message will be sent as MMS depending on its contents and settings.
     */
    fun checkMMS(message: Message): Boolean {
        return message.images!!.isNotEmpty() ||
            (message.getParts().isNotEmpty()) ||
            (settings.sendLongAsMms && Utils.getNumPages(settings, message.text!!) > settings.sendLongAsMmsAfter) ||
            (message.addresses!!.size > 1 && settings.group) ||
            message.subject != null
    }

    companion object {
        private const val TAG = "Transaction"

        @JvmStatic
        lateinit var settings: Settings

        const val SENT_SMS_BUNDLE = "com.klinker.android.send_message.SENT_SMS_BUNDLE"
        const val DELIVERED_SMS_BUNDLE = "com.klinker.android.send_message.DELIVERED_SMS_BUNDLE"

        @JvmStatic
        var NOTIFY_SMS_FAILURE = ".NOTIFY_SMS_FAILURE"

        const val MMS_ERROR = "com.klinker.android.send_message.MMS_ERROR"
        const val NOTIFY_OF_DELIVERY = "com.klinker.android.send_message.NOTIFY_DELIVERY"
        const val NOTIFY_OF_MMS = "com.klinker.android.messaging.NEW_MMS_DOWNLOADED"

        const val NO_THREAD_ID = 0L

        const val DEFAULT_EXPIRY_TIME = 7L * 24 * 60 * 60

        @JvmField
        val DEFAULT_PRIORITY = PduHeaders.PRIORITY_NORMAL

        private fun sendMmsThroughSystem(
            context: Context,
            subject: String?,
            parts: List<MMSPart>,
            fromAddress: String?,
            addresses: Array<String>?,
            explicitSentMmsReceiver: Intent?,
            save: Boolean,
            existingMessageUri: Uri?,
        ) {
            try {
                val fileName = "send." + abs(Random().nextLong()).toString() + ".dat"
                val mSendFile = File(context.cacheDir, fileName)

                val sendReq = buildPdu(context, fromAddress, addresses, subject, parts)
                val messageUri: Uri?
                if (save) {
                    // default behavior unless the save flag is explicitly false
                    val persister = PduPersister.getPduPersister(context)
                    messageUri = persister.persist(
                        sendReq, Uri.parse("content://mms/outbox"),
                        true, settings.group, null, settings.getSubscriptionId(),
                    )
                } else {
                    messageUri = existingMessageUri
                    Log.v(TAG, messageUri.toString())

                    // update message status to outbox as we are resending the same message
                    val values = ContentValues(1)
                    values.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
                    val rowsUpdated = SqliteWrapper.update(
                        context, context.contentResolver, messageUri, values,
                        null, null,
                    )
                    Log.v(TAG, "rowsUpdated=$rowsUpdated")
                }

                val intent: Intent
                if (explicitSentMmsReceiver == null) {
                    intent = Intent(MmsSentReceiver.MMS_SENT)
                    BroadcastUtils.addClassName(context, intent, MmsSentReceiver.MMS_SENT)
                } else {
                    intent = explicitSentMmsReceiver
                }

                intent.putExtra(MmsSentReceiver.EXTRA_CONTENT_URI, messageUri.toString())
                intent.putExtra(MmsSentReceiver.EXTRA_FILE_PATH, mSendFile.path)
                // Android 12+ requires FLAG_IMMUTABLE / FLAG_MUTABLE (see SMS-side note).
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                val writerUri = Uri.Builder()
                    .authority(context.packageName + ".MmsFileProvider")
                    .path(fileName)
                    .scheme(ContentResolver.SCHEME_CONTENT)
                    .build()
                var writer: FileOutputStream? = null
                var contentUri: Uri? = null
                try {
                    writer = FileOutputStream(mSendFile)
                    writer.write(PduComposer(context, sendReq).make())
                    contentUri = writerUri
                } catch (e: IOException) {
                    Log.e(TAG, "Error writing send file", e)
                } finally {
                    if (writer != null) {
                        try {
                            writer.close()
                        } catch (e: IOException) {
                        }
                    }
                }

                val configOverrides = Bundle()
                configOverrides.putBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, settings.group)
                val httpParams = MmsConfig.getHttpParams()
                if (!TextUtils.isEmpty(httpParams)) {
                    configOverrides.putString(SmsManager.MMS_CONFIG_HTTP_PARAMS, httpParams)
                }
                configOverrides.putInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, MmsConfig.getMaxMessageSize())

                if (contentUri != null) {
                    SmsManagerFactory.createSmsManager(settings).sendMultimediaMessage(
                        context,
                        contentUri, null, configOverrides, pendingIntent,
                    )
                } else {
                    Log.e(TAG, "Error writing sending Mms")
                    try {
                        pendingIntent.send(SmsManager.MMS_ERROR_IO_ERROR)
                    } catch (ex: PendingIntent.CanceledException) {
                        Log.e(TAG, "Mms pending intent cancelled?", ex)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "error using system sending method", e)
            }
        }

        private fun buildPdu(
            context: Context,
            fromAddress: String?,
            recipients: Array<String>?,
            subject: String?,
            parts: List<MMSPart>,
        ): SendReq {
            val req = SendReq()
            // From, per spec
            req.prepareFromAddress(context, fromAddress, settings.getSubscriptionId())
            // To
            for (recipient in recipients!!) {
                req.addTo(EncodedStringValue(recipient))
            }
            // Subject
            if (!TextUtils.isEmpty(subject)) {
                req.setSubject(EncodedStringValue(subject))
            }
            // Date
            req.setDate(System.currentTimeMillis() / 1000)
            // Body
            val body = PduBody()
            var size = 0
            for (i in parts.indices) {
                val part = parts[i]
                size += addTextPart(body, part, i)
            }

            // add a SMIL document for compatibility (first-party Kotlin builder,
            // replacing the vendored SmilHelper/SmilXmlSerializer DOM stack)
            val smilPart = PduPart()
            smilPart.setContentId("smil".toByteArray())
            smilPart.setContentLocation("smil.xml".toByteArray())
            smilPart.setContentType(ContentType.APP_SMIL.toByteArray())
            smilPart.setData(SmilPresentationBuilder.build(body))
            body.addPart(0, smilPart)

            req.setBody(body)
            // Message size
            req.setMessageSize(size.toLong())
            // Message class
            req.setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray())
            // Expiry
            req.setExpiry(DEFAULT_EXPIRY_TIME)
            try {
                // Priority
                req.setPriority(DEFAULT_PRIORITY)
                // Delivery report
                req.setDeliveryReport(PduHeaders.VALUE_NO)
                // Read report
                req.setReadReport(PduHeaders.VALUE_NO)
            } catch (e: InvalidHeaderValueException) {
            }

            return req
        }

        private fun addTextPart(pb: PduBody, p: MMSPart, id: Int): Int {
            val filename = p.Name
            val part = PduPart()
            // Set Charset if it's a text media.
            if (p.MimeType.startsWith("text")) {
                part.setCharset(CharacterSets.UTF_8)
            }
            // Set Content-Type.
            part.setContentType(p.MimeType.toByteArray())
            // Set Content-Location.
            part.setContentLocation(filename.toByteArray())
            val index = filename.lastIndexOf(".")
            val contentId = if (index == -1) filename else filename.substring(0, index)
            part.setContentId(contentId.toByteArray())
            part.setData(p.Data)
            pb.addPart(part)

            return part.data!!.size
        }
    }
}
