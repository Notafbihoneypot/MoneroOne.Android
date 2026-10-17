package one.monero.moneroone.paymentlinks

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link names and numbers: the same cases as iOS `LinkNameTests`. */
class LinkNamesTest {

    private val secret = "000102030405060708090a0b0c0d0e0f"

    private fun sent(id: String, created: Long, number: Int? = null, note: String? = null) = SentLink(
        id = id, height = 100, amount = 20_000_000_000, expiry = 1_900_000_000, createdAtMillis = created,
        walletId = "w", envelopeAddress = "4...", feeReserve = 300_000_000, state = SentState.FUNDED,
        fundingTxid = "f-$id", secret = secret, number = number, note = note
    )

    private fun received(id: String, opened: Long, number: Int? = null, note: String? = null) = ReceivedLink(
        id = id, words = null, height = 100, amount = 0, expiry = 0, receivedAtMillis = opened,
        secret = secret, number = number, note = note
    )

    @Test
    fun `the name is the trimmed note, else Link and the number`() {
        assertEquals("Dinner", sent("a", 1, number = 3, note = "  Dinner \n").name)
        assertEquals("Link 3", sent("a", 1, number = 3, note = "   ").name)
        assertEquals("Link 3", sent("a", 1, number = 3, note = null).name)
        assertEquals("Link 12", received("b", 1, number = 12).name)
        assertEquals("Payment link", received("b", 1).name)
    }

    @Test
    fun `a note keeps at most 80 characters and a blank one is none`() {
        assertEquals(80, LinkNames.cleanNote("x".repeat(200))?.length)
        assertNull(LinkNames.cleanNote(" \t "))
        assertNull(LinkNames.cleanNote(null))
        assertEquals("Rent", LinkNames.cleanNote(" Rent "))
    }

    @Test
    fun `old records get numbers once, in the order they were made, sent and received mixed`() {
        val numbered = LinkNames.numbered(
            sent = listOf(sent("s2", created = 300), sent("s1", created = 100)),
            received = listOf(received("r1", opened = 200)),
            counter = 0
        )
        assertEquals(mapOf("s1" to 1, "s2" to 3), numbered.sent.associate { it.id to it.number })
        assertEquals(2, numbered.received.single().number)
        assertEquals(3, numbered.counter)

        // Again: nothing changes.
        val again = LinkNames.numbered(numbered.sent, numbered.received, numbered.counter)
        assertEquals(numbered.sent, again.sent)
        assertEquals(numbered.received, again.received)
        assertEquals(3, again.counter)
    }

    @Test
    fun `migration numbers after the highest number and the counter`() {
        val numbered = LinkNames.numbered(
            sent = listOf(sent("old", created = 50), sent("new", created = 900, number = 4)),
            received = emptyList(),
            counter = 6
        )
        assertEquals(7, numbered.sent.first { it.id == "old" }.number)
        assertEquals(4, numbered.sent.first { it.id == "new" }.number)
        assertEquals(7, numbered.counter)
    }

    @Test
    fun `records that all have numbers come back as they are`() {
        val list = listOf(sent("a", 1, number = 1))
        val numbered = LinkNames.numbered(list, emptyList(), 1)
        assertSame(list, numbered.sent)
        assertEquals(2, LinkNames.highest(list, listOf(received("b", 2, number = 2)), 0) )
    }

    @Test
    fun `records saved before numbers and notes still read`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """[{"id":"a","height":100,"amount":5,"expiry":9,"createdAtMillis":1,"walletId":"w",""" +
            """"envelopeAddress":"4","feeReserve":1,"state":"FUNDED"}]"""
        val link = json.decodeFromString(ListSerializer(SentLink.serializer()), old).single()
        assertNull(link.number)
        assertNull(link.note)
        assertEquals("Payment link", link.name)
    }

    @Test
    fun `the note is never in the link, its URL or the share text`() {
        val link = sent("a", 1, number = 2, note = "Dinner with Sam")
        val url = link.link.url()
        val share = shareText("0.02", link.link)
        assertFalse(url.contains("Dinner"))
        assertFalse(share.contains("Dinner"))
        assertTrue(share.contains(url))
        // The link a recipient opens carries no note: its record starts with none.
        val parsed = PaymentLinkCodec.parse(url) as PaymentLinkParse.Valid
        assertFalse(parsed.link.toString().contains("Dinner"))
    }

    @Test
    fun `the detail's total is the amount, the claim fee reserve and the network fee`() {
        val link = sent("a", 1)
        val costs = PaymentLinkActivityRules.sentCosts(link, fundingFee = 40_000_000)
        assertEquals(40_000_000L, costs.networkFee)
        assertEquals(300_000_000L, costs.claimFeeReserve)
        assertEquals(20_000_000_000L + 300_000_000L + 40_000_000L, costs.total)

        val unknown = PaymentLinkActivityRules.sentCosts(link, fundingFee = null)
        assertNull(unknown.networkFee)
        assertNull(unknown.total)
    }
}
