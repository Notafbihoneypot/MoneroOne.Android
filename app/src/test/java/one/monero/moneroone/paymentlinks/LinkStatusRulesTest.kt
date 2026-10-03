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

    private fun check(seen: Boolean = true, mined: Boolean = true, confirmations: Long = 0, balance: Long = 0, unlocked: Long = 0) =
        EnvelopeSnapshot(0, seen, mined, confirmations, balance, unlocked)

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
    }

    @Test
    fun `mined funding with no balance left is claimed`() {
        assertEquals(LinkStatus.CLAIMED, LinkStatusRules.status(sent(check = check(confirmations = 12)), before))
        assertEquals(LinkStatus.CLAIMED, LinkStatusRules.status(sent(lostRace = true), before))
        assertFalse(LinkStatusRules.canReclaim(sent(check = check(confirmations = 12))))
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
    fun `reclaim needs an unlocked balance`() {
        assertFalse(LinkStatusRules.canReclaim(sent()))
        assertFalse(LinkStatusRules.canReclaim(sent(check = check(confirmations = 4, balance = 5))))
        assertTrue(LinkStatusRules.canReclaim(sent(check = check(confirmations = 10, balance = 5, unlocked = 5))))
    }

    @Test
    fun `claim sheet states`() {
        assertEquals(ClaimView.NoMoney, LinkStatusRules.claimView(check(seen = false, mined = false)))
        assertEquals(ClaimView.Waiting(0, 20), LinkStatusRules.claimView(check(mined = false)))
        assertEquals(ClaimView.Waiting(1, 18), LinkStatusRules.claimView(check(confirmations = 1, balance = 5)))
        assertEquals(ClaimView.Waiting(9, 2), LinkStatusRules.claimView(check(confirmations = 9, balance = 5)))
        assertEquals(ClaimView.Ready(5), LinkStatusRules.claimView(check(confirmations = 10, balance = 5, unlocked = 5)))
        assertEquals(ClaimView.AlreadyClaimed, LinkStatusRules.claimView(check(confirmations = 11)))
    }
}
