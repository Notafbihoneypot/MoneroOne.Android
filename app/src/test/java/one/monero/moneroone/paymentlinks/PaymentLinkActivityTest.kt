package one.monero.moneroone.paymentlinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Payment links in Activity and Send: the same cases as iOS `PaymentLinkActivityTests`. */
class PaymentLinkActivityTest {

    private val secret = "000102030405060708090a0b0c0d0e0f"

    private fun sent(
        id: String = "a",
        state: SentState = SentState.FUNDED,
        check: EnvelopeSnapshot? = null,
        fundingTxid: String? = "f1",
        reclaimTxid: String? = null
    ) = SentLink(
        id = id, height = 100, amount = 500_000_000_000, expiry = 1_900_000_000, createdAtMillis = 1,
        walletId = "w", envelopeAddress = "4...", feeReserve = 1, state = state, fundingTxid = fundingTxid,
        reclaimTxid = reclaimTxid, lastCheck = check, secret = secret
    )

    private fun received(
        id: String = "b",
        check: EnvelopeSnapshot? = null,
        state: ReceivedState = ReceivedState.PENDING,
        claimTxid: String? = null,
        withSecret: Boolean = true
    ) = ReceivedLink(
        id = id, words = null, height = 100, amount = 0, expiry = 0, receivedAtMillis = 1_799_999_000_000, state = state,
        claimTxid = claimTxid, lastCheck = check, secret = if (withSecret) secret else null
    )

    private fun check(
        seen: Boolean = true,
        mined: Boolean = true,
        confirmations: Long = 0,
        balance: Long = 1_000,
        unlocked: Long = 0,
        spent: Boolean = false,
        fundingHeight: Long? = null,
        checkedAt: Long = 1_800_000_000_000
    ) = EnvelopeSnapshot(checkedAt, seen, mined, confirmations, balance, unlocked, spent, emptyList(), fundingHeight)

    private val noMoney = check(seen = false, mined = false, balance = 0)
    private val waiting = check(confirmations = 3)
    private val ready = check(confirmations = 12, unlocked = 1_000)
    private val claimed = check(confirmations = 12, balance = 0, spent = true)

    @Test
    fun `funding matches the outgoing transaction only`() {
        val link = sent()
        assertEquals(LinkActivityKind.FUNDING, PaymentLinkActivityRules.match("f1", false, listOf(link), emptyList())?.kind)
        assertNull(PaymentLinkActivityRules.match("f1", true, listOf(link), emptyList()))
    }

    @Test
    fun `reclaim and claim match incoming settle transactions`() {
        val sentLinks = listOf(sent(id = "a", reclaimTxid = "s1"))
        val receivedLinks = listOf(received(id = "b", claimTxid = "c1"))
        assertEquals(LinkActivityKind.RECLAIM, PaymentLinkActivityRules.match("s1", true, sentLinks, receivedLinks)?.kind)
        val claim = PaymentLinkActivityRules.match("c1", true, sentLinks, receivedLinks)
        assertEquals(LinkActivityKind.CLAIM, claim?.kind)
        assertEquals("b", claim?.id)
        assertEquals(PaymentLinkRoutes.RECEIVED, claim?.routeKind)
        assertNull(PaymentLinkActivityRules.match("s1", false, sentLinks, receivedLinks))
        assertNull(PaymentLinkActivityRules.match("c1", false, sentLinks, receivedLinks))
        // A reclaim that lost a race keeps an empty txid: it matches nothing.
        assertNull(PaymentLinkActivityRules.match("", true, listOf(sent(reclaimTxid = "")), emptyList()))
    }

    @Test
    fun `other transactions do not match`() {
        val sentLinks = listOf(sent(reclaimTxid = "s1"))
        val receivedLinks = listOf(received(claimTxid = "c1"))
        assertNull(PaymentLinkActivityRules.match("other", true, sentLinks, receivedLinks))
        assertNull(PaymentLinkActivityRules.match("other", false, sentLinks, receivedLinks))
        assertNull(PaymentLinkActivityRules.match("f1", false, emptyList(), emptyList()))
    }

    @Test
    fun titles() {
        assertEquals("Sent by Link", LinkActivity(LinkActivityKind.FUNDING, sent(), null).title)
        assertEquals("Reclaimed by Link", LinkActivity(LinkActivityKind.RECLAIM, sent(), null).title)
        assertEquals("Received by Link", LinkActivity(LinkActivityKind.CLAIM, null, received()).title)
    }

    @Test
    fun `a funding row shows the link state, never not funded`() {
        val state = PaymentLinkActivityRules::fundingRowState
        assertEquals(LinkRowState.WAITING, state(LinkStatus.NOT_FUNDED))
        assertEquals(LinkRowState.WAITING, state(LinkStatus.PENDING))
        assertEquals(LinkRowState.READY, state(LinkStatus.READY))
        assertEquals(LinkRowState.EXPIRED, state(LinkStatus.EXPIRED))
        assertEquals(LinkRowState.CLAIMED, state(LinkStatus.CLAIMED))
        assertEquals(LinkRowState.RECLAIMED, state(LinkStatus.RECLAIMED))
        assertEquals(
            listOf("Waiting", "Ready", "Claimed", "Reclaimed", "Expired", "Ready to claim"),
            LinkRowState.entries.map(PaymentLinkActivityRules::stateLabel)
        )
    }

