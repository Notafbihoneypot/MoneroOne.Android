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
    fun `no funding seen is not funded`() {
        assertEquals(LinkStatus.NOT_FUNDED, LinkStatusRules.status(sent(SentState.CREATED), before))
        assertEquals(LinkStatus.NOT_FUNDED, LinkStatusRules.status(sent(SentState.NOT_FUNDED), before))
        assertEquals(LinkStatus.NOT_FUNDED, LinkStatusRules.status(sent(check = check(seen = false, mined = false)), before))
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
        val row = { v: SentView -> PaymentLinkActivityRules.fundingStatusLine(v.status) }
        val stale = sent(check = check(confirmations = 1, balance = 5))

        val ready = view(stale, funding = 10)
        assertEquals(LinkStatus.READY, ready.status)
        assertEquals("Ready to claim", row(ready))
        assertEquals("Ready", sentDisplay(ready).label)
        assertTrue(ready.canReclaim)

        val young = view(stale, funding = 9)
        assertEquals(LinkStatus.PENDING, young.status)
        assertEquals("Waiting to be claimed", row(young))
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
        // A check that found no funding stays not funded, and its row waits.
        val unseen = view(sent(check = check(seen = false, mined = false)), funding = 12)
        assertEquals(LinkStatus.NOT_FUNDED, unseen.status)
        assertEquals("Waiting to be claimed", row(unseen))
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
    fun `claim words go only once the claim is mined`() {
        fun received(state: ReceivedState, txid: String?, words: List<String>? = List(25) { "abbey" }, id: String = "0123456789abcdef") =
            ReceivedLink(id, words, 100, 5, expiry, 0, state = state, claimTxid = txid)
        val claimed = received(ReceivedState.CLAIMED, "aa")
        val pending = received(ReceivedState.PENDING, null, id = "1111111111111111")
        val forgotten = received(ReceivedState.CLAIMED, "bb", words = null, id = "2222222222222222")
        val list = listOf(claimed, pending, forgotten)
        assertEquals(emptyList<String>(), LinkStatusRules.claimWordsToForget(list, emptySet()))
        assertEquals(emptyList<String>(), LinkStatusRules.claimWordsToForget(list, setOf("cc")))
        assertEquals(listOf(claimed.id), LinkStatusRules.claimWordsToForget(list, setOf("aa", "bb")))
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
