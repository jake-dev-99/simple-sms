package io.simplezen.simple_sms.device

import android.net.Uri
import android.provider.Telephony

/**
 * Which native messaging table a per-message write targets.
 *
 * The provider read contract is channel-qualified: `NormalizedMessage` carries
 * `channel` plus the native `_id`, and a bare `_id` is ambiguous because SMS
 * and MMS are separate tables with independent `_id` sequences. Per-message
 * writes (mark-read, delete) must carry the same channel so they hit the one
 * correct table — never the old SMS-first guess, which marked or deleted the
 * wrong message when an SMS and an MMS happened to share an `_id` (UNFY-213).
 */
enum class MessageTable { SMS, MMS }

/**
 * Resolve the target table from the channel discriminator the Dart provider
 * sends — `SmsMmsType.name`, i.e. `"sms"` or `"mms"`.
 *
 * Returns `null` for an absent or unrecognized channel so the caller surfaces
 * an error rather than silently defaulting to a table (no silent default at a
 * trust boundary — the same discipline that the SMS-first fallback violated).
 */
fun messageTableFor(channel: String?): MessageTable? = when (channel) {
    "sms" -> MessageTable.SMS
    "mms" -> MessageTable.MMS
    else -> null
}

/** The `content://` base URI for this [MessageTable]. */
fun MessageTable.contentUri(): Uri = when (this) {
    MessageTable.SMS -> Telephony.Sms.CONTENT_URI
    MessageTable.MMS -> Telephony.Mms.CONTENT_URI
}