    @Test
    fun `a received link not claimed yet is Ready to claim or Waiting`() {
        assertEquals(LinkRowState.READY_TO_CLAIM, PaymentLinkActivityRules.pendingRowState(PendingLinkState.Ready))
        assertEquals(LinkRowState.WAITING, PaymentLinkActivityRules.pendingRowState(PendingLinkState.Waiting(4)))
        assertEquals(LinkRowState.WAITING, PaymentLinkActivityRules.pendingRowState(PendingLinkState.NoMoney))
        assertEquals(LinkRowState.WAITING, PaymentLinkActivityRules.pendingRowState(PendingLinkState.Unchecked))
    }

    private fun row(activity: LinkActivity, txAmount: Long = 520_000_000_000, confirmations: Long = 3, fiatMode: Boolean = false) =
        PaymentLinkActivityRules.transactionRow(
            activity, txAmount, confirmations, incoming = activity.kind != LinkActivityKind.FUNDING, date = "Oct 16",
            fiatMode = fiatMode, fiatOf = { "\$" + it / 10_000_000_000 }, transactionStatus = "Locked", nowSeconds = 1_800_000_000
        )

    @Test
    fun `a funding row is the link's name, its one amount and its state`() {
        val link = sent().copy(number = 4, note = "Dinner")
        val text = row(LinkActivity(LinkActivityKind.FUNDING, link, null))
        assertEquals("Dinner", text.title)
        // The link's amount (what the recipient gets), not the funding tx's amount with the claim fee reserve.
        assertEquals("\u22120.5000 XMR", text.amount)
        assertEquals("\$50", text.secondary)
        assertEquals(LinkRowState.WAITING, text.state)
        assertEquals("Sent by Link, Dinner, minus 0.5000 XMR, Waiting, Oct 16", text.spoken)
    }

    @Test
    fun `a claim or reclaim row shows its own amount and the transaction status`() {
        val claim = row(LinkActivity(LinkActivityKind.CLAIM, null, received().copy(number = 2)), txAmount = 490_000_000_000)
        assertEquals("Link 2", claim.title)
        assertEquals("+0.4900 XMR", claim.amount)
        assertNull(claim.state)
        assertEquals("Received by Link, Link 2, plus 0.4900 XMR, Locked, Oct 16", claim.spoken)

        val reclaim = row(LinkActivity(LinkActivityKind.RECLAIM, sent().copy(number = 1), null), txAmount = 499_000_000_000)
        assertEquals("Link 1", reclaim.title)
        assertEquals("Reclaimed by Link, Link 1, plus 0.4990 XMR, Locked, Oct 16", reclaim.spoken)
    }

    @Test
    fun `in fiat mode a link row leads with fiat`() {
        val text = row(LinkActivity(LinkActivityKind.FUNDING, sent().copy(number = 4), null), fiatMode = true)
        assertEquals("\u2212\$50", text.amount)
        assertEquals("0.5000 XMR", text.secondary)
        assertEquals("Sent by Link, Link 4, minus \$50, 0.5000 XMR, Waiting, Oct 16", text.spoken)
    }

    @Test
    fun `pending state from the record`() {
        val now = 1_800_000_000_000
        assertEquals(PendingLinkState.Unchecked, PaymentLinkActivityRules.pendingState(received(), 500, now))
        assertEquals(PendingLinkState.NoMoney, PaymentLinkActivityRules.pendingState(received(check = noMoney), 500, now))
        assertEquals(PendingLinkState.Ready, PaymentLinkActivityRules.pendingState(received(check = ready), null, now))
    }

    @Test
    fun `pending counts down from the funding height`() {
        val link = received(check = check(confirmations = 0, fundingHeight = 400))
        val now = 1_800_000_000_000
        // 4 confirmations: 6 blocks of 2 minutes left.
        assertEquals(PendingLinkState.Waiting(12), PaymentLinkActivityRules.pendingState(link, 404, now))
        assertEquals(PendingLinkState.Waiting(2), PaymentLinkActivityRules.pendingState(link, 409, now))
        assertEquals(PendingLinkState.Ready, PaymentLinkActivityRules.pendingState(link, 410, now))
        assertEquals(PendingLinkState.Ready, PaymentLinkActivityRules.pendingState(link, 450, now))
    }

