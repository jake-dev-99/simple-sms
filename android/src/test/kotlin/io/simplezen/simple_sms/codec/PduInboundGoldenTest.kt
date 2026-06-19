package io.simplezen.simple_sms.codec

import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.RetrieveConf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1 · L2 — inbound parse goldens (UNFY-145).
 *
 * Pins the **genuinely-live inbound codec contract**: the bytes a carrier
 * delivers are decoded by `PduParser(pdu, true).parse()` into a
 * `NotificationInd` (InboundMmsHandler.kt:88) and a `RetrieveConf`
 * (InboundMmsHandler.kt:360). These fixtures are the byte-for-byte input a
 * future Kotlin codec (Phase 5) must decode identically.
 *
 * Provenance: the WAP-230 / OMA-MMS PDUs below are **synthesized spec-valid**
 * blobs (no real carrier capture was available); each byte is annotated with
 * the field it encodes. Real device captures can replace/augment these later
 * (see L7). The committed hex IS the durable fixture — the parser is the
 * oracle that proves it well-formed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PduInboundGoldenTest {

    /** Strip annotations/whitespace from an annotated hex blob → bytes. */
    private fun hex(blob: String): ByteArray {
        val cleaned = blob
            .lineSequence()
            .map { it.substringBefore("//").trim() }
            .joinToString(" ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        return ByteArray(cleaned.size) { cleaned[it].toInt(16).toByte() }
    }

    // M-Notification.ind — the WAP-push the carrier sends to announce an MMS.
    // Headers the live path reads: transactionId, contentLocation, messageSize,
    // expiry (InboundMmsHandler.kt:88-121).
    private val notificationIndPdu = hex(
        """
        8C 82                                  // X-Mms-Message-Type = m-notification-ind
        8D 92                                  // X-Mms-MMS-Version = 1.2  (0x12 | short-int high bit)
        98 54 31 32 33 00                      // X-Mms-Transaction-Id = "T123"
        89 01 81                               // From = value-len(1) insert-address-token
        8A 80                                  // X-Mms-Message-Class = personal
        8E 02 04 D2                            // X-Mms-Message-Size = long-int(len 2) 0x04D2 = 1234
        88 06 80 04 65 53 F1 00                // X-Mms-Expiry = vlen(6) absolute(0x80) long-int(len4)=1700000000
        83 68 74 74 70 3A 2F 2F 6D 6D 73 63    // X-Mms-Content-Location = "http://mmsc
        2E 65 78 61 6D 70 6C 65 2F 61 62 63 00 //   .example/abc"
        """.trimIndent(),
    )

    @Test
    fun notificationInd_parses_liveReadFields() {
        val parsed = PduParser(notificationIndPdu, true).parse()
        assertTrue(
            "expected NotificationInd, got ${parsed?.javaClass?.simpleName}",
            parsed is NotificationInd,
        )
        parsed as NotificationInd
        assertEquals("transactionId", "T123", String(parsed.transactionId!!))
        assertEquals("contentLocation", "http://mmsc.example/abc", String(parsed.contentLocation!!))
        assertEquals("messageSize", 1234L, parsed.messageSize)
        // Every header present in the fixture is asserted, so the fixture stays
        // honest as a dead-code oracle (no exercised-but-unpinned decode paths).
        assertEquals("from (insert-address-token)", "insert-address-token", parsed.from?.string)
        assertEquals("messageClass", "personal", String(parsed.messageClass!!))
        // Absolute expiry (token 0x80) decodes verbatim to its epoch-seconds
        // long-integer — deterministic, unlike a relative token which the
        // parser rewrites to now()+delta (PduParser.java:509-515; see below).
        assertEquals("expiry", 1700000000L, parsed.expiry)
    }

    // Same notification but with a RELATIVE expiry (token 0x81, delta 86400s) —
    // the encoding real carriers actually send. The parser rewrites it to
    // now()+delta (PduParser.java:509-515), so we bound it rather than pin an
    // exact value. This covers the dominant inbound-expiry path that the
    // absolute fixture above deliberately avoids for determinism.
    private val notificationIndRelativeExpiryPdu = hex(
        """
        8C 82                                  // m-notification-ind
        8D 92                                  // MMS-Version 1.2
        98 54 31 32 33 00                      // Transaction-Id "T123"
        8A 80                                  // Message-Class personal
        8E 02 04 D2                            // Message-Size 1234
        88 05 81 03 01 51 80                   // Expiry = vlen(5) relative(0x81) long-int(len3)=86400
        83 63 6C 00                            // Content-Location "cl"
        """.trimIndent(),
    )

    @Test
    fun notificationInd_relativeExpiry_convertsToNowPlusDelta() {
        val deltaSeconds = 86_400L
        val lowerBound = System.currentTimeMillis() / 1000 + deltaSeconds
        val parsed = PduParser(notificationIndRelativeExpiryPdu, true).parse()
        val upperBound = System.currentTimeMillis() / 1000 + deltaSeconds
        assertTrue("expected NotificationInd", parsed is NotificationInd)
        val expiry = (parsed as NotificationInd).expiry
        assertTrue(
            "relative expiry should resolve to now()+$deltaSeconds (got $expiry, " +
                "window [$lowerBound,$upperBound])",
            expiry in lowerBound..upperBound,
        )
    }

    // M-Retrieve.conf — the actual MMS the hub downloads from the MMSC and
    // hands to PduParser (InboundMmsHandler.kt:360) → PduPersister. Content-Type
    // is the terminal header (parser stops there, PduParser.java:824); the body
    // is one text/plain part. Live path reads messageId (InboundMmsPersister:165)
    // and persists the whole PDU.
    private val retrieveConfPdu = hex(
        """
        8C 84                                  // X-Mms-Message-Type = m-retrieve-conf
        8D 92                                  // X-Mms-MMS-Version = 1.2
        98 54 31 32 33 00                      // X-Mms-Transaction-Id = "T123"
        8B 4D 49 44 2D 31 00                   // Message-ID = "MID-1"
        85 04 5A A3 C2 80                      // Date = long-int(len 4) 0x5AA3C280
        89 05 80 6E 75 6D 00                   // From = vlen(5) address-present(0x80) "num"
        96 48 69 00                            // Subject = "Hi"
        84 B3                                  // Content-Type = multipart.related (short-int 0x80|0x33) — TERMINAL
        01                                     //   body: part count = 1
        01                                     //   part0 header-length = 1 (content-type only)
        05                                     //   part0 data-length = 5
        83                                     //   part0 content-type = text/plain (short-int 0x80|0x03)
        48 65 6C 6C 6F                         //   part0 data = "Hello"
        """.trimIndent(),
    )

    @Test
    fun retrieveConf_parses_liveReadFields_andBody() {
        val parsed = PduParser(retrieveConfPdu, true).parse()
        assertTrue(
            "expected RetrieveConf, got ${parsed?.javaClass?.simpleName}",
            parsed is RetrieveConf,
        )
        parsed as RetrieveConf
        assertEquals(
            "content-type",
            "application/vnd.wap.multipart.related",
            String(parsed.contentType!!),
        )
        assertEquals("transactionId", "T123", String(parsed.transactionId!!))
        assertEquals("messageId", "MID-1", String(parsed.messageId!!))
        assertEquals("from", "num", parsed.from?.string)
        assertEquals("subject", "Hi", parsed.subject?.string)

        // `body` is now nullable on the Kotlin MultimediaMessagePdu base; the
        // fixture's RetrieveConf always carries a body, so `!!` preserves the
        // prior platform-type non-null treatment.
        val body = parsed.body!!
        assertEquals("one body part", 1, body.partsNum)
        val part0 = body.getPart(0)
        assertEquals("part0 content-type", "text/plain", String(part0.contentType!!))
        assertEquals("part0 data", "Hello", String(part0.data!!))
    }

    // M-Retrieve.conf with a TOP-LEVEL multipart/alternative body (Content-Type
    // 0x80|0x26). UNFY-155: this is retain-all — every part is kept. The AOSP
    // original had a "take only the first part" branch for ALTERNATIVE that was
    // dead code (the mixed/related/alternative `if` already returns), so the
    // effective behaviour was always retain-all; we keep that and dropped the
    // dead branch (see ADR-0012). This pins the decision so a future change to
    // first-part-only is caught here.
    private val retrieveConfAlternativePdu = hex(
        """
        8C 84                                  // X-Mms-Message-Type = m-retrieve-conf
        8D 92                                  // X-Mms-MMS-Version = 1.2
        98 54 31 32 33 00                      // X-Mms-Transaction-Id = "T123"
        8B 4D 49 44 2D 32 00                   // Message-ID = "MID-2"
        85 04 5A A3 C2 80                      // Date = long-int(len 4) 0x5AA3C280
        89 05 80 6E 75 6D 00                   // From = vlen(5) address-present(0x80) "num"
        84 A6                                  // Content-Type = multipart.alternative (0x80|0x26) — TERMINAL
        02                                     //   body: part count = 2
        01 05 83 48 65 6C 6C 6F                //   part0: hlen=1 dlen=5 ctype=text/plain data="Hello"
        01 05 83 57 6F 72 6C 64                //   part1: hlen=1 dlen=5 ctype=text/plain data="World"
        """.trimIndent(),
    )

    @Test
    fun retrieveConf_multipartAlternative_retainsAllParts() {
        val parsed = PduParser(retrieveConfAlternativePdu, true).parse()
        assertTrue(
            "expected RetrieveConf, got ${parsed?.javaClass?.simpleName}",
            parsed is RetrieveConf,
        )
        parsed as RetrieveConf
        assertEquals(
            "content-type",
            "application/vnd.wap.multipart.alternative",
            String(parsed.contentType!!),
        )
        // UNFY-155 retain-all: BOTH alternative parts survive (not just the first).
        val body = parsed.body!!
        assertEquals("both parts retained", 2, body.partsNum)
        assertEquals("part0 data", "Hello", String(body.getPart(0).data!!))
        assertEquals("part1 data", "World", String(body.getPart(1).data!!))
    }

    // M-Retrieve.conf whose single body part is a NESTED multipart/alternative
    // (Content-Type 0x80|0x26) with a malformed inner body: it declares 1 part
    // then immediately exhausts, so the inner parseParts() returns null. UNFY-155:
    // the original childBody!!.getPart(0) NPE'd on that null; the guard now bails,
    // so parse() returns null (mBody == null path) instead of throwing.
    private val retrieveConfMalformedNestedAlternativePdu = hex(
        """
        8C 84                                  // m-retrieve-conf
        8D 92                                  // MMS-Version 1.2
        98 54 31 32 33 00                      // Transaction-Id "T123"
        8B 4D 49 44 2D 33 00                   // Message-ID "MID-3"
        85 04 5A A3 C2 80                      // Date
        89 05 80 6E 75 6D 00                   // From "num"
        84 B3                                  // Content-Type = multipart.related — TERMINAL
        01                                     //   body: part count = 1
        01 03 A6 01 00 00                      //   part0: hlen=1 dlen=3 ctype=multipart.alternative data=malformed("01 00 00")
        """.trimIndent(),
    )

    @Test
    fun retrieveConf_malformedNestedAlternative_returnsNullNotNpe() {
        // Before UNFY-155 this threw NullPointerException; the guard makes it bail.
        val parsed = PduParser(retrieveConfMalformedNestedAlternativePdu, true).parse()
        assertTrue("malformed nested alternative must parse to null, not crash", parsed == null)
    }
}
