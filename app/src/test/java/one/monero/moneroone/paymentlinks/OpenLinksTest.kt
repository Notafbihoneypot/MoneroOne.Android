package one.monero.moneroone.paymentlinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The balance card's open-link lines and which links count as open (spec Round 3). */
class OpenLinksTest {

    private val secret = "000102030405060708090a0b0c0d0e0f"
    private val expiry = 2_000_000_000L
    private val before = expiry - 1
    private val after = expiry + 1

    private fun sent(
        id: String = "a",
        walletId: String = "w",
        amount: Long = 50_000_000_000,
        createdAt: Long = 1,
        state: SentState = SentState.FUNDED,
        check: EnvelopeSnapshot? = null,
        reclaimTxid: String? = null,
        expiresAt: Long = expiry
    ) = SentLink(
        id = id, height = 100, amount = amount, expiry = expiresAt, createdAtMillis = createdAt,
        walletId = walletId, envelopeAddress = "4...", feeReserve = 1, state = state, fundingTxid = "f$id",
        reclaimTxid = reclaimTxid, lastCheck = check, secret = secret
    )

    private fun received(id: String = "r", amount: Long = 0, check: EnvelopeSnapshot? = null) = ReceivedLink(
        id = id, words = null, height = 100, amount = amount, expiry = 0, receivedAtMillis = 1,
        lastCheck = check, secret = secret
    )

    private fun check(confirmations: Long = 3, balance: Long = 1_000, unlocked: Long = 0, spent: Boolean = false) =
        EnvelopeSnapshot(1, true, true, confirmations, balance, unlocked, spent)

    private val waiting = check()
    private val ready = check(confirmations = 12, unlocked = 1_000)
    private val claimed = check(confirmations = 12, balance = 0, spent = true)

    @Test
    fun `one open link reads singular`() {
        val line = OpenLinkRules.sentLine("0.05 XMR", count = 1, expired = 0)
        assertEquals("0.05 XMR", line.amount)
        assertEquals("in 1 open link", line.rest)
        assertEquals("0.05 XMR in 1 open link", line.spoken)
    }

    @Test
    fun `several open links read plural with the expired count`() {
        val line = OpenLinkRules.sentLine("0.40 XMR", count = 3, expired = 1)
        assertEquals("0.40 XMR", line.amount)
        assertEquals("in 3 open links · 1 expired", line.rest)
        assertEquals("0.40 XMR in 3 open links, 1 expired", line.spoken)
        assertEquals("in 2 open links", OpenLinkRules.sentLine("0.15 XMR", count = 2, expired = 0).rest)
    }

    @Test
    fun `the claim line shows the amount, or the count when no amount is known`() {
        assertEquals(OpenLinkLine("~0.03 XMR", "to claim", "about 0.03 XMR to claim"), OpenLinkRules.claimLine("0.03 XMR", 1))
        assertEquals(OpenLinkLine("1 payment link", "to claim", "1 payment link to claim"), OpenLinkRules.claimLine(null, 1))
        assertEquals("2 payment links", OpenLinkRules.claimLine(null, 2).amount)
    }

    @Test
    fun `in fiat mode the lines lead with fiat`() {
        val sent = OpenLinkRules.sentLine("\$11.14", count = 1, expired = 0)
        assertEquals("\$11.14", sent.amount)
        assertEquals("\$11.14 in 1 open link", sent.spoken)
        val claim = OpenLinkRules.claimLine("\$27.37", 1)
        assertEquals(OpenLinkLine("~\$27.37", "to claim", "about \$27.37 to claim"), claim)
    }

    @Test
    fun `pending, ready and expired links are open`() {
        assertTrue(OpenLinkRules.isOpen(sent(), before))
        assertTrue(OpenLinkRules.isOpen(sent(check = waiting), before))
        assertTrue(OpenLinkRules.isOpen(sent(check = ready), before))
        assertTrue(OpenLinkRules.isOpen(sent(check = waiting), after))
        assertTrue(OpenLinkRules.isOpen(sent(check = ready), after))
    }

    @Test
    fun `claimed, reclaimed and unfunded links are not open`() {
        assertFalse(OpenLinkRules.isOpen(sent(check = claimed), before))
        assertFalse(OpenLinkRules.isOpen(sent(check = ready, reclaimTxid = "rc"), after))
        assertFalse(OpenLinkRules.isOpen(sent(state = SentState.NOT_FUNDED), before))
        assertFalse(OpenLinkRules.isOpen(sent(state = SentState.CREATED), before))
    }

    @Test
    fun `only the wallet's own sent links count, and expired ones are counted`() {
        val links = listOf(
            sent(id = "a", createdAt = 1, check = ready),
            sent(id = "b", createdAt = 3, check = waiting),
            sent(id = "c", createdAt = 2, walletId = "other", check = ready),
            sent(id = "d", createdAt = 4, check = claimed)
        )
        val now = OpenLinkRules.openLinks(links, emptyList(), "w", before)
        assertEquals(listOf("b", "a"), now.sent.map { it.id })
        assertEquals(0, now.expired)
        assertEquals(100_000_000_000, now.sentAmount)

        val later = OpenLinkRules.openLinks(links, emptyList(), "w", after)
        assertEquals(2, later.expired)
        assertTrue(OpenLinkRules.openLinks(links, emptyList(), "nobody", before).isEmpty)
    }

    @Test
    fun `received links count on every wallet until claimed`() {
        val links = listOf(received(id = "x", amount = 30_000_000_000), received(id = "y", check = claimed))
        val open = OpenLinkRules.openLinks(emptyList(), links, "w", before)
        assertEquals(listOf("x"), open.received.map { it.id })
        assertEquals(30_000_000_000, open.receivedAmount)
        assertEquals(listOf("x"), OpenLinkRules.openLinks(emptyList(), links, "other", before).received.map { it.id })
        assertNull(OpenLinkRules.openLinks(emptyList(), listOf(received()), "w", before).receivedAmount)
    }

    @Test
    fun `open sent links list newest first and count the expired ones`() {
        val expired = sent(id = "e", createdAt = 1, check = ready, expiresAt = before - 10)
        val older = sent(id = "o", createdAt = 2, check = waiting)
        val newer = sent(id = "n", createdAt = 5, check = waiting)
        val open = OpenLinkRules.openLinks(listOf(older, expired, newer), emptyList(), "w", before)
        assertEquals(1, open.expired)
        assertEquals(listOf("n", "o", "e"), open.sent.map { it.id })
    }
}
