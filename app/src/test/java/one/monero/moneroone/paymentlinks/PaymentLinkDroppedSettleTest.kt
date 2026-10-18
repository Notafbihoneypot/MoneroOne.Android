package one.monero.moneroone.paymentlinks

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Money that a dropped settle never moved, and fundings the node took without a txid in this
 * wallet's history, come back on their own (requirements 2026-10-17).
 */
class PaymentLinkDroppedSettleTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val sentAt = 1_000_000L
    private val grace = LinkStatusRules.SETTLE_GRACE_MS

    private fun sent(reclaimTxid: String? = null, state: SentState = SentState.FUNDED, check: EnvelopeSnapshot? = null) = SentLink(
        id = "0123456789abcdef", words = List(25) { "abbey" }, height = 100, amount = 500_000_000_000,
        expiry = 2_000_000_000L, createdAtMillis = 0, walletId = "w", envelopeAddress = "4...", feeReserve = 1,
        state = state, reclaimTxid = reclaimTxid, reclaimStartedAt = reclaimTxid?.let { sentAt - 5_000 },
        reclaimSentAt = reclaimTxid?.let { sentAt }, lastCheck = check
    )

    private fun claimed() = ReceivedLink(
        "0123456789abcdef", List(25) { "abbey" }, 100, 5, 2_000_000_000L, 0, state = ReceivedState.CLAIMED,
        claimTxid = "aa", claimedAmount = 5, claimStartedAt = sentAt - 5_000, claimSentAt = sentAt
    )

    /** A fresh restore of the envelope: the funding in a block, nothing spent as far as the chain shows. */
    private fun scan(
        at: Long,
        balance: Long = 7,
        unlocked: Long = balance,
        mined: List<String> = emptyList(),
        failed: List<String> = emptyList(),
        spends: List<String> = mined
    ) = EnvelopeSnapshot(
            checkedAt = at, fundingSeen = true, fundingMined = true, confirmations = 40, balance = balance, unlocked = unlocked,
            spendSeen = spends.isNotEmpty(), spendTxids = spends, minedSpendTxids = mined, failedSpendTxids = failed
        )

    private val funded = scan(at = sentAt - 60_000)
    private val none = WalletHistory()
    private fun pending(vararg txids: String) = WalletHistory(pending = txids.toSet())

    @Test
    fun `a settle in the pool or within its grace is not dropped`() {
        val txids = listOf("ab")
        val nothing = emptyList<String>()
        assertEquals(nothing, LinkStatusRules.droppedSettles(txids, sentAt, scan(sentAt + grace), none))
        assertEquals(nothing, LinkStatusRules.droppedSettles(txids, sentAt, scan(sentAt + grace + 1), pending("ab")))
        assertEquals(nothing, LinkStatusRules.droppedSettles(txids, sentAt, scan(sentAt + 10 * grace, mined = txids), none))
        // Mined in the open wallet's history, though the envelope scan has not reached its block.
        assertEquals(nothing, LinkStatusRules.droppedSettles(txids, sentAt, scan(sentAt + 10 * grace), WalletHistory(confirmed = setOf("ab"))))
        // The envelope's own cache still lists it waiting in the pool.
        assertEquals(nothing, LinkStatusRules.droppedSettles(txids, sentAt, scan(sentAt + 10 * grace, spends = txids), none))
        // The open wallet shows it in the pool: that outranks the envelope's failed mark.
        assertEquals(nothing, LinkStatusRules.droppedSettles(txids, sentAt, scan(sentAt + 1, failed = txids), pending("ab")))
        // No time to measure a grace from (a claim made elsewhere): only a failed mark counts.
        assertEquals(nothing, LinkStatusRules.droppedSettles(txids, null, scan(sentAt + 10 * grace), none))
    }

    @Test
    fun `a settle past its grace and seen nowhere, or marked failed, is dropped`() {
        val txids = listOf("ab")
        assertEquals(txids, LinkStatusRules.droppedSettles(txids, sentAt, scan(sentAt + grace + 1), none))
        assertEquals(txids, LinkStatusRules.droppedSettles(txids, sentAt, scan(sentAt + 1, failed = txids), none))
        assertEquals(txids, LinkStatusRules.droppedSettles(txids, null, scan(sentAt + 1, failed = txids), none))
        assertEquals(txids, LinkStatusRules.droppedSettles(txids, sentAt, scan(sentAt + 1), WalletHistory(failed = setOf("ab"))))
    }

    @Test
    fun `a reclaim evicted with no replacement can be reclaimed again, also after a restart`() {
        val reclaimed = sent(reclaimTxid = "ab", check = funded)
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(reclaimed))
        // Within the grace a fresh scan still sees the money (the reclaim waits in the pool): no change.
        assertEquals(reclaimed, LinkStatusRules.withCheck(reclaimed, scan(sentAt + grace / 2)))
        // Past the grace, while the open wallet still shows it in the pool: no change.
        assertEquals(reclaimed, LinkStatusRules.withCheck(reclaimed, scan(sentAt + 2 * grace), pending("ab")))
        // Evicted: in no block and in no pool past its grace. The money is reclaimable again.
        val reopened = LinkStatusRules.withCheck(reclaimed, scan(sentAt + 2 * grace))
        assertEquals(LinkStatus.READY, LinkStatusRules.baseStatus(reopened))
        assertTrue(LinkStatusRules.sentView(reopened, null, null, 0).canReclaim)
        // The app restarts: the record as stored reads the same.
        val restored = json.decodeFromString(SentLink.serializer(), json.encodeToString(SentLink.serializer(), reopened))
        assertEquals(LinkStatus.READY, LinkStatusRules.baseStatus(restored))
        // The next checks keep it open; a new reclaim settles it again.
        val later = LinkStatusRules.withCheck(restored, scan(sentAt + 3 * grace))
        assertEquals(LinkStatus.READY, LinkStatusRules.baseStatus(later))
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(later.copy(reclaimTxid = "cd", reclaimSentAt = sentAt + 3 * grace)))
    }

    @Test
    fun `wallet2's failed mark reopens a reclaim at once`() {
        val reclaimed = sent(reclaimTxid = "ab", check = funded)
        val reopened = LinkStatusRules.withCheck(reclaimed, scan(sentAt + 60_000, failed = listOf("ab")))
        assertEquals(LinkStatus.READY, LinkStatusRules.baseStatus(reopened))
    }

    @Test
    fun `a reclaim read as dropped that is mined after all reads reclaimed`() {
        val reopened = LinkStatusRules.withCheck(sent(reclaimTxid = "ab", check = funded), scan(sentAt + 2 * grace))
        val mined = LinkStatusRules.withCheck(reopened, scan(sentAt + 3 * grace, balance = 0, mined = listOf("ab")))
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(mined))
        assertFalse(LinkStatusRules.settleUnconfirmed(mined))
    }

    @Test
    fun `a claim evicted with no replacement is ready to claim again, also after a restart`() {
        val claimed = claimed()
        assertEquals(claimed, claimed.noting(scan(sentAt + grace / 2)))
        assertEquals(claimed, claimed.noting(scan(sentAt + 2 * grace), pending("aa")))
        val reopened = claimed.noting(scan(sentAt + 2 * grace))
        assertEquals(ReceivedState.PENDING, reopened.state)
        assertFalse(reopened.isSettled)
        assertEquals(claimed.words, reopened.words)
        assertEquals(ClaimView.Ready(7), LinkStatusRules.claimView(reopened.lastCheck!!))
        val restored = json.decodeFromString(ReceivedLink.serializer(), json.encodeToString(ReceivedLink.serializer(), reopened))
        assertEquals(ReceivedState.PENDING, restored.state)
        assertEquals(ClaimView.Ready(7), LinkStatusRules.claimView(restored.noting(scan(sentAt + 3 * grace)).lastCheck!!))
    }

    @Test
    fun `a claim read as dropped that is mined after all reads claimed here again`() {
        val reopened = claimed().noting(scan(sentAt + 2 * grace))
        val mined = reopened.noting(scan(sentAt + 3 * grace, balance = 0, mined = listOf("aa")))
        assertEquals(ReceivedState.CLAIMED, mined.state)
        assertTrue(mined.claimedHere)
        assertFalse(mined.settleUnconfirmed)
    }

    @Test
    fun `settled links are checked until their settle is in a block`() {
        val reclaimed = sent(reclaimTxid = "ab", check = funded)
        assertTrue(LinkStatusRules.settleUnconfirmed(reclaimed))
        assertTrue(LinkStatusRules.checkDue(reclaimed, sentAt))
        // While it waits in the open wallet's pool, a check could change nothing: none is due.
        assertFalse(LinkStatusRules.checkDue(reclaimed, sentAt, pending("ab")))
        assertTrue(LinkStatusRules.checkDue(reclaimed, sentAt, WalletHistory(confirmed = setOf("ab"))))
        // In a block, the envelope empty: final, checked no more.
        val final = LinkStatusRules.withCheck(reclaimed, scan(sentAt + 60_000, balance = 0, mined = listOf("ab")))
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(final))
        assertFalse(LinkStatusRules.settleUnconfirmed(final))
        assertFalse(LinkStatusRules.checkDue(final, sentAt))
        // A reclaim whose txid never showed cannot be followed.
        assertFalse(LinkStatusRules.checkDue(sent(reclaimTxid = "", check = funded), sentAt))
        val claimed = claimed()
        assertTrue(claimed.settleUnconfirmed)
        assertTrue(claimed.settleDue(none))
        assertFalse(claimed.settleDue(pending("aa")))
        assertFalse(claimed.noting(scan(sentAt + 60_000, balance = 0, mined = listOf("aa"))).settleUnconfirmed)
    }

    @Test
    fun `a reclaim the envelope's cache lists as waiting reopens once wallet2 marks it failed`() {
        // The kept cache lists the reclaim in the pool past its grace and hides its money: not dropped.
        val reclaimed = sent(reclaimTxid = "ab", check = funded)
        val cached = scan(sentAt + 2 * grace, balance = 0, spends = listOf("ab"), mined = emptyList())
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(LinkStatusRules.withCheck(reclaimed, cached)))
        // wallet2 found it gone from the pool: failed, its money spendable again.
        val failed = scan(sentAt + 3 * grace, failed = listOf("ab"))
        assertEquals(LinkStatus.READY, LinkStatusRules.baseStatus(LinkStatusRules.withCheck(reclaimed, failed)))
    }

    @Test
    fun `a hidden attempt that reached the broadcast is scanned until a day of blocks is covered`() {
        val attempt = sent(state = SentState.NOT_FUNDED)
        assertTrue(LinkStatusRules.isHidden(attempt))
        assertTrue(LinkStatusRules.hiddenScanDue(attempt, sentAt))
        val top = 100 + EnvelopeFactory.HEIGHT_MARGIN + LinkStatusRules.HIDDEN_SCAN_BLOCKS
        val empty = EnvelopeSnapshot(checkedAt = 1, fundingSeen = false, fundingMined = false, confirmations = 0, balance = 0, unlocked = 0)
        // An early empty scan does not stop the scans.
        val early = LinkStatusRules.withCheck(attempt, empty.copy(scannedHeight = 130))
        assertTrue(LinkStatusRules.isHidden(early))
        assertTrue(LinkStatusRules.hiddenScanDue(early, sentAt))
        assertTrue(LinkStatusRules.checkDue(early, sentAt))
        assertTrue(LinkStatusRules.hiddenScanDue(LinkStatusRules.withCheck(attempt, empty.copy(scannedHeight = top - 1)), sentAt))
        // A day of blocks past the attempt with no funding: the scans stop. The secret stays.
        val done = LinkStatusRules.withCheck(attempt, empty.copy(scannedHeight = top))
        assertFalse(LinkStatusRules.hiddenScanDue(done, sentAt))
        assertFalse(LinkStatusRules.checkDue(done, sentAt))
        assertEquals(attempt.words, done.words)
    }

    @Test
    fun `a created attempt waits for its send, and a known funding txid needs no scan`() {
        val created = sent(state = SentState.CREATED)
        assertTrue(LinkStatusRules.isHidden(created))
        // The send may still run: no scan for the first 10 minutes.
        assertFalse(LinkStatusRules.hiddenScanDue(created, LinkStatusRules.HIDDEN_SCAN_WAIT_MS))
        assertTrue(LinkStatusRules.hiddenScanDue(created, LinkStatusRules.HIDDEN_SCAN_WAIT_MS + 1))
        // A funding the wallet marks failed: its history follows that txid, no scan.
        val failed = sent(state = SentState.FUNDED).copy(fundingTxid = "f", fundingFailed = true)
        assertTrue(LinkStatusRules.isHidden(failed))
        assertFalse(LinkStatusRules.hiddenScanDue(failed, sentAt))
    }

    @Test
    fun `an attempt that failed before any broadcast needs no scan`() {
        val attempt = sent(state = SentState.NOT_FUNDED).copy(failedBeforeBroadcast = true)
        assertTrue(LinkStatusRules.isHidden(attempt))
        assertFalse(LinkStatusRules.hiddenScanDue(attempt, sentAt))
        assertFalse(LinkStatusRules.checkDue(attempt, sentAt))
    }

    @Test
    fun `a funding the node took with no local txid reappears as waiting once a scan sees it`() {
        // The node took the funding, its answer was lost, and this wallet holds no funding txid.
        val attempt = sent(state = SentState.NOT_FUNDED)
        val empty = EnvelopeSnapshot(
            checkedAt = 1, fundingSeen = false, fundingMined = false, confirmations = 0, balance = 0, unlocked = 0, scannedHeight = 120
        )
        val stillHidden = LinkStatusRules.withCheck(attempt, empty)
        assertTrue(LinkStatusRules.isHidden(stillHidden))
        assertTrue(LinkStatusRules.checkDue(stillHidden, sentAt))
        // Mined later: the next cycle's scan finds it.
        val mined = EnvelopeSnapshot(
            checkedAt = 2, fundingSeen = true, fundingMined = true, confirmations = 2, balance = 5, unlocked = 0,
            fundingTxids = listOf("f1"), scannedHeight = 140
        )
        val back = LinkStatusRules.withCheck(stillHidden, mined)
        assertFalse(LinkStatusRules.isHidden(back))
        assertEquals(LinkStatus.PENDING, LinkStatusRules.baseStatus(back))
        assertEquals("f1", back.fundingTxid)
        assertEquals(SentState.FUNDED, back.state)
    }
}
