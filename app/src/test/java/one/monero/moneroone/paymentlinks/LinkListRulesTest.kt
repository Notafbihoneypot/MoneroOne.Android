package one.monero.moneroone.paymentlinks

import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.ui.screens.transactions.TransactionHistoryLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.math.BigInteger

/** Activity's Links filter and the balance card's sent line: the same cases as iOS `LinkListTests`. */
class LinkListRulesTest {

    private val secret = "000102030405060708090a0b0c0d0e0f"
    private val now = 1_800_000_000L

    private fun sent(
        id: String,
        reclaimTxid: String? = null,
        check: EnvelopeSnapshot? = null,
        expiry: Long = now + 86_400,
        fundingTxid: String? = "f-$id"
    ) = SentLink(
        id = id, height = 100, amount = 20_000_000_000, expiry = expiry, createdAtMillis = 1, walletId = "w",
        envelopeAddress = "4", feeReserve = 300_000_000, state = SentState.FUNDED, fundingTxid = fundingTxid,
        reclaimTxid = reclaimTxid, lastCheck = check, secret = secret
    )

    private fun received(id: String, opened: Long, claimTxid: String? = null) = ReceivedLink(
        id = id, words = null, height = 100, amount = 0, expiry = 0, receivedAtMillis = opened,
        state = if (claimTxid != null) ReceivedState.CLAIMED else ReceivedState.PENDING, claimTxid = claimTxid, secret = secret
    )

    private fun tx(hash: String, incoming: Boolean, atSeconds: Long, amount: Long = 20_340_000_000, confirmations: Long = 3) =
        TransactionInfo(if (incoming) 0 else 1, false, false, amount, 40_000_000, 50, hash, atSeconds, "", 0, 0, confirmations, 0, "", emptyList())

    private val claimedCheck = EnvelopeSnapshot(1, true, true, 12, 0, 0, true, emptyList(), null)

    @Test
    fun `open holds the links with money, newest first, sent and received mixed, and history the rest`() {
        val waiting = sent("waiting")
        val expired = sent("expired", expiry = now - 10)
        val claimed = sent("claimed", check = claimedCheck)
        val reclaimed = sent("reclaimed", reclaimTxid = "back")
        val claim = received("got", opened = 1_000, claimTxid = "claim")
        val pending = received("toclaim", opened = (now - 50) * 1000)
        val index = PaymentLinkIndex(listOf(waiting, expired, claimed, reclaimed), listOf(claim, pending))
        val transactions = listOf(
            tx("f-waiting", false, now - 10),
            tx("f-expired", false, now - 900),
            tx("f-claimed", false, now - 200),
            tx("f-reclaimed", false, now - 700),
            tx("back", true, now - 600),
            tx("claim", true, now - 300),
            tx("other", true, now - 5)
        )

        val sections = LinkListRules.sections(transactions, index, listOf(pending), now)

        assertEquals(listOf("waiting", "toclaim", "expired"), sections.open.map { it.linkId })
        assertEquals(listOf("f-claimed", "claim", "back", "f-reclaimed"), sections.history.map { it.key })
        // 6 links (the reclaimed one has two rows), 3 open.
        assertEquals(6, sections.linkCount)
        assertEquals(3, sections.openCount)
    }

    @Test
    fun `each row shows once`() {
        val claimedHere = received("got", opened = 1_000, claimTxid = "claim")
        val index = PaymentLinkIndex(listOf(sent("a")), listOf(claimedHere))
        val transactions = listOf(tx("f-a", false, now - 10), tx("claim", true, now - 20))
        // A received record passed as waiting while its claim row shows: it is not listed twice.
        val sections = LinkListRules.sections(transactions, index, listOf(claimedHere), now)
        val keys = (sections.open + sections.history).map { it.key }
        assertEquals(keys.distinct(), keys)
        assertEquals(listOf("f-a", "claim"), keys)
        assertEquals(2, sections.linkCount)
    }

    @Test
    fun `the Links totals count each link's amount, as its row shows`() {
        val link = sent("a")
        val index = PaymentLinkIndex(listOf(link), emptyList())
        val funding = tx("f-a", false, now - 10, amount = 20_300_000_000)
        val back = tx("back", true, now - 5, amount = 19_000_000_000)
        assertEquals(20_000_000_000L, LinkListRules.rowAmount(funding, index))
        assertEquals(19_000_000_000L, LinkListRules.rowAmount(back, index))

        val totals = TransactionHistoryLogic.totals(listOf(funding, back)) { LinkListRules.rowAmount(it, index) }
        assertEquals(BigInteger.valueOf(20_000_000_000), totals.sent)
        assertEquals(BigInteger.valueOf(19_000_000_000), totals.received)
        val fiat = TransactionHistoryLogic.fiatTotals(listOf(funding), { LinkListRules.rowAmount(it, index) }) { 100.0 }!!
        assertEquals(0, fiat.sent.compareTo(java.math.BigDecimal("2.00")))
    }

    @Test
    fun `the sent line opens the one open link, else the Links filter`() {
        val one = sent("a")
        assertSame(one, OpenLinkRules.singleOpenLink(OpenLinks(listOf(one), 0, emptyList())))
        assertNull(OpenLinkRules.singleOpenLink(OpenLinks(listOf(one, sent("b")), 0, emptyList())))
        // Its funding transaction is not known yet: the Links filter.
        assertNull(OpenLinkRules.singleOpenLink(OpenLinks(listOf(sent("c", fundingTxid = null)), 0, emptyList())))
    }
}