    @Test
    fun `pending without a height counts down from the last check`() {
        val checked = 1_800_000_000_000
        // In the pool at the check: 20 minutes, less the time since.
        val pool = received(check = check(mined = false, confirmations = 0, checkedAt = checked))
        assertEquals(PendingLinkState.Waiting(20), PaymentLinkActivityRules.pendingState(pool, null, checked))
        assertEquals(PendingLinkState.Waiting(15), PaymentLinkActivityRules.pendingState(pool, null, checked + 5 * 60_000))
        assertEquals(PendingLinkState.Ready, PaymentLinkActivityRules.pendingState(pool, null, checked + 21 * 60_000))
        // A daemon height below the funding height (a node behind) is not used.
        val behind = received(check = check(confirmations = 0, fundingHeight = 500, checkedAt = checked))
        assertEquals(PendingLinkState.Waiting(15), PaymentLinkActivityRules.pendingState(behind, 450, checked + 5 * 60_000))
    }

    @Test
    fun `pending labels`() {
        assertEquals("Tap to check", PaymentLinkActivityRules.pendingLabel(PendingLinkState.Unchecked))
        assertEquals("No money yet", PaymentLinkActivityRules.pendingLabel(PendingLinkState.NoMoney))
        assertEquals("Ready in about 12 min", PaymentLinkActivityRules.pendingLabel(PendingLinkState.Waiting(12)))
        assertEquals("Ready to claim", PaymentLinkActivityRules.pendingLabel(PendingLinkState.Ready))
    }

    @Test
    fun `only unsettled received links with a secret are pinned`() {
        val links = listOf(
            received(id = "a", check = waiting),
            received(id = "b"),
            received(id = "c", state = ReceivedState.CLAIMED, claimTxid = "c1"),
            received(id = "d", check = claimed),
            received(id = "e", check = ready, withSecret = false)
        )
        assertEquals(setOf("a", "b"), PaymentLinkActivityRules.pendingRecords(links).map { it.id }.toSet())
    }

    @Test
    fun `a received link's detail chip reads as the pinned row`() {
        val now = 1_800_000_000_000
        assertEquals("No money yet", receivedDisplay(received(check = noMoney), null, now / 1000).label)
        val unlocked = received(check = check(confirmations = 10, checkedAt = now))
        assertEquals("Ready to claim", receivedDisplay(unlocked, null, now / 1000).label)
        assertEquals(LinkHue.GREEN, receivedDisplay(unlocked, null, now / 1000).hue)
        val young = received(check = check(confirmations = 2, checkedAt = now))
        assertEquals("Pending 2/10", receivedDisplay(young, null, now / 1000).label)
    }

    @Test
    fun `received confirmations take the higher of the check and the daemon height`() {
        val link = received(check = check(confirmations = 2, fundingHeight = 400))
        assertEquals(2L, PaymentLinkActivityRules.receivedConfirmations(link, null))
        assertEquals(6L, PaymentLinkActivityRules.receivedConfirmations(link, 406))
        assertEquals(2L, PaymentLinkActivityRules.receivedConfirmations(link, 390))
        assertNull(PaymentLinkActivityRules.receivedConfirmations(received(), 406))
    }

    @Test
    fun `a received link's row shows its name and the claim estimate in XMR over fiat`() {
        val link = received(check = ready).copy(number = 7, netAmount = 49_186_000_000)
        val row = PaymentLinkActivityRules.pendingRow(link, PendingLinkState.Ready, "Thu, 8:12 PM", fiatMode = false, fiatValue = "\$12.35")
        assertEquals("Link 7", row.title)
        assertEquals("+~0.049186 XMR", row.amount)
        assertEquals("~\$12.35", row.secondary)
        assertEquals(LinkRowState.READY_TO_CLAIM, row.state)
        assertEquals("Payment link, Link 7, plus about 0.049186 XMR, Ready to claim, Thu, 8:12 PM", row.spoken)
    }

    @Test
    fun `in fiat mode a received link's row leads with fiat`() {
        val link = received(check = waiting).copy(number = 7, netAmount = 49_186_000_000)
        val row = PaymentLinkActivityRules.pendingRow(link, PendingLinkState.Waiting(4), "Oct 16", fiatMode = true, fiatValue = "\$12.35")
        assertEquals("+~\$12.35", row.amount)
        assertEquals("~0.04918 XMR", row.secondary)
        assertEquals("Payment link, Link 7, plus about \$12.35, 0.04918 XMR, Waiting, Oct 16", row.spoken)
        // With no price yet, fiat mode falls back to XMR like every row.
        val noPrice = PaymentLinkActivityRules.pendingRow(link, PendingLinkState.Ready, "Oct 16", fiatMode = true, fiatValue = null)
        assertEquals("+~0.049186 XMR", noPrice.amount)
        assertNull(noPrice.secondary)
    }

    @Test
    fun `a received link with no known amount shows no amount`() {
        val row = PaymentLinkActivityRules.pendingRow(received().copy(number = 3), PendingLinkState.Unchecked, "Oct 16", fiatMode = true, fiatValue = null)
        assertNull(row.amount)
        assertNull(row.secondary)
        assertEquals("Payment link, Link 3, Waiting, Oct 16", row.spoken)
    }
}
