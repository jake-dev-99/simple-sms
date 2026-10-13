package io.simplezen.simple_sms.messaging

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import io.simplezen.simple_query.ContentQuery
import io.simplezen.simple_sms.mms.codec.DeliveryInd
import io.simplezen.simple_sms.mms.codec.PduHeaders
import io.simplezen.simple_sms.mms.codec.PduParser
import io.simplezen.simple_sms.mms.codec.PduPersister
import io.simplezen.simple_sms.mms.codec.SendConf
import java.io.IOException

// MMS delivery reports (UNFY-110).
//
// Every outbound MMS asks its MMSC for a delivery report (X-Mms-Delivery-Report,
// set when the PDU is built). The MMSC's SendConf names the message; that id is
// recorded on the row as `m_id` with its SENT result ([sendResultWrite]). When
// a recipient fetches the message (or never can), the MMSC sends an
// M-Delivery.ind WAP push naming the same id, the recipient, and its
// X-Mms-Status. A group MMS gets one per recipient; [MmsDeliveryLedger] adds
// them up, and the message's verdict is recorded as the row's `st`
// ([persistMmsDeliveryStatus]): delivered once every recipient got it, failed
// once none will ([MmsDeliveryRecord.verdict]).
//
// Android stores no delivery indication itself, and neither does this app: the
// verdict lands on the outbound row, the row a consumer reads.

private const val TAG = "MmsDeliveryReports"

/** X-Mms-Status values (OMA MMS 7.3.54) that say the recipient got the message. */
private val MMS_DELIVERED_STATUSES = setOf(PduHeaders.STATUS_RETRIEVED, PduHeaders.STATUS_FORWARDED)

/** X-Mms-Status values that say the recipient will never get the message. */
internal val MMS_DELIVERY_FAILED_STATUSES =
    listOf(PduHeaders.STATUS_EXPIRED, PduHeaders.STATUS_REJECTED, PduHeaders.STATUS_UNREACHABLE)

/**
 * Whether an X-Mms-Status is a verdict: the recipient got the message, or
 * never will. Deferred, indeterminate and unrecognised say neither, so they
 * are not recorded.
 */
internal fun isMmsDeliveryVerdict(status: Int): Boolean =
    status in MMS_DELIVERED_STATUSES || status in MMS_DELIVERY_FAILED_STATUSES

/**
 * The MMSC's message id in a SENT result's SendConf (`SmsManager.EXTRA_MMS_DATA`),
 * stored as the provider stores `m_id`, or null when there is none: no
 * SendConf, a PDU that does not parse as one, or a SendConf without an id
 * (an MMSC that refused the send). Every null is logged; the MMS then shows
 * sent without a delivery verdict.
 */
internal fun mmsMessageIdFromSendConf(data: ByteArray?): String? {
    if (data == null) {
        Log.w(TAG, "MMS SENT result without a SendConf; its delivery report can't be matched")
        return null
    }
    // Parsing runs inside onReceive; a parser failure must not crash the
    // process (UNFY-282 containment).
    val pdu = try {
        PduParser(data, true).parse()
    } catch (e: RuntimeException) {
        Log.e(TAG, "MMS SendConf failed to parse (${data.size} bytes)", e)
        return null
    }
    if (pdu !is SendConf) {
        Log.w(TAG, "MMS SENT result data is not a SendConf (${pdu?.messageType})")
        return null
    }
    val messageId = pdu.messageId
    if (messageId == null || messageId.isEmpty()) {
        Log.w(TAG, "MMS SendConf without a message id (response status ${pdu.responseStatus})")
        return null
    }
    return PduPersister.toIsoString(messageId)
}

/** Every X-Mms-Status verdict ([isMmsDeliveryVerdict]). */
private val MMS_DELIVERY_VERDICTS = MMS_DELIVERED_STATUSES + MMS_DELIVERY_FAILED_STATUSES

/**
 * Which rows an MMS delivery verdict may write: the sent MMS the MMSC named
 * (`m_id`), while it holds no verdict. A verdict is terminal: it is the
 * message's once every recipient reported ([MmsDeliveryRecord.verdict]). Any
 * other `st` (none, or a provider's own default) is no verdict. The `m_id`
 * guard keeps a late report off a row that reused a deleted message's id.
 */
internal val MMS_DELIVERY_REPORT_SELECTION =
    "${Telephony.Mms.MESSAGE_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ? AND " +
        "(${Telephony.Mms.STATUS} IS NULL OR " +
        "${Telephony.Mms.STATUS} NOT IN (${MMS_DELIVERY_VERDICTS.joinToString { "?" }}))"

