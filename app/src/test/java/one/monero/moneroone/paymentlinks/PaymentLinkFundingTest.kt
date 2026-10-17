package one.monero.moneroone.paymentlinks

import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.model.Transfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sent link's funding (the founder's Link 10, iOS and Android): a link with a funding transaction is
 * never Not funded and always offers Share; a link with no funding anywhere is hidden, and comes back
 * once its funding shows up; nothing offers Retry or Remove; a missed funding is rescanned.
 */
class PaymentLinkFundingTest {

    private val secret = "000102030405060708090a0b0c0d0e0f"
    private val now = 1_800_000_000L
    private val envelope = "4envelopeAddress"

    private fun sent(
        id: String = "0123456789abcdef",
        state: SentState = SentState.FUNDED,
        fundingTxid: String? = "f-$id",
        check: EnvelopeSnapshot? = null,
        reclaimTxid: String? = null,
        height: Long = 100,
        expiry: Long = now + 86_400
    ) = SentLink(
        id = id, height = height, amount = 20_000_000_000, expiry = expiry, createdAtMillis = 1, walletId = "w",
        envelopeAddress = envelope, feeReserve = 300_000_000, state = state, fundingTxid = fundingTxid,
        reclaimTxid = reclaimTxid, lastCheck = check, secret = secret, number = 10, note = "Dinner"
    )

    private fun check(
        seen: Boolean = true, mined: Boolean = true, confirmations: Long = 0, balance: Long = 0, unlocked: Long = 0,
        spent: Boolean = false
    ) = EnvelopeSnapshot(1, seen, mined, confirmations, balance, unlocked, spent, emptyList(), null)

    /** A full sync that did not see the funding. */
    private val unseen = check(seen = false, mined = false)

    /** An outgoing transaction of the open wallet, to [to] when given. */
    private fun tx(hash: String, to: String? = null, confirmations: Long = 0, blockheight: Long = 0, pending: Boolean = confirmations == 0L) =
        TransactionInfo(
            1, pending, false, 20_300_000_000, 40_000_000, blockheight, hash, now - 10, "", 0, 0, confirmations, 0, "",
            to?.let { listOf(Transfer(20_300_000_000, it)) } ?: emptyList()
        )

    private fun row(view: SentView) = PaymentLinkActivityRules.stateLabel(PaymentLinkActivityRules.fundingRowState(view.status))

    @Test
    fun `a link with a funding transaction is never Not funded`() {
        val fundings = listOf(
            sent(state = SentState.NOT_FUNDED),                       // the error came after the node took it
            sent(state = SentState.FUNDED, fundingTxid = null),       // broadcast out, txid not read yet
            sent(state = SentState.CREATED, fundingTxid = "f")        // the app quit mid-broadcast
        )
        for (link in fundings) for (last in listOf(null, unseen)) {
            val record = link.copy(lastCheck = last)
            assertEquals(LinkStatus.PENDING, LinkStatusRules.baseStatus(record))
            assertFalse(LinkStatusRules.isHidden(record))
        }
        // The open wallet's own history counts as a funding transaction.
        val bare = sent(state = SentState.NOT_FUNDED, fundingTxid = null, check = unseen)
        assertEquals(LinkStatus.NOT_FUNDED, LinkStatusRules.baseStatus(bare))
        assertEquals(LinkStatus.PENDING, LinkStatusRules.baseStatus(bare, inWallet = true))
    }

    @Test
    fun `a funding the scan has not seen waits in the pool, after a block and with the node down`() {
        val link = sent(check = unseen)
        // In the pool: the open wallet holds the funding with 0 confirmations.
        val pool = sentView(link, listOf(tx("f-${link.id}")), daemonHeight = null, nowSeconds = now)
        assertEquals(LinkStatus.PENDING, pool.status)
        assertEquals("Pending 0/10", sentDisplay(pool).label)
        assertEquals("Waiting", row(pool))
        // Mined: one confirmation in the open wallet, the scan still behind.
        val mined = sentView(link, listOf(tx("f-${link.id}", confirmations = 1, blockheight = 500)), null, now)
        assertEquals(LinkStatus.PENDING, mined.status)
        assertEquals("Pending 1/10", sentDisplay(mined).label)
        // Node down: no new check and no wallet history; the record alone still waits.
        val offline = sentView(link, emptyList(), null, now)
        assertEquals(LinkStatus.PENDING, offline.status)
        assertEquals("Pending", sentDisplay(offline).label)
        // The wallet's history wins over the missed scan: ready at 10 (Reclaim checks the envelope first).
        val deep = sentView(link, listOf(tx("f-${link.id}", confirmations = 40, blockheight = 500)), null, now)
        assertEquals(LinkStatus.READY, deep.status)
        assertEquals(40L, deep.confirmations)
        assertTrue(deep.canReclaim)
        // The scan agrees: the normal rules again.
        val agreed = link.copy(lastCheck = check(confirmations = 10, balance = 5, unlocked = 5))
        assertEquals(LinkStatus.READY, sentView(agreed, emptyList(), null, now).status)
    }

