package io.simplezen.simple_sms.codec

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.mms.ContentType
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.SendReq
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1 · L3 — outbound compose golden (UNFY-146).
 *
 * Pins the **live outbound wire encoder**: `PduComposer(context, sendReq).make()`
 * — invoked by `Transaction.sendMmsThroughSystem` (Transaction.java:710) on a
 * `SendReq` built by `buildPdu` (Transaction.java:747). The composed bytes are
 * exactly what is handed to `SmsManager.sendMultimediaMessage`, i.e. what goes
 * on the wire. A future Kotlin codec (Phase 5) must reproduce these bytes
 * byte-for-byte.
 *
 * The `SendReq` here mirrors `buildPdu`'s output structure — From insert-token,
 * To, Subject, Date, a multipart body whose part 0 is the SMIL document (per
 * `buildPdu`'s `body.addPart(0, smilPart)`), Message-Class personal, Expiry,
 * Priority, and delivery/read report = No. The two normally-volatile fields
 * (transaction-id, which `SendReq()` time-generates, and Date) are **pinned**
 * so `make()` is deterministic and the golden below is stable.
 *
 * Two complementary assertions:
 *  - **byte-exact** vs [GOLDEN_HEX] — the reproduce-identically contract;
 *  - **round-trip** parse — semantic check with readable failures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PduComposeGoldenTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Pinned for determinism (see class doc).
    private val fixedTransactionId = "T-GOLDEN-001"
    private val fixedDateSeconds = 1_700_000_000L

    // A fixed, minimal SMIL document standing in for buildPdu's
    // SmilHelper-generated one. buildPdu always inserts a SMIL part at body
    // index 0 for carrier/client compatibility; the golden pins the COMPOSER,
    // not SmilHelper, so a static SMIL payload keeps the bytes deterministic.
    private val smilXml =
        "<smil><body><par><text src=\"text-0.txt\"/><img src=\"img.jpg\"/></par></body></smil>"

    /** Build the SendReq exactly as buildPdu would, with volatile fields pinned. */
    private fun buildGoldenSendReq(): SendReq {
        val req = SendReq()
        req.setTransactionId(fixedTransactionId.toByteArray())
        // From defaults to the insert-address-token in SendReq(); leave it.
        req.setTo(arrayOf(EncodedStringValue("+15555550100"), EncodedStringValue("+15555550111")))
        req.setSubject(EncodedStringValue("golden-compose"))
        req.setDate(fixedDateSeconds)

        val body = PduBody()

        // Text part.
        val textPart = PduPart()
        textPart.setCharset(CharacterSets.UTF_8)
        textPart.setContentType("text/plain".toByteArray())
        textPart.setContentLocation("text-0.txt".toByteArray())
        textPart.setContentId("text-0".toByteArray())
        textPart.setData("hello golden".toByteArray())
        body.addPart(textPart)

        // Binary (image) part — exercises the non-text encode path.
        val imgPart = PduPart()
        imgPart.setContentType("image/jpeg".toByteArray())
        imgPart.setContentLocation("img.jpg".toByteArray())
        imgPart.setContentId("img".toByteArray())
        imgPart.setData(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        body.addPart(imgPart)

        // SMIL part inserted at index 0 (mirrors buildPdu).
        val smilPart = PduPart()
        smilPart.setContentId("smil".toByteArray())
        smilPart.setContentLocation("smil.xml".toByteArray())
        smilPart.setContentType(ContentType.APP_SMIL.toByteArray())
        smilPart.setData(smilXml.toByteArray())
        body.addPart(0, smilPart)

        req.setBody(body)
        req.setMessageSize(("hello golden".length + 4).toLong())
        req.setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray())
        req.setExpiry(604_800L) // 7 days, buildPdu's DEFAULT_EXPIRY_TIME
        req.setPriority(PduHeaders.PRIORITY_NORMAL)
        req.setDeliveryReport(PduHeaders.VALUE_NO)
        req.setReadReport(PduHeaders.VALUE_NO)
        return req
    }

    @Test
    fun compose_isDeterministic_andByteExact() {
        val bytes = PduComposer(context, buildGoldenSendReq()).make()
        assertNotNull("PduComposer.make() returned null — compose failed", bytes)

        // Determinism: two composes of an equivalent SendReq are identical.
        val bytes2 = PduComposer(context, buildGoldenSendReq()).make()
        assertArrayEquals("compose must be deterministic for a fixed SendReq", bytes, bytes2)

        assertArrayEquals(
            "composed wire bytes drifted from the golden — a Kotlin reimpl must match this",
            hexToBytes(GOLDEN_HEX),
            bytes,
        )
    }

    @Test
    fun compose_roundTrips_preservingStructure() {
        val bytes = PduComposer(context, buildGoldenSendReq()).make()
        val parsed = PduParser(bytes!!, true).parse()
        assertTrue("expected SendReq, got ${parsed?.javaClass?.simpleName}", parsed is SendReq)
        parsed as SendReq

        assertEquals("message-type", PduHeaders.MESSAGE_TYPE_SEND_REQ, parsed.messageType)
        // Decode with an explicit charset — String(ByteArray) on the JVM would
        // otherwise use the platform default; the wire fields are ASCII so the
        // bytes are unaffected, but pinning UTF-8 keeps the test environment-proof.
        assertEquals("transaction-id", fixedTransactionId, String(parsed.transactionId, Charsets.UTF_8))
        assertEquals("date", fixedDateSeconds, parsed.date)
        assertEquals("subject", "golden-compose", parsed.subject?.string)
        assertEquals(
            "recipients",
            listOf("+15555550100", "+15555550111"),
            parsed.to?.map { it.string },
        )

        val body = parsed.body
        assertNotNull("SendReq should carry a body", body)
        assertEquals("body part count (smil + text + image)", 3, body!!.partsNum)
        // SMIL was inserted at index 0.
        assertEquals(
            "part 0 is the SMIL document",
            ContentType.APP_SMIL,
            String(body.getPart(0).contentType, Charsets.UTF_8),
        )
        // The text and image parts survive with their content-types.
        val parts = (0 until body.partsNum).map { body.getPart(it) }
        val contentTypes = parts.map { String(it.contentType, Charsets.UTF_8) }
        assertTrue("text/plain part present", contentTypes.contains("text/plain"))
        assertTrue("image/jpeg part present", contentTypes.contains("image/jpeg"))

        // Part payloads survive the parse path too — extends coverage beyond
        // structure/content-type to the parser's data decode (catches a
        // parse-side data regression the byte-exact compose golden would miss).
        val textPart = parts.first { String(it.contentType, Charsets.UTF_8) == "text/plain" }
        assertEquals("text payload preserved", "hello golden", String(textPart.data, Charsets.UTF_8))
        val imgPart = parts.first { String(it.contentType, Charsets.UTF_8) == "image/jpeg" }
        assertArrayEquals(
            "image bytes preserved",
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            imgPart.data,
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) {
            "GOLDEN_HEX must be an even number of hex digits, got ${clean.length}"
        }
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        // The exact bytes PduComposer.make() emits for buildGoldenSendReq()
        // (mirrors buildPdu's live output; transaction-id + date pinned).
        // Generated + decode-verified once, then frozen as the wire contract.
        private const val GOLDEN_HEX =
            "8C8098542D474F4C44454E2D303031008D9285046553F1008901819718EA" +
                "2B31353535353535303130302F545950453D504C4D4E009718EA2B313535" +
                "35353535303131312F545950453D504C4D4E009610EA676F6C64656E2D63" +
                "6F6D706F7365008A8088058103093A808F8186819081841BB38A3C736D69" +
                "6C3E00896170706C69636174696F6E2F736D696C00032F511B6170706C69" +
                "636174696F6E2F736D696C0085736D696C2E786D6C00C0223C736D696C3E" +
                "008E736D696C2E786D6C003C736D696C3E3C626F64793E3C7061723E3C74" +
                "657874207372633D22746578742D302E747874222F3E3C696D6720737263" +
                "3D22696D672E6A7067222F3E3C2F7061723E3C2F626F64793E3C2F736D69" +
                "6C3E270C0F8385746578742D302E7478740081EAC0223C746578742D303E" +
                "008E746578742D302E7478740068656C6C6F20676F6C64656E1C040A9E85" +
                "696D672E6A706700C0223C696D673E008E696D672E6A706700FFD8FFE0"
    }
}
