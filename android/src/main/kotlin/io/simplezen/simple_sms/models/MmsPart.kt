package io.simplezen.simple_sms.models

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony.Mms
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.PduPart
import java.io.File
import java.nio.charset.Charset

/**
 * Decode a part's bytes using its IANA MIBenum charset.
 *
 * `PduPart.charset` is an IANA MIBenum integer (3 = US-ASCII, 4 = Latin-1,
 * 106 = UTF-8, 1015 = UCS-2, etc.). We resolve via [CharacterSets] from the
 * vendored PDU library, falling back to UTF-8 when the charset is
 * unspecified (0) or the resolved name isn't supported by the JVM.
 */
private fun decodeWithCharset(bytes: ByteArray, charsetMibEnum: Int): String {
    if (bytes.isEmpty()) return ""
    val name: String? = runCatching {
        if (charsetMibEnum > 0) CharacterSets.getMimeName(charsetMibEnum) else null
    }.getOrNull()
    val charset: Charset = name
        ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        ?: Charsets.UTF_8
    return String(bytes, charset)
}

data class MmsPart(
    var seq: Int,
    var filename: String,
    var name: String,

    var charset: Int,
    var mimeType: String,
    var contentLocation: String,
    var contentDisposition: String,
    var contentId: String,
    var data: ByteArray,
    var size: Long = 0,
    var text: String,
) {

    companion object {
        fun pduPartToMmsPart(context: Context, seq: Int, part: PduPart): MmsPart {
            val charset: Int = part.charset
            val name = String(part.name ?: byteArrayOf())
            val data: ByteArray = part.data ?: byteArrayOf()
            val size: Long = data.size.toLong()
            // R0-3: throw on null contentType. Empty MIME is a
            // corruption-class signal — every downstream consumer
            // (Dart's `ContentType.fromMime` post-PR #82, the Android
            // attachment renderer, the SMIL classifier) cannot
            // classify a part with no MIME. The previous behaviour
            // substituted "" and propagated; the part deserialized,
            // then exploded a layer up with no attribution to the
            // offending part.
            //
            // Decoded explicitly as US-ASCII because MIME content types
            // are spec'd as ASCII. `String(byteArray)` without a
            // charset uses the platform default, which varies across
            // devices/locales and can mis-decode non-ASCII bytes.
            //
            // Exception body intentionally avoids the part `name` —
            // attachment filenames can carry user-generated content
            // (and PII like `Photo from $phoneNumber.jpg`); shipping
            // those to Crashlytics is a privacy bug. `hasName` and
            // `nameLength` keep the diagnostic signal without leaking
            // content.
            val contentTypeBytes = part.contentType
                ?: throw IllegalStateException(
                    "MmsPart.pduPartToMmsPart: part contentType is null " +
                        "(seq=$seq, dataLen=${data.size}, " +
                        "hasName=${name.isNotEmpty()}, nameLength=${name.length})"
                )
            val contentType = String(contentTypeBytes, Charsets.US_ASCII)

            // S1 #13 / S2 #19: only treat the part as text when its
            // declared content type starts with "text/". Decode using
            // the part's declared charset (IANA MIBenum from PduPart),
            // not the JVM platform default. The previous implementation
            // used a "contains U+FFFD" heuristic over a platform-default
            // String decode, which produced mojibake on Latin-1 / GSM-7
            // / UCS-2 parts and false-positively classified attachment
            // bytes as text whenever their byte stream happened not to
            // contain the replacement character.
            val text: String = if (contentType.startsWith("text/", ignoreCase = true)) {
                runCatching { decodeWithCharset(data, charset) }.getOrDefault("")
            } else {
                ""
            }

            val contentDisposition = String(part.contentDisposition ?: byteArrayOf())
            val contentId = String(part.contentId ?: byteArrayOf())
            val filename = String(part.filename ?: byteArrayOf())
            val contentLocation = String(part.contentLocation ?: byteArrayOf())

            return MmsPart(
                seq = seq,
                mimeType = contentType,
                filename = filename,
                contentLocation = contentLocation,
                charset = charset,
                contentDisposition = contentDisposition,
                contentId = contentId,
                name = name,
                data = data,
                size = size,
                text = text,
            )
        }
    }

    val contentValues = ContentValues().apply {
        put(Mms.Part.SEQ, seq)
        put(Mms.Part.CONTENT_TYPE, mimeType)
        put(Mms.Part.NAME, name)

        put(Mms.Part.CONTENT_LOCATION,
            if(contentLocation.isNotEmpty())
                contentLocation
            else if(name.isNotEmpty())
                name
            else
                name
        )

        put(Mms.Part.CONTENT_ID,
            if(contentId.isNotEmpty())
                contentId
            else
                "<${File(name).nameWithoutExtension}>"
        )

        if(text.isNotEmpty())
            put(Mms.Part.TEXT, text)
        if(filename.isNotEmpty())
            put(Mms.Part.FILENAME, filename)
        if(charset > 0)
            put(Mms.Part.CHARSET, charset)
        if(contentDisposition.isNotEmpty())
            put(Mms.Part.CONTENT_DISPOSITION, contentDisposition)
    }
}