    @Test
    fun `the status follows the wallet's history even before the record has its txid`() {
        val link = sent(state = SentState.NOT_FUNDED, fundingTxid = null, check = unseen)
        val history = listOf(tx("other", to = "4someoneElse", confirmations = 5, blockheight = 400), tx("late", to = envelope, confirmations = 2, blockheight = 499))
        assertEquals("late", fundingTransaction(link, history)?.hash)
        val view = sentView(link, history, null, now)
        assertEquals(LinkStatus.PENDING, view.status)
        assertEquals(2L, view.confirmations)
        assertEquals(LinkStatus.NOT_FUNDED, sentView(link, history.take(1), null, now).status)
    }

    @Test
    fun `Share stays for every link with a funding transaction that is not claimed or reclaimed`() {
        val waiting = sentView(sent(check = unseen), emptyList(), null, now)
        val expired = sentView(sent(check = unseen, expiry = now - 1), emptyList(), null, now)
        val ready = sentView(sent(check = check(confirmations = 10, balance = 5, unlocked = 5)), emptyList(), null, now)
        for (view in listOf(waiting, expired, ready)) {
            assertTrue(view.status.name, SentLinkAction.SHARE_AGAIN in LinkStatusRules.sentActions(view.status))
        }
        assertEquals(LinkStatus.EXPIRED, expired.status)
        val claimed = sent(check = check(confirmations = 12, spent = true))
        val reclaimed = sent(reclaimTxid = "back")
        for (link in listOf(claimed, reclaimed)) {
            assertEquals(emptyList<SentLinkAction>(), LinkStatusRules.sentActions(sentView(link, emptyList(), null, now).status))
        }
    }

    @Test
    fun `no link state offers Retry or Remove`() {
        assertEquals(listOf("SHARE_AGAIN", "RECLAIM"), SentLinkAction.entries.map { it.name })
        for (status in LinkStatus.entries) {
            val actions = LinkStatusRules.sentActions(status).map { it.name }
            assertTrue(status.name, actions.none { "RETRY" in it || "REMOVE" in it || "DELETE" in it })
        }
        // No per-link delete path in the store or the view model (Remove All Wallets wipes everything).
        val storeMethods = PaymentLinkStore::class.java.declaredMethods.map { it.name }
        assertTrue(storeMethods.toString(), storeMethods.none { it.startsWith("remove") && it != "removeAll" })
        val modelMethods = PaymentLinksViewModel::class.java.declaredMethods.map { it.name }
        assertTrue(modelMethods.toString(), modelMethods.none { it.startsWith("remove") })
    }

    @Test
    fun `a link with no funding anywhere is hidden, and keeps its record`() {
        val hidden = sent(state = SentState.NOT_FUNDED, fundingTxid = null, check = unseen)
        assertTrue(LinkStatusRules.isHidden(hidden))
        assertTrue(LinkStatusRules.isHidden(hidden.copy(state = SentState.CREATED, lastCheck = null)))
        // Not on the balance card's line, not in Activity, not in the Links counts.
        assertTrue(OpenLinkRules.openLinks(listOf(hidden), emptyList(), "w", now).isEmpty)
        val sections = LinkListRules.sections(listOf(tx("unrelated", to = "4someoneElse", confirmations = 3, blockheight = 400)), PaymentLinkIndex(listOf(hidden), emptyList()), emptyList(), now)
        assertEquals(0, sections.linkCount)
        assertTrue(sections.open.isEmpty() && sections.history.isEmpty())
        // Never settled, so its record, secret and files stay.
        assertFalse(LinkStatusRules.isSettled(hidden))
    }

