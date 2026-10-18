package one.monero.moneroone.paymentlinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spec's status table, on recorded state plus an envelope sync. */
class LinkStatusRulesTest {

    private val expiry = 2_000_000_000L
    private val before = expiry - 1
    private val after = expiry + 1

    private fun sent(
        state: SentState = SentState.FUNDED,
        check: EnvelopeSnapshot? = null,
        reclaimTxid: String? = null,
        lostRace: Boolean = false
    ) = SentLink(
        id = "0123456789abcdef", words = List(25) { "abbey" }, height = 100, amount = 500_000_000_000,
        expiry = expiry, createdAtMillis = 0, walletId = "w", envelopeAddress = "4...", feeReserve = 1,
        state = state, reclaimTxid = reclaimTxid, reclaimLostRace = lostRace, lastCheck = check
    )

    private fun check(
        seen: Boolean = true, mined: Boolean = true, confirmations: Long = 0, balance: Long = 0, unlocked: Long = 0,
        spent: Boolean = balance == 0L && confirmations > 0
    ) = EnvelopeSnapshot(0, seen, mined, confirmations, balance, unlocked, spent)

    @Test
    fun `no funding anywhere is not funded`() {
        assertEquals(LinkStatus.NOT_FUNDED, LinkStatusRules.status(sent(SentState.CREATED), before))
        assertEquals(LinkStatus.NOT_FUNDED, LinkStatusRules.status(sent(SentState.NOT_FUNDED), before))
        assertEquals(LinkStatus.NOT_FUNDED, LinkStatusRules.status(sent(SentState.NOT_FUNDED, check(seen = false, mined = false)), before))
    }

    @Test
    fun `a broadcast that went out waits while the check has not seen it`() {
        assertEquals(LinkStatus.PENDING, LinkStatusRules.status(sent(check = check(seen = false, mined = false)), before))
    }

    @Test
    fun `a broadcast before any check reads pending`() {
        assertEquals(LinkStatus.PENDING, LinkStatusRules.status(sent(SentState.FUNDED), before))
    }

    @Test
    fun `a failed broadcast that did go out is found by the check`() {
        val found = sent(SentState.NOT_FUNDED, check(mined = false))
        assertEquals(LinkStatus.PENDING, LinkStatusRules.status(found, before))
    }

    @Test
    fun `funding with fewer than 10 confirmations is pending`() {
        assertEquals(LinkStatus.PENDING, LinkStatusRules.status(sent(check = check(mined = false)), before))
        assertEquals(LinkStatus.PENDING, LinkStatusRules.status(sent(check = check(confirmations = 1, balance = 5)), before))
        assertEquals(LinkStatus.PENDING, LinkStatusRules.status(sent(check = check(confirmations = 9, balance = 5)), before))
    }

    @Test
    fun `an unlocked balance is ready`() {
        assertEquals(LinkStatus.READY, LinkStatusRules.status(sent(check = check(confirmations = 10, balance = 5, unlocked = 5)), before))
        // The kit's height can trail wallet2's unlock by a block: the unlock decides.
        assertEquals(LinkStatus.READY, LinkStatusRules.status(sent(check = check(confirmations = 9, balance = 5, unlocked = 5)), before))
        assertEquals(ClaimView.Ready(5), LinkStatusRules.claimView(check(confirmations = 9, balance = 5, unlocked = 5)))
    }

    @Test
    fun `mined funding with no balance left is claimed`() {
        assertEquals(LinkStatus.CLAIMED, LinkStatusRules.status(sent(check = check(confirmations = 12)), before))
        assertEquals(LinkStatus.CLAIMED, LinkStatusRules.status(sent(lostRace = true), before))
        assertFalse(LinkStatusRules.sentView(sent(check = check(confirmations = 12)), 30, null, before).canReclaim)
    }

    @Test
    fun `a zero balance without a seen spend is never claimed`() {
        // The kit reports Synced before it publishes the balance: a stale zero must not settle the link.
        val stale = sent(check = check(confirmations = 12, spent = false))
        assertEquals(LinkStatus.PENDING, LinkStatusRules.status(stale, before))
        assertFalse(LinkStatusRules.isSettled(stale))
        assertEquals(ClaimView.Waiting(9, 2), LinkStatusRules.claimView(check(confirmations = 12, spent = false)))
    }

