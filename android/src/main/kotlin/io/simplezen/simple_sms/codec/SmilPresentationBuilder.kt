package io.simplezen.simple_sms.codec

import android.util.Log
import com.google.android.mms.ContentType
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduPart

/**
 * Builds the SMIL presentation document for an **outbound** MMS body.
 *
 * First-party Kotlin replacement for the vendored AOSP/Klinker SMIL stack
 * (`com.google.android.mms.smil.SmilHelper` +
 * `com.android.mms.dom.smil.parser.SmilXmlSerializer` and the ~40-file
 * `com.android.mms.dom.*` / `org.w3c.dom.smil.*` generic-DOM subsystem they
 * dragged in). MMS only ever needed a tiny, fixed-shape document — a single
 * `<head><layout/>` and a sequence of `<par>` groups referencing the parts —
 * so the entire generic SMIL DOM was dead weight. This emits that document
 * directly.
 *
 * Output is pinned **byte-for-byte** against the vendored implementation's
 * real output by `OutboundSmilGoldenTest` (the golden bytes there were
 * captured from `SmilHelper`/`SmilXmlSerializer` before deletion), so the
 * wire bytes handed to `PduComposer` are unchanged. The `<par>` grouping
 * state machine, the `dur="8000ms"` default, the element tags, and the
 * attribute escaping all mirror `SmilHelper.createSmilDocument`.
 *
 * Invoked from `Transaction.buildPdu` (the lone consumer of the old seam).
 */
object SmilPresentationBuilder {
    private const val TAG = "SmilPresentationBuilder"

    // The vendored serializer emitted setDur(8.0f) as "8000ms".
    private const val PAR_DUR = "8000ms"

    private const val SMIL_NS = "http://www.w3.org/2001/SMIL20/Language"

    // Element tags, matching SmilHelper.ELEMENT_TAG_* exactly.
    private const val TAG_TEXT = "text"
    private const val TAG_IMAGE = "img"
    private const val TAG_AUDIO = "audio"
    private const val TAG_VIDEO = "video"
    private const val TAG_VCARD = "vcard"

    /**
     * Serialize the SMIL presentation for [body] to UTF-8 bytes, matching the
     * vendored `SmilXmlSerializer.serialize(SmilHelper.createSmilDocument(body))`
     * output exactly.
     */
    @JvmStatic
    fun build(body: PduBody): ByteArray {
        val sb = StringBuilder()
        sb.append("<smil xmlns=\"").append(SMIL_NS).append("\">")
        sb.append("<head><layout/></head>")
        sb.append("<body>")
        for (par in groupIntoPars(body)) {
            if (par.isEmpty()) {
                sb.append("<par dur=\"").append(PAR_DUR).append("\"/>")
            } else {
                sb.append("<par dur=\"").append(PAR_DUR).append("\">")
                for (el in par) {
                    sb.append('<').append(el.tag)
                        .append(" src=\"").append(escapeXml(el.src)).append("\"/>")
                }
                sb.append("</par>")
            }
        }
        sb.append("</body></smil>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private data class MediaEl(val tag: String, val src: String)

    /**
     * Reproduces `SmilHelper.createSmilDocument`'s `<par>` grouping: one
     * initial `<par>` is always present (so an empty body still emits a single
     * empty `<par>`); a new `<par>` starts whenever the current one already
     * holds **both** a text element and a media element and another part
     * arrives. Unknown content types are omitted from the SMIL (logged), as
     * the vendored helper did.
     */
    private fun groupIntoPars(body: PduBody): List<List<MediaEl>> {
        val pars = mutableListOf<MutableList<MediaEl>>()
        var current = mutableListOf<MediaEl>()
        pars.add(current)

        val partsNum = body.partsNum
        if (partsNum == 0) {
            return pars
        }

        var hasText = false
        var hasMedia = false
        for (i in 0 until partsNum) {
            if (hasMedia && hasText) {
                current = mutableListOf()
                pars.add(current)
                hasText = false
                hasMedia = false
            }

            val part: PduPart = body.getPart(i)
            // PduPart.contentType is now properly nullable (was a Java
            // platform type); preserve the prior NPE-on-null of new String(null).
            val contentType = String(part.contentType!!)

            when {
                contentType == ContentType.TEXT_PLAIN ||
                    contentType.equals(ContentType.APP_WAP_XHTML, ignoreCase = true) ||
                    contentType == ContentType.TEXT_HTML -> {
                    current.add(MediaEl(TAG_TEXT, part.generateLocation()))
                    hasText = true
                }
                ContentType.isImageType(contentType) -> {
                    current.add(MediaEl(TAG_IMAGE, part.generateLocation()))
                    hasMedia = true
                }
                ContentType.isVideoType(contentType) -> {
                    current.add(MediaEl(TAG_VIDEO, part.generateLocation()))
                    hasMedia = true
                }
                ContentType.isAudioType(contentType) -> {
                    current.add(MediaEl(TAG_AUDIO, part.generateLocation()))
                    hasMedia = true
                }
                contentType == ContentType.TEXT_VCARD -> {
                    current.add(MediaEl(TAG_VCARD, part.generateLocation()))
                    hasMedia = true
                }
                else -> Log.w(TAG, "Omitting part $i from SMIL: unknown content type '$contentType'")
            }
        }
        return pars
    }

    /**
     * Mirrors `SmilHelper.escapeXML` exactly — sequential literal replacement
     * of `&`, `<`, `>`, `"`, `'`, with `&` first so the inserted entities
     * aren't re-escaped.
     */
    private fun escapeXml(str: String): String =
        str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
