package io.simplezen.simple_sms.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 1 · L5 — pins the Verizon `enabledTransID` MMSC-URL workaround
 * ([resolveVerizonDownloadUrl]), extracted from `InboundMmsHandler.onReceive`
 * (UNFY-148). A carrier-specific correctness quirk: removing it (audit S0 #2 /
 * PR #59) empirically broke Verizon inbound MMS, so the heuristic is pinned so a
 * future edit to it is a visible, tested decision.
 *
 * Plain JUnit: the function is pure Kotlin with no Android dependencies.
 */
class InboundVerizonUrlTest {

    @Test
    fun verizonTemplate_messageIdSuffix_appendsTransactionId() {
        assertEquals(
            "http://63.59.1.1/servlets/mms?message-id=T-ABC",
            resolveVerizonDownloadUrl("http://63.59.1.1/servlets/mms?message-id=", "T-ABC"),
        )
    }

    @Test
    fun bareEqualsSuffix_appendsTransactionId() {
        // The `|| endsWith("=")` catch-all is intentional — any template URL
        // ending in `=` is treated as needing the disambiguating token.
        assertEquals(
            "http://mmsc.example/d?x=T1",
            resolveVerizonDownloadUrl("http://mmsc.example/d?x=", "T1"),
        )
    }

    @Test
    fun completeUrl_leftUnchanged() {
        val url = "http://mmsc.example/download/abc123"
        assertEquals(url, resolveVerizonDownloadUrl(url, "T-ABC"))
    }

    @Test
    fun completeUrlWithFilledQueryValue_leftUnchanged() {
        // A non-template MMSC URL that already carries a value must NOT be
        // touched (T-Mobile / AT&T / Google Fi send complete URLs).
        val url = "http://mmsc.example/mms?message-id=already-set"
        assertEquals(url, resolveVerizonDownloadUrl(url, "T-ABC"))
    }

    @Test
    fun emptyTransactionId_onTemplate_appendsNothing() {
        // Degenerate boundary: a blank token appends nothing. Pinned so a change
        // to the upstream "transactionId is empty" guard is a visible decision.
        assertEquals(
            "http://x/mms?message-id=",
            resolveVerizonDownloadUrl("http://x/mms?message-id=", ""),
        )
    }
}