    @Test
    fun `a funding found later brings the link back as Waiting`() {
        val hidden = sent(id = "1111111111111111", state = SentState.NOT_FUNDED, fundingTxid = null, check = unseen)
        val known = sent(id = "2222222222222222")
        val reclaimed = sent(id = "3333333333333333", fundingTxid = null, reclaimTxid = "back")
        val walletHistory = { address: String -> if (address == envelope) "late" else null }
        assertEquals(mapOf(hidden.id to "late"), LinkStatusRules.fundingsToAdopt(listOf(hidden, known, reclaimed), fundingTxidFor = walletHistory))
        assertEquals(emptyMap<String, String>(), LinkStatusRules.fundingsToAdopt(listOf(hidden)) { null })

        val adopted = hidden.copy(fundingTxid = "late", state = SentState.FUNDED)
        assertFalse(LinkStatusRules.isHidden(adopted))
        assertEquals(LinkStatus.PENDING, LinkStatusRules.status(adopted, now))
        assertEquals(listOf(adopted), OpenLinkRules.openLinks(listOf(adopted), emptyList(), "w", now).sent)
        val sections = LinkListRules.sections(listOf(tx("late", to = envelope, confirmations = 1, blockheight = 499)), PaymentLinkIndex(listOf(adopted), emptyList()), emptyList(), now)
        assertEquals(1, sections.linkCount)
        assertEquals(1, sections.openCount)
        // A check that sees the money brings it back as well.
        assertFalse(LinkStatusRules.isHidden(hidden.copy(lastCheck = check(mined = false))))
    }

    @Test
    fun `a funding the wallet marks failed sent no money`() {
        val link = sent(id = "4444444444444444", fundingTxid = "dropped")
        val failedTx = tx("dropped", to = envelope).let {
            TransactionInfo(1, false, true, it.amount, it.fee, 0, "dropped", it.timestamp, "", 0, 0, 0, 0, "", it.transfers)
        }
        // The wallet's history flags it, and clears the flag when the transaction is live again.
        assertEquals(mapOf(link.id to true), LinkStatusRules.fundingFailures(listOf(link), failed = setOf("dropped"), live = emptySet()))
        val flagged = link.copy(fundingFailed = true)
        assertEquals(mapOf(link.id to false), LinkStatusRules.fundingFailures(listOf(flagged), failed = emptySet(), live = setOf("dropped")))
        assertEquals(emptyMap<String, Boolean>(), LinkStatusRules.fundingFailures(listOf(flagged), failed = setOf("dropped"), live = emptySet()))
        // A txid the open wallet does not hold (another wallet funded it) keeps its value.
        assertEquals(emptyMap<String, Boolean>(), LinkStatusRules.fundingFailures(listOf(link), failed = emptySet(), live = setOf("other")))
        // Flagged: hidden, no Share, not open, no Links row; the failed row stays a plain transaction.
        assertTrue(LinkStatusRules.isHidden(flagged))
        assertEquals(LinkStatus.NOT_FUNDED, sentView(flagged, listOf(failedTx), null, now).status)
        assertTrue(OpenLinkRules.openLinks(listOf(flagged), emptyList(), "w", now).isEmpty)
        val index = PaymentLinkIndex(listOf(flagged), emptyList())
        assertNull(index.match(failedTx))
        assertEquals(0, LinkListRules.sections(listOf(failedTx), index, emptyList(), now).linkCount)
        // A check that saw money, or a new funding to the envelope, counts again.
        assertFalse(LinkStatusRules.isHidden(flagged.copy(lastCheck = check(mined = false))))
        assertEquals(mapOf(link.id to "resent"), LinkStatusRules.fundingsToAdopt(listOf(flagged)) { "resent" })
    }

    @Test
    fun `a missed funding is rescanned from no later than its block`() {
        // The restore height sits above the funding's block: start under the block.
        assertEquals(990L, LinkStatusRules.rescanFrom(sent(height = 1_005), unseen, fundingBlock = 1_000))
        // The restore height is lower: start there, with a new cache.
        assertEquals(900L, LinkStatusRules.rescanFrom(sent(height = 900), unseen, fundingBlock = 1_000))
        assertEquals(0L, LinkStatusRules.rescanFrom(sent(height = 5), unseen, fundingBlock = 3))
        // No rescan: the check saw it, the funding is not in a block, or the link is settled.
        assertNull(LinkStatusRules.rescanFrom(sent(), check(confirmations = 2, balance = 5), fundingBlock = 1_000))
        assertNull(LinkStatusRules.rescanFrom(sent(), unseen, fundingBlock = null))
        assertNull(LinkStatusRules.rescanFrom(sent(reclaimTxid = "back"), unseen, fundingBlock = 1_000))
    }

    @Test
    fun `the check log line carries the link's number only`() {
        val line = LinkStatusRules.checkLogLine(10, check(confirmations = 3, balance = 5).copy(spendTxids = listOf("spendhash")))
        assertEquals("link 10 check seen=true mined=true conf=3 balance=yes unlocked=none spent=false", line)
        val link = sent()
        for (private in listOf(link.note!!, link.envelopeAddress, secret, "spendhash", link.id)) assertFalse(private, private in line)
        assertEquals("link ? check seen=false mined=false conf=0 balance=none unlocked=none spent=false", LinkStatusRules.checkLogLine(null, unseen))
    }
}
