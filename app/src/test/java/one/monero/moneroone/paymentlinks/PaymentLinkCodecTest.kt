package one.monero.moneroone.paymentlinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spec's test vector (regtest wallet, no value) and the parse rules. */
class PaymentLinkCodecTest {

    private val words = ("cogs aloof wobbly island zigzags setup saxophone jockey request wise mighty border " +
        "different axes innocent subtly northern testing fidget lids absorb benches madness nocturnal aloof").split(" ")

    private val vectorLink = "https://monero.one/c#v=1&s=cogs-aloof-wobbly-island-zigzags-setup-saxophone-jockey-" +
        "request-wise-mighty-border-different-axes-innocent-subtly-northern-testing-fidget-lids-absorb-benches-" +
        "madness-nocturnal-aloof&h=150&a=100000000000&e=1791000000&n=0123456789abcdef"

    private val vector = PaymentLink(words, 150, 100_000_000_000, 1_791_000_000, "0123456789abcdef")

    private fun valid(text: String): PaymentLink {
        val parsed = PaymentLinkCodec.parse(text)
        assertTrue("expected a valid link, got $parsed", parsed is PaymentLinkParse.Valid)
        return (parsed as PaymentLinkParse.Valid).link
    }

    @Test
    fun `vector builds to the exact link`() {
        assertEquals(vectorLink, vector.url())
    }

    @Test
    fun `vector parses back to the same fields`() {
        val link = valid(vectorLink)
        assertEquals(words, link.words)
        assertEquals(150L, link.height)
        assertEquals(100_000_000_000L, link.amount)
        assertEquals(1_791_000_000L, link.expiry)
        assertEquals("0123456789abcdef", link.id)
        assertEquals(vector, link)
        assertEquals(vectorLink, link.url())
    }

    @Test
    fun `words split on plus, percent-20 and spaces too`() {
        val joined = vectorLink.substringAfter("s=").substringBefore("&h=")
        for (separator in listOf("+", "%20", " ", "%20-")) {
            assertEquals(words, valid(vectorLink.replace(joined, joined.replace("-", separator))).words)
        }
    }

    @Test
    fun `upper case words and id are read as lower case`() {
        val upper = vectorLink.replace("cogs-aloof", "COGS-Aloof").replace("n=0123456789abcdef", "n=0123456789ABCDEF")
        val link = valid(upper)
        assertEquals(words, link.words)
        assertEquals("0123456789abcdef", link.id)
    }

    @Test
    fun `a link pasted without a scheme parses`() {
        assertEquals(vector, valid(vectorLink.removePrefix("https://")))
        assertEquals(vector, valid("  ${vectorLink.removePrefix("https://")}\n"))
    }

    @Test
    fun `key order does not matter and unknown keys are ignored`() {
        val fragment = vectorLink.substringAfter('#').split('&')
        val shuffled = "https://monero.one/c#x=9&" + fragment.reversed().joinToString("&") + "&utm=1"
        assertEquals(vector, valid(shuffled))
    }

    @Test
    fun `http and other hosts are not claim links`() {
        assertSame(PaymentLinkParse.NotALink, PaymentLinkCodec.parse(vectorLink.replace("https://", "http://")))
        assertSame(PaymentLinkParse.NotALink, PaymentLinkCodec.parse(vectorLink.replace("monero.one", "evil.one")))
        assertSame(PaymentLinkParse.NotALink, PaymentLinkCodec.parse(vectorLink.replace("monero.one/c", "monero.one/u")))
        assertSame(PaymentLinkParse.NotALink, PaymentLinkCodec.parse(vectorLink.replace("monero.one/c#", "monero.one/cb#")))
        assertSame(PaymentLinkParse.NotALink, PaymentLinkCodec.parse("monero:48F34bwGALB8apPAXnwgA916e53fKJPs2eXoJrdG53LeWzNTQT1ASJSNKpvqmQ6kw2i5hALMdWYKCeAJpwDSMdTFG7ynvk4"))
        assertSame(PaymentLinkParse.NotALink, PaymentLinkCodec.parse("48F34bwGALB8apPAXnwgA916e53fKJPs2eXoJrdG53LeWzNTQT1ASJSNKpvqmQ6kw2i5hALMdWYKCeAJpwDSMdTFG7ynvk4"))
        assertFalse(PaymentLinkCodec.isClaimLink(vectorLink.replace("https://", "http://")))
        assertTrue(PaymentLinkCodec.isClaimLink(vectorLink))
        assertTrue(PaymentLinkCodec.isClaimLink("monero.one/c#v=1"))
    }

    @Test
    fun `another version asks for an update`() {
        assertSame(PaymentLinkParse.NeedsUpdate, PaymentLinkCodec.parse(vectorLink.replace("#v=1", "#v=2")))
        assertSame(PaymentLinkParse.NeedsUpdate, PaymentLinkCodec.parse("https://monero.one/c#v=2"))
        // A version cut short or garbled is an incomplete link, not a newer one.
        assertSame(PaymentLinkParse.Incomplete, PaymentLinkCodec.parse("https://monero.one/c#v"))
        assertSame(PaymentLinkParse.Incomplete, PaymentLinkCodec.parse("https://monero.one/c#v="))
        assertSame(PaymentLinkParse.Incomplete, PaymentLinkCodec.parse("https://monero.one/c#v=x"))
        assertSame(PaymentLinkParse.Incomplete, PaymentLinkCodec.parse("https://monero.one/c#v=0"))
    }

    @Test
    fun `a missing or bad key makes the link incomplete`() {
        val incomplete = listOf(
            "https://monero.one/c",
            "https://monero.one/c#",
            vectorLink.replace("v=1&", ""),
            vectorLink.replace("&h=150", ""),
            vectorLink.replace("&a=100000000000", ""),
            vectorLink.replace("&e=1791000000", ""),
            vectorLink.replace("&n=0123456789abcdef", ""),
            vectorLink.replace("h=150", "h=-1"),
            vectorLink.replace("a=100000000000", "a=0.1"),
            vectorLink.replace("e=1791000000", "e=99999999999999999999"),
            vectorLink.replace("n=0123456789abcdef", "n=0123456789abcde"),
            vectorLink.replace("n=0123456789abcdef", "n=0123456789abcdeg"),
            // 24 words
            vectorLink.replace("cogs-aloof-", "cogs-"),
            // a word typed wrong fails the checksum word
            vectorLink.replace("wobbly", "wobble"),
            vectorLink.replace("nocturnal-aloof", "nocturnal-cogs"),
            vectorLink.replace("s=cogs", "s=%zz")
        )
        for (text in incomplete) {
            assertSame(text, PaymentLinkParse.Incomplete, PaymentLinkCodec.parse(text))
        }
    }

    @Test
    fun `the words never appear in toString`() {
        val text = vector.toString() + valid(vectorLink).toString()
        assertFalse(text.contains("cogs"))
        assertFalse(text.contains("nocturnal"))
    }
}