/** [MMS_DELIVERY_REPORT_SELECTION]'s arguments for the MMSC's [messageId]. */
internal fun mmsDeliveryReportArgs(messageId: String): List<String> =
    listOf(messageId, Telephony.Mms.MESSAGE_BOX_SENT.toString()) + MMS_DELIVERY_VERDICTS.map { it.toString() }

/**
 * Purpose: records an MMS delivery verdict, [status], on [messageUri] (see
 * [MMS_DELIVERY_REPORT_SELECTION]). A write the provider ignored, or failed,
 * is owed and retried ([OwedRowWrites], UNFY-335).
 *
 * @param messageId the MMSC's message id, which the row must still carry.
 * @param status an X-Mms-Status verdict ([isMmsDeliveryVerdict]).
 * @returns how the write went.
 * @throws IllegalArgumentException when [status] is not a verdict.
 * @throws Exception the provider's failure, once the write is owed.
 */
internal fun persistMmsDeliveryStatus(
    context: Context,
    messageUri: Uri,
    messageId: String,
    status: Int,
): RowWriteOutcome {
    require(isMmsDeliveryVerdict(status)) { "X-Mms-Status 0x${Integer.toHexString(status)} is not a verdict" }
    return recordRowWrite(
        context,
        RowWrite(
            messageUri.toString(),
            RowWriteKind.DELIVERY_STATUS,
            mapOf(Telephony.Mms.STATUS to status.toLong()),
            MMS_DELIVERY_REPORT_SELECTION,
            mmsDeliveryReportArgs(messageId),
            // A verdict is terminal (see MMS_DELIVERY_REPORT_SELECTION).
            final = true,
        ),
    )
}

/**
 * The country an MMS's recipients are written in, as an ISO code: its SIM's
 * home country, since the sender wrote them there (abroad, the network's
 * country is another); else the country that SIM is on. The device's
 * network country ([compareNumbers]'s default) when this returns null: the
 * row names no subscription, or its SIM reports neither.
 */
internal fun mmsRecipientRegion(context: Context, subscriptionId: Int?): String? {
    val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
    val sim = subscriptionId?.takeIf(SubscriptionManager::isValidSubscriptionId)?.let(telephony::createForSubscriptionId)
        ?: return null
    return listOf(sim.simCountryIso, sim.networkCountryIso).firstOrNull { !it.isNullOrBlank() }?.uppercase()
}

/**
 * Whether two MMS addresses name the same recipient: an email address by
 * case-insensitive equality, a phone number by [compareNumbers], read in
 * [region] ([mmsRecipientRegion]). The parser already drops an address's
 * `/TYPE=` suffix.
 */
internal fun isSameMmsAddress(context: Context, region: String?, a: String, b: String): Boolean =
    if ('@' in a || '@' in b) {
        a.trim().equals(b.trim(), ignoreCase = true)
    } else {
        compareNumbers(context, setOf(a), b, region)
    }

/**
 * The recipient of [recipients] a report from [reported] is for: the only
 * recipient of a one-recipient MMS, whose report can be no one else's; else
 * the one recipient whose address matches. `null` when none or several match.
 */
internal fun reportingRecipient(context: Context, region: String?, reported: String?, recipients: List<String>): String? {
    if (recipients.size == 1) return recipients.single()
    if (reported == null) return null
    return recipients.filter { isSameMmsAddress(context, region, reported, it) }.singleOrNull()
}

/** The MMS address types a sent message's recipients are stored with. */
private val MMS_RECIPIENT_ADDRESS_TYPES = setOf(PduHeaders.TO, PduHeaders.CC, PduHeaders.BCC)

/**
 * The recipients of the MMS at [rowId], read from its `addr` rows (the same
 * table the Dart lookup reads), each once ([isSameMmsAddress] in [region]);
 * `null` when the provider returned no cursor.
 *
 * @throws Exception the provider's failure.
 */
private fun mmsRecipients(context: Context, rowId: Long, region: String?): List<String>? {
    val rows = ContentQuery.queryOrNull(
        context,
        "content://mms/$rowId/addr",
        arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
    ) ?: return null
    val recipients = mutableListOf<String>()
    rows.filter { (it[Telephony.Mms.Addr.TYPE] as? Long)?.toInt() in MMS_RECIPIENT_ADDRESS_TYPES }
        .mapNotNull { it[Telephony.Mms.Addr.ADDRESS] as? String }
        .filter { it.isNotBlank() }
        // One recipient stored twice, in two formats, is one recipient: its
        // one report must match one address ([reportingRecipient]).
        .forEach { address -> if (recipients.none { isSameMmsAddress(context, region, it, address) }) recipients += address }
    return recipients
}

/**
 * Purpose: records an M-Delivery.ind on the sent MMS it names. A report
 * without a verdict, without a message id, or for no sent MMS this device
 * holds, changes nothing; nor does one that waits on other recipients, or
 * names none of the row's recipients. Failures are logged, never thrown:
 * nothing waits on a report.
 *
 * @param report the parsed WAP push.
 * @returns nothing.
 * @throws nothing.
 */
