package io.simplezen.simple_sms.codec

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.SendReq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1 · L1 — harness validation (UNFY-144).
 *
 * This is **not** a golden contract. Its only job is to prove the vendored
 * `pdu_alt` codec compiles and runs on a plain JVM under Robolectric, so the
 * real golden leaves (L2 inbound parse, L3 outbound compose) and the handler
 * unit tests (L4/L5) can run in CI without an emulator. It exercises the two
 * codec entry points the migration cares about — `PduComposer.make()` and
 * `PduParser.parse()` — via a self-consistent round-trip.
 *
 * Pinned at SDK 34: ≥ the module's minSdk (30) and within Robolectric
 * 4.14.1's supported range.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PduCodecHarnessTest {

    @Test
    fun sendReq_composesAndParses_roundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // A default SendReq() presets the mandatory headers (message-type,
        // MMS version, multipart/related content-type, From insert-address
        // token, transaction-id); we add the recipient, subject and a single
        // text part to make a complete, composable M-Send.req.
        val req = SendReq()
        req.setTo(arrayOf(EncodedStringValue("+15555550100")))
        req.setSubject(EncodedStringValue("golden-harness"))

        val body = PduBody()
        val part = PduPart()
        part.setContentType("text/plain".toByteArray())
        part.setContentId("<text-0>".toByteArray())
        // PduComposer requires each part to carry at least one of
        // name/filename/content-location (PduComposer.java:927-941).
        part.setContentLocation("text-0.txt".toByteArray())
        part.setData("hello golden".toByteArray())
        body.addPart(0, part)
        req.setBody(body)

        val bytes = PduComposer(context, req).make()
        assertNotNull("PduComposer.make() returned null — compose failed", bytes)

        val parsed = PduParser(bytes!!, true).parse()
        assertTrue(
            "expected SendReq from the round-trip, got ${parsed?.javaClass?.simpleName}",
            parsed is SendReq,
        )

        // Beyond the type check, assert a few fields survive the
        // compose -> parse pipeline so a subtle codec regression is caught —
        // without promoting this into a full byte-for-byte golden (that's L2).
        parsed as SendReq
        assertEquals(
            "recipient should round-trip",
            "+15555550100",
            parsed.to?.firstOrNull()?.string,
        )
        assertEquals("subject should round-trip", "golden-harness", parsed.subject?.string)
        val parsedBody = parsed.body
        assertNotNull("parsed SendReq should carry a body", parsedBody)
        assertEquals("body should have one part", 1, parsedBody!!.partsNum)
        assertEquals(
            "part content-type should round-trip",
            "text/plain",
            parsedBody.getPart(0).contentType?.let { String(it) },
        )
    }
}
