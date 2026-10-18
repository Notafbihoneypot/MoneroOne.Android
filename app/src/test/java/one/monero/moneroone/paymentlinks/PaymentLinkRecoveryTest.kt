package one.monero.moneroone.paymentlinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope scan's own recovery (iOS c58ba1a): an envelope saved before its scan from the restore
 * height began is restored and scanned from there; old records are rewritten; a check adopts the funding txid the
 * envelope saw; a received link with no money leaves Open after its 30 days.
 */
class PaymentLinkRecoveryTest {

    private val secret = "000102030405060708090a0b0c0d0e0f"
    private val day = 24 * 60 * 60L
    private val receivedAt = 1_800_000_000L

    @Test
    fun `an envelope with no scan begun from its height is restored before it opens`() {
        // No files yet: a plain restore.
        assertFalse(EnvelopeScanRules.rebuildBeforeOpen(hasWallet = false, scannedFrom = null, restoreHeight = 1_000))
        // Files saved before a scan from this height began (an app kill, a node drop): restore again.
        assertTrue(EnvelopeScanRules.rebuildBeforeOpen(hasWallet = true, scannedFrom = null, restoreHeight = 1_000))
        // A scan from this height or lower began: the cache stays and resumes it (a long scan cut short too).
        assertFalse(EnvelopeScanRules.rebuildBeforeOpen(hasWallet = true, scannedFrom = 1_000, restoreHeight = 1_000))
        assertFalse(EnvelopeScanRules.rebuildBeforeOpen(hasWallet = true, scannedFrom = 985, restoreHeight = 1_000))
        // A rescan from lower than the cache's start: restore from there.
        assertTrue(EnvelopeScanRules.rebuildBeforeOpen(hasWallet = true, scannedFrom = 1_000, restoreHeight = 985))
    }

    @Test
    fun `a rescan starts at the restore height or under the funding block`() {
        assertEquals(1_000L, LinkStatusRules.scanFloor(1_000, null))
        assertEquals(1_000L, LinkStatusRules.scanFloor(1_000, 1_500))
        assertEquals(985L, LinkStatusRules.scanFloor(1_000, 995))
        assertEquals(0L, LinkStatusRules.scanFloor(1_000, 3))
    }

    private fun sent(state: SentState, txid: String? = null, failed: Boolean = false) = SentLink(
        id = "0123456789abcdef", height = 100, amount = 1, expiry = receivedAt + 30 * day, createdAtMillis = 1,
        walletId = "w", envelopeAddress = "4env", feeReserve = 1, state = state, fundingTxid = txid,
        fundingFailed = failed, secret = secret, number = 7
    )

    @Test
    fun `old records with a known funding are rewritten as funded`() {
        assertEquals(SentState.FUNDED, LinkStatusRules.reconciled(sent(SentState.NOT_FUNDED, "f")).state)
        assertEquals(SentState.FUNDED, LinkStatusRules.reconciled(sent(SentState.CREATED, "f")).state)
        assertEquals(SentState.NOT_FUNDED, LinkStatusRules.reconciled(sent(SentState.NOT_FUNDED)).state)
        assertEquals(SentState.NOT_FUNDED, LinkStatusRules.reconciled(sent(SentState.NOT_FUNDED, "f", failed = true)).state)
        val funded = sent(SentState.FUNDED, "f")
        assertTrue(LinkStatusRules.reconciled(funded) === funded)
    }

    @Test
    fun `a check takes the funding txid the envelope saw when the record has none`() {
        val seen = EnvelopeSnapshot(1, true, true, 2, 5, 0, fundingTxids = listOf("mined", "pool"))
        val adopted = LinkStatusRules.withCheck(sent(SentState.NOT_FUNDED), seen)
        assertEquals("mined", adopted.fundingTxid)
        assertEquals(SentState.FUNDED, adopted.state)
        assertEquals(seen, adopted.lastCheck)
        assertEquals("f", LinkStatusRules.withCheck(sent(SentState.FUNDED, "f"), seen).fundingTxid)
        val replaced = LinkStatusRules.withCheck(sent(SentState.FUNDED, "dropped", failed = true), seen)
        assertEquals("mined", replaced.fundingTxid)
        assertFalse(replaced.fundingFailed)
        assertNull(LinkStatusRules.withCheck(sent(SentState.NOT_FUNDED), EnvelopeSnapshot(1, false, false, 0, 0, 0)).fundingTxid)
    }

    private fun received(check: EnvelopeSnapshot? = null, expiry: Long = 0, state: ReceivedState = ReceivedState.PENDING) = ReceivedLink(
        id = "fedcba9876543210", words = null, height = 100, amount = 0, expiry = expiry,
        receivedAtMillis = receivedAt * 1000, state = state, lastCheck = check, secret = secret, number = 8
    )

    private val noMoney = EnvelopeSnapshot(1, false, false, 0, 0, 0)
    private val waiting = EnvelopeSnapshot(1, true, true, 3, 1_000, 0, fundingHeight = 90)

    @Test
    fun `a received link with no money stays open for its 30 days, then is Expired in History`() {
        val during = receivedAt + 29 * day
        val after = receivedAt + 31 * day
        for (check in listOf(null, noMoney)) {
            val link = received(check)
            assertFalse(LinkStatusRules.receivedExpired(link, during))
            assertTrue(LinkStatusRules.receivedExpired(link, after))
            // During its 30 days: Open, on the balance card's line.
            assertEquals(listOf(link), OpenLinkRules.openLinks(emptyList(), listOf(link), "w", during).received)
            assertEquals(1, LinkListRules.sections(emptyList(), PaymentLinkIndex(emptyList(), listOf(link)), listOf(link), during).openCount)
            // After: History, as Expired, off the line. The record keeps its secret.
            assertTrue(OpenLinkRules.openLinks(emptyList(), listOf(link), "w", after).received.isEmpty())
            val sections = LinkListRules.sections(emptyList(), PaymentLinkIndex(emptyList(), listOf(link)), listOf(link), after)
            assertEquals(0, sections.openCount)
            assertEquals(listOf(link.id), sections.history.map { it.linkId })
            assertEquals(1, sections.linkCount)
            val state = PaymentLinkActivityRules.pendingState(link, null, after * 1000)
            assertEquals(PendingLinkState.Expired, state)
            assertEquals("Expired", PaymentLinkActivityRules.stateLabel(PaymentLinkActivityRules.pendingRowState(state)))
            assertEquals("Expired", receivedDisplay(link, null, after).label)
            assertEquals(secret, link.secret)
        }
    }

    @Test
    fun `money in a received link keeps it open after 30 days`() {
        val after = receivedAt + 31 * day
        val funded = received(waiting)
        assertFalse(LinkStatusRules.receivedExpired(funded, after))
        assertEquals(listOf(funded), OpenLinkRules.openLinks(emptyList(), listOf(funded), "w", after).received)
        // Money that shows up after the 30 days brings an expired link back.
        assertTrue(LinkStatusRules.receivedExpired(received(noMoney), after))
        assertFalse(LinkStatusRules.receivedExpired(received(noMoney).copy(lastCheck = waiting), after))
        // A claimed link is never Expired; a v1 link's own expiry counts.
        assertFalse(LinkStatusRules.receivedExpired(received(state = ReceivedState.CLAIMED), after))
        assertTrue(LinkStatusRules.receivedExpired(received(expiry = receivedAt + day), receivedAt + 2 * day))
    }
}
