package io.simplezen.simple_sms.codec

import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduPart
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden contract for the outbound SMIL presentation document.
 *
 * Pins [SmilPresentationBuilder.build] byte-for-byte against the **real
 * output of the vendored AOSP/Klinker SMIL stack** (`SmilHelper` +
 * `SmilXmlSerializer`) that it replaces. The golden strings below were
 * captured directly from that vendored implementation (via a temporary
 * Robolectric harness, Base64-encoded to avoid report double-escaping)
 * before it was deleted — so this test *is* the equivalence proof: the
 * wire bytes handed to `PduComposer` by `Transaction.buildPdu` are
 * unchanged by the migration.
 *
 * Cases cover the behaviours that shaped the old DOM output: the fixed
 * `<head><layout/>` shell, the `<par dur="8000ms">` default, the text/media
 * `<par>` grouping state machine, the single empty `<par>` an empty (or
 * all-unknown-type) body still emits, and XML attribute escaping.
 *
 * Robolectric only because `PduPart` touches `android.net.Uri` and the
 * builder logs unknown content types via `android.util.Log`; the builder
 * itself is otherwise pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboundSmilGoldenTest {

    private val header =
        "<smil xmlns=\"http://www.w3.org/2001/SMIL20/Language\">" +
            "<head><layout/></head><body>"
    private val footer = "</body></smil>"

    private fun part(loc: String, type: String): PduPart =
        PduPart().apply {
            setContentType(type.toByteArray())
            setContentLocation(loc.toByteArray())
            setData("x".toByteArray())
        }

    private fun smil(body: PduBody): String =
        String(SmilPresentationBuilder.build(body), Charsets.UTF_8)

    @Test
    fun textOnly_singleParWithTextElement() {
        val body = PduBody().apply { addPart(part("text-0.txt", "text/plain")) }
        assertEquals(
            header + "<par dur=\"8000ms\"><text src=\"text-0.txt\"/></par>" + footer,
            smil(body),
        )
    }

    @Test
    fun textThenImage_groupedIntoOnePar() {
        val body = PduBody().apply {
            addPart(part("text-0.txt", "text/plain"))
            addPart(part("img.jpg", "image/jpeg"))
        }
        assertEquals(
            header +
                "<par dur=\"8000ms\"><text src=\"text-0.txt\"/><img src=\"img.jpg\"/></par>" +
                footer,
            smil(body),
        )
    }

    @Test
    fun textMediaTextMedia_startsSecondParAfterFirstIsFull() {
        val body = PduBody().apply {
            addPart(part("a.txt", "text/plain"))
            addPart(part("a.jpg", "image/jpeg"))
            addPart(part("b.txt", "text/plain"))
            addPart(part("b.jpg", "image/jpeg"))
        }
        assertEquals(
            header +
                "<par dur=\"8000ms\"><text src=\"a.txt\"/><img src=\"a.jpg\"/></par>" +
                "<par dur=\"8000ms\"><text src=\"b.txt\"/><img src=\"b.jpg\"/></par>" +
                footer,
            smil(body),
        )
    }

    @Test
    fun emptyBody_emitsSingleEmptyPar() {
        assertEquals(
            header + "<par dur=\"8000ms\"/>" + footer,
            smil(PduBody()),
        )
    }

    @Test
    fun unknownContentType_omittedLeavingEmptyPar() {
        val body = PduBody().apply { addPart(part("mystery.bin", "application/octet-stream")) }
        assertEquals(
            header + "<par dur=\"8000ms\"/>" + footer,
            smil(body),
        )
    }

    @Test
    fun audioOnly_usesAudioTag() {
        val body = PduBody().apply { addPart(part("a.mp3", "audio/mpeg")) }
        assertEquals(
            header + "<par dur=\"8000ms\"><audio src=\"a.mp3\"/></par>" + footer,
            smil(body),
        )
    }

    @Test
    fun videoOnly_usesVideoTag() {
        val body = PduBody().apply { addPart(part("v.mp4", "video/mp4")) }
        assertEquals(
            header + "<par dur=\"8000ms\"><video src=\"v.mp4\"/></par>" + footer,
            smil(body),
        )
    }

    @Test
    fun vcardCountsAsMedia_forParGrouping() {
        // vcard sets hasMedia (not hasText) in SmilHelper, so text+vcard fill
        // one <par> and a following text starts a new one.
        val body = PduBody().apply {
            addPart(part("a.txt", "text/plain"))
            addPart(part("contact.vcf", "text/x-vCard"))
            addPart(part("b.txt", "text/plain"))
        }
        assertEquals(
            header +
                "<par dur=\"8000ms\"><text src=\"a.txt\"/><vcard src=\"contact.vcf\"/></par>" +
                "<par dur=\"8000ms\"><text src=\"b.txt\"/></par>" +
                footer,
            smil(body),
        )
    }

    @Test
    fun srcWithXmlMetacharacters_isEscaped() {
        val body = PduBody().apply { addPart(part("a&b<c>\"d'e.txt", "text/plain")) }
        assertEquals(
            header +
                "<par dur=\"8000ms\"><text src=\"a&amp;b&lt;c&gt;&quot;d&apos;e.txt\"/></par>" +
                footer,
            smil(body),
        )
    }
}