internal fun recordMmsDeliveryReport(context: Context, report: DeliveryInd) {
    val messageIdBytes = report.messageId
    if (messageIdBytes == null || messageIdBytes.isEmpty()) {
        Log.w(TAG, "MMS delivery report without a message id; ignored")
        return
    }
    val messageId = PduPersister.toIsoString(messageIdBytes)
    val status = report.status
    if (!isMmsDeliveryVerdict(status)) {
        Log.i(TAG, "MMS delivery report for $messageId without a verdict (0x${Integer.toHexString(status)}); ignored")
        return
    }
    val rows = try {
        ContentQuery.queryOrNull(
            context,
            Telephony.Mms.CONTENT_URI.toString(),
            arrayOf(Telephony.Mms._ID, Telephony.Mms.SUBSCRIPTION_ID),
            "${Telephony.Mms.MESSAGE_ID} = ? AND ${Telephony.Mms.MESSAGE_TYPE} = ?",
            arrayOf(messageId, PduHeaders.MESSAGE_TYPE_SEND_REQ.toString()),
        )
    } catch (e: Exception) {
        Log.e(TAG, "Could not look up the MMS for delivery report $messageId", e)
        return
    }
    if (rows == null) {
        Log.e(TAG, "The provider returned no cursor for delivery report $messageId; ignored")
        return
    }
    // Each row's id, and its SIM subscription when recorded.
    val rowSubscriptions = linkedMapOf<Long, Int?>()
    for (row in rows) {
        val rowId = row[Telephony.Mms._ID] as? Long
        if (rowId == null) {
            Log.e(TAG, "MMS row for delivery report $messageId has no integer _id: $row")
            continue
        }
        rowSubscriptions[rowId] = (row[Telephony.Mms.SUBSCRIPTION_ID] as? Long)?.toInt()
    }
    // A row whose SENT result is still owed holds no m_id yet: its verdict
    // waits for that write ([OwedRowWrites.waitsForSendResult]). One MMSC id
    // names one message, so a row that carries it owes no such write.
    if (rowSubscriptions.isEmpty()) {
        OwedRowWrites.forApp(context).rowsOwingMmsMessageId(messageId).forEach {
            rowSubscriptions[ContentUris.parseId(it)] = null
        }
    }
    if (rowSubscriptions.isEmpty()) {
        Log.i(TAG, "No sent MMS carries $messageId; its delivery report is ignored")
        return
    }
    val reported = report.to?.firstOrNull()?.string
    for ((rowId, subscriptionId) in rowSubscriptions) {
        recordRowDeliveryReport(context, rowId, subscriptionId, messageId, reported, status)
    }
}

// Adds [reported]'s verdict to the row's others ([MmsDeliveryLedger]) and
// records the message's verdict once there is one.
private fun recordRowDeliveryReport(
    context: Context,
    rowId: Long,
    subscriptionId: Int?,
    messageId: String,
    reported: String?,
    status: Int,
) {
    val messageUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, rowId)
    val region = mmsRecipientRegion(context, subscriptionId)
    val recipients = try {
        mmsRecipients(context, rowId, region)
    } catch (e: Exception) {
        Log.e(TAG, "Could not read the recipients of $messageUri for delivery report $messageId", e)
        return
    }
    if (recipients.isNullOrEmpty()) {
        Log.e(TAG, "No recipients readable for $messageUri; delivery report $messageId ignored")
        return
    }
    val recipient = reportingRecipient(context, region, reported, recipients) ?: run {
        Log.w(TAG, "Delivery report $messageId names no single recipient of $messageUri ($reported); ignored")
        return
    }
    val verdict = try {
        MmsDeliveryLedger.forApp(context).record(rowId, messageId, recipient, status, recipients.size)
    } catch (e: IOException) {
        // A one-recipient MMS's report is its verdict; a group's can't be
        // added up, so it is left unrecorded rather than stand for the message.
        Log.e(TAG, "MMS delivery ledger unavailable for $messageUri", e)
        if (recipients.size == 1) MmsDeliveryRecord(messageId).withVerdict(recipient, status).verdict(1) else null
    }
    if (verdict == null) {
        Log.d(TAG, "$messageUri delivery report recorded; awaiting other recipients")
        return
    }
    try {
        val written = persistMmsDeliveryStatus(context, messageUri, messageId, verdict)
        Log.d(TAG, "MMS delivery 0x${Integer.toHexString(verdict)} for $messageUri ($written)")
    } catch (e: Exception) {
        Log.e(TAG, "Failed recording the delivery status for $messageUri", e)
    }
}