    @Test
    fun `our reclaim wins over the chain state`() {
        val reclaimed = sent(check = check(confirmations = 12), reclaimTxid = "ab")
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.status(reclaimed, after))
        assertTrue(LinkStatusRules.isSettled(reclaimed))
    }

    @Test
    fun `a check before our reclaim is mined never reopens the link`() {
        val reclaimed = sent(check = check(confirmations = 12), reclaimTxid = "ab")
        // The scan still sees the money our reclaim spends (it waits in the pool), even as spendable.
        for (early in listOf(
            check(confirmations = 12, balance = 7, unlocked = 7),
            check(confirmations = 12, balance = 7, unlocked = 7).copy(spendTxids = listOf("ab")),
            check(confirmations = 12, balance = 7, unlocked = 7).copy(checkedAt = 3_600_000)
        )) {
            val after = LinkStatusRules.withCheck(reclaimed, early)
            assertEquals(reclaimed, after)
            assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(after))
        }
    }

    @Test
    fun `money our mined reclaim left behind reopens the link`() {
        val reclaimed = sent(check = check(confirmations = 12), reclaimTxid = "ab")
        val mined = check(confirmations = 30, balance = 7, unlocked = 7).copy(spendTxids = listOf("ab"), minedSpendTxids = listOf("ab"))
        val reopened = LinkStatusRules.withCheck(reclaimed, mined)
        assertEquals(LinkStatus.READY, LinkStatusRules.baseStatus(reopened))
        assertFalse(LinkStatusRules.isSettled(reopened))
        // Locked money left behind is not spendable yet: the link stays reclaimed.
        val locked = mined.copy(unlocked = 0)
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(LinkStatusRules.withCheck(reclaimed, locked)))
        // A mined reclaim and nothing left: still reclaimed.
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(LinkStatusRules.withCheck(reclaimed, mined.copy(balance = 0, unlocked = 0))))
        // The next reclaim settles it again.
        val again = reopened.copy(reclaimTxid = "cd")
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(again))
        // A reclaim whose txid never showed proves nothing.
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(LinkStatusRules.withCheck(reclaimed.copy(reclaimTxid = ""), mined)))
    }

    @Test
    fun `a claim that beat our reclaim reads claimed and stays claimed`() {
        val lost = sent(check = check(confirmations = 12, balance = 5, unlocked = 5), lostRace = true)
        assertEquals(LinkStatus.CLAIMED, LinkStatusRules.baseStatus(lost))
        val later = check(confirmations = 40, balance = 3, unlocked = 3).copy(spendTxids = listOf("ee"), minedSpendTxids = listOf("ee"))
        assertEquals(LinkStatus.CLAIMED, LinkStatusRules.baseStatus(LinkStatusRules.withCheck(lost, later)))
    }

    @Test
    fun `a claim that beat our sent reclaim reads claimed, not reclaimed, and stays claimed`() {
        // Our reclaim went out; the recipient's claim reached a block first, and ours never shows.
        val reclaimed = sent(check = check(confirmations = 12, balance = 5, unlocked = 5), reclaimTxid = "ours")
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(reclaimed))
        val beaten = check(confirmations = 30).copy(spendTxids = listOf("claim"), minedSpendTxids = listOf("claim"))
        val claimed = LinkStatusRules.withCheck(reclaimed, beaten)
        assertTrue(claimed.reclaimLostRace)
        assertEquals(LinkStatus.CLAIMED, LinkStatusRules.baseStatus(claimed))
        assertTrue(LinkStatusRules.isSettled(claimed))
        // Final: a later scan (even one that shows money, or our own spend) leaves it claimed, and no check is due.
        val later = check(confirmations = 40, balance = 3, unlocked = 3).copy(spendTxids = listOf("ours"), minedSpendTxids = listOf("ours"))
        assertEquals(LinkStatus.CLAIMED, LinkStatusRules.baseStatus(LinkStatusRules.withCheck(claimed, later)))
        assertFalse(LinkStatusRules.checkDue(claimed, 0))
        // Our reclaim seen, in the pool or mined: no race was lost.
        val ours = check(confirmations = 30).copy(spendTxids = listOf("claim", "ours"))
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(LinkStatusRules.withCheck(reclaimed, ours)))
        // A reclaim whose txid never showed cannot tell.
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(LinkStatusRules.withCheck(reclaimed.copy(reclaimTxid = ""), beaten)))
    }

    @Test
    fun `a spend from before our reclaim is not a claim that beat it`() {
        // Our first reclaim "ab" is mined, money came later, and the second reclaim "cd" waits in the pool.
        val mined = check(confirmations = 30, balance = 7, unlocked = 7).copy(spendTxids = listOf("ab"), minedSpendTxids = listOf("ab"))
        val reopened = LinkStatusRules.withCheck(sent(check = check(confirmations = 12), reclaimTxid = "ab"), mined)
        assertEquals(LinkStatus.READY, LinkStatusRules.baseStatus(reopened))
        val again = reopened.copy(reclaimTxid = "cd", spendsBeforeReclaim = LinkStatusRules.spendsBeforeReclaim(reopened, listOf("cd")))
        assertEquals(listOf("ab"), again.spendsBeforeReclaim)
        // A fresh scan that does not see "cd" in the pool yet: still reclaimed.
        val after = LinkStatusRules.withCheck(again, mined)
        assertFalse(after.reclaimLostRace)
        assertEquals(LinkStatus.RECLAIMED, LinkStatusRules.baseStatus(after))
        // A new spend that is not ours beat it.
        val beaten = mined.copy(balance = 0, unlocked = 0, spendTxids = listOf("ab", "ee"), minedSpendTxids = listOf("ab", "ee"))
        assertEquals(LinkStatus.CLAIMED, LinkStatusRules.baseStatus(LinkStatusRules.withCheck(again, beaten)))
    }

    @Test
    fun `a link claimed elsewhere reopens only once that claim is mined and money remains`() {
        val claimed = sent(check = check(confirmations = 12).copy(spendTxids = listOf("ee")))
        assertEquals(LinkStatus.CLAIMED, LinkStatusRules.baseStatus(claimed))
        // A fresh scan that misses the claim in the pool sees the money again: no change.
        val early = check(confirmations = 12, balance = 5, unlocked = 5)
        assertEquals(claimed, LinkStatusRules.withCheck(claimed, early))
        val leftover = check(confirmations = 40, balance = 3, unlocked = 3).copy(spendTxids = listOf("ee"), minedSpendTxids = listOf("ee"))
        assertEquals(LinkStatus.READY, LinkStatusRules.baseStatus(LinkStatusRules.withCheck(claimed, leftover)))
    }

    @Test
    fun `pending and ready expire, settled links do not`() {
        assertEquals(LinkStatus.EXPIRED, LinkStatusRules.status(sent(check = check(confirmations = 3, balance = 5)), after))
        assertEquals(LinkStatus.EXPIRED, LinkStatusRules.status(sent(check = check(confirmations = 10, balance = 5, unlocked = 5)), after))
        assertEquals(LinkStatus.CLAIMED, LinkStatusRules.status(sent(check = check(confirmations = 12)), after))
        assertEquals(LinkStatus.NOT_FUNDED, LinkStatusRules.status(sent(SentState.NOT_FUNDED), after))
        assertEquals(LinkStatus.READY, LinkStatusRules.status(sent(check = check(confirmations = 10, balance = 5, unlocked = 5)), expiry))
    }

    @Test
    fun `reclaim needs an unlocked balance or 10 confirmations of the funding`() {
        fun view(link: SentLink, funding: Long? = null, daemonHeight: Long? = null, now: Long = before) =
            LinkStatusRules.sentView(link, funding, daemonHeight, now)
        assertFalse(view(sent()).canReclaim)
        assertFalse(view(sent(check = check(confirmations = 4, balance = 5))).canReclaim)
        assertTrue(view(sent(check = check(confirmations = 10, balance = 5, unlocked = 5))).canReclaim)
    }

    /**
     * The founder's bug (iOS 3319a9e): the funding had 10 confirmations, the record still said pending (no
     * check had seen the unlock), and the row said "Ready to claim" while the detail said "Pending 10/10"
     * with Reclaim off. The row, the detail and Reclaim now read one status.
     */
    @Test
    fun `a sent link is ready at the unlock depth whatever the last check`() {
        fun view(link: SentLink, funding: Long?, daemonHeight: Long? = null, now: Long = before) =
            LinkStatusRules.sentView(link, funding, daemonHeight, now)
        val row = { v: SentView -> PaymentLinkActivityRules.stateLabel(PaymentLinkActivityRules.fundingRowState(v.status)) }
        val stale = sent(check = check(confirmations = 1, balance = 5))

        val ready = view(stale, funding = 10)
        assertEquals(LinkStatus.READY, ready.status)
        assertEquals("Ready", row(ready))
        assertEquals("Ready", sentDisplay(ready).label)
        assertTrue(ready.canReclaim)

        val young = view(stale, funding = 9)
        assertEquals(LinkStatus.PENDING, young.status)
        assertEquals("Waiting", row(young))
        assertEquals("Pending 9/10", sentDisplay(young).label)
        assertFalse(young.canReclaim)

        // Another wallet funded it: the funding height of the last check and the daemon height give the count.
        val elsewhere = sent(check = check(confirmations = 1, balance = 5).copy(fundingHeight = 1_000))
        assertTrue(view(elsewhere, funding = null, daemonHeight = 1_010).canReclaim)
        assertFalse(view(elsewhere, funding = null, daemonHeight = 1_009).canReclaim)

        // Past its expiry it reads Expired, and Reclaim is on.
        val expired = view(stale, funding = 12, now = after)
        assertEquals(LinkStatus.EXPIRED, expired.status)
        assertEquals("Expired", row(expired))
        assertTrue(expired.canReclaim)

        // A check that saw the claim, or our reclaim, wins over the count.
        val claimed = view(sent(check = check(confirmations = 12)), funding = 30)
        assertEquals(LinkStatus.CLAIMED, claimed.status)
        assertFalse(claimed.canReclaim)
        assertFalse(view(sent(check = check(confirmations = 12), reclaimTxid = "ab"), funding = 30).canReclaim)
        assertFalse(view(sent(lostRace = true), funding = 30).canReclaim)

        // Not checked yet, with its funding in the open wallet: pending, then ready at 10.
        assertEquals(LinkStatus.PENDING, view(sent(SentState.NOT_FUNDED), funding = 2).status)
        assertEquals(LinkStatus.READY, view(sent(SentState.CREATED), funding = 10).status)
        // A check that did not see a funding the open wallet holds (iOS Link 10): never Not funded. The
        // wallet's count wins, so it waits, then is ready at 10 (Reclaim checks the envelope again first).
        val unseen = view(sent(SentState.NOT_FUNDED, check(seen = false, mined = false)), funding = 4)
        assertEquals(LinkStatus.PENDING, unseen.status)
        assertEquals("Waiting", row(unseen))
        assertEquals("Pending 4/10", sentDisplay(unseen).label)
        val deep = view(sent(SentState.NOT_FUNDED, check(seen = false, mined = false)), funding = 12)
        assertEquals(LinkStatus.READY, deep.status)
        assertTrue(deep.canReclaim)
    }

    @Test
    fun `claim sheet states`() {
        assertEquals(ClaimView.NoMoney, LinkStatusRules.claimView(check(seen = false, mined = false)))
        assertEquals(ClaimView.Waiting(0, 20), LinkStatusRules.claimView(check(mined = false)))
        assertEquals(ClaimView.Waiting(1, 18), LinkStatusRules.claimView(check(confirmations = 1, balance = 5)))
        assertEquals(ClaimView.Waiting(9, 2), LinkStatusRules.claimView(check(confirmations = 9, balance = 5)))
        assertEquals(ClaimView.Ready(5), LinkStatusRules.claimView(check(confirmations = 10, balance = 5, unlocked = 5)))
        assertEquals(ClaimView.AlreadyClaimed, LinkStatusRules.claimView(check(confirmations = 11)))
        assertEquals(ClaimView.Waiting(9, 2), LinkStatusRules.claimView(check(confirmations = 10, balance = 5)))
    }

    @Test
    fun `a check before our claim is mined keeps the link claimed`() {
        // Reviewer High (2026-10-17): any balance turned Claimed into Ready, and a second claim failed as a double spend.
        val claimed = ReceivedLink("0123456789abcdef", List(25) { "abbey" }, 100, 5, expiry, 0,
            state = ReceivedState.CLAIMED, claimTxid = "aa", claimedAmount = 5)
        for (early in listOf(
            check(confirmations = 11, balance = 7, unlocked = 7),
            check(confirmations = 11, balance = 7, unlocked = 0),
            check(confirmations = 11, balance = 7, unlocked = 7).copy(spendTxids = listOf("aa"))
        )) {
            val after = claimed.noting(early)
            assertEquals(claimed, after)
            assertTrue(after.isSettled)
            assertTrue(after.claimedHere)
        }
    }

    @Test
    fun `money our mined claim left behind reopens the link and keeps its secret`() {
        val claimed = ReceivedLink("0123456789abcdef", List(25) { "abbey" }, 100, 5, expiry, 0,
            state = ReceivedState.CLAIMED, claimTxid = "aa", claimedAmount = 5)
        val mined = check(confirmations = 30, balance = 7, unlocked = 7).copy(spendTxids = listOf("aa"), minedSpendTxids = listOf("aa"))
        val reopened = claimed.noting(mined)
        assertEquals(ReceivedState.PENDING, reopened.state)
        assertEquals(claimed.words, reopened.words)
        assertFalse(reopened.isSettled)
        assertEquals(ClaimView.Ready(7), LinkStatusRules.claimView(reopened.lastCheck!!))
        // Locked money left behind waits for a later check.
        assertEquals(claimed, claimed.noting(mined.copy(unlocked = 0)))
        // A claim that lost to another claim (no txid, no amount) is not ours and never reopens.
        val beaten = claimed.copy(claimTxid = null, claimedAmount = null)
        assertFalse(beaten.claimedHere)
        assertEquals(beaten, beaten.noting(mined))
    }

    @Test
    fun `a lost sweep is ours only when its hash reached the open wallet`() {
        val spent = check(confirmations = 12).copy(spendTxids = listOf("aa"))
        val started = sent(check = spent).copy(reclaimStartedAt = 1, reclaimWallet = "w1")
        val notStarted = sent(check = spent).copy(id = "1111111111111111")
        val done = sent(check = spent, reclaimTxid = "aa").copy(id = "2222222222222222", reclaimStartedAt = 1, reclaimWallet = "w1")
        val list = listOf(started, notStarted, done)
        assertEquals(emptyMap<String, String>(), LinkStatusRules.lostReclaims(list, "w1", setOf("bb")))
        assertEquals(mapOf(started.id to "aa"), LinkStatusRules.lostReclaims(list, "w1", setOf("aa")))
        // The same spend in another wallet on this phone is a claim that won, not our reclaim.
        assertEquals(emptyMap<String, String>(), LinkStatusRules.lostReclaims(list, "w2", setOf("aa")))
        assertEquals(emptyMap<String, String>(), LinkStatusRules.lostReclaims(list, null, setOf("aa")))

        val claim = ReceivedLink("3333333333333333", List(25) { "abbey" }, 100, 5, expiry, 0, lastCheck = spent, claimStartedAt = 1, claimWallet = "w2")
        val untouched = claim.copy(id = "4444444444444444", claimStartedAt = null)
        assertEquals(emptyMap<String, String>(), LinkStatusRules.lostClaims(listOf(claim, untouched), "w2", emptySet()))
        assertEquals(emptyMap<String, String>(), LinkStatusRules.lostClaims(listOf(claim, untouched), "w1", setOf("aa")))
        assertEquals(mapOf(claim.id to "aa"), LinkStatusRules.lostClaims(listOf(claim, untouched), "w2", setOf("aa")))
    }
}
