package one.monero.moneroone.paymentlinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The link shapes (privacy hardening, 2026-10-16): the https link puts the v2 body
 * behind the fragment directive `:~:m1=`; the older /c shapes still open. App-only
 * links (/a and moneroone://c) were removed on 2026-10-17: they no longer open.
 */
class PaymentLinkShapesTest {

    private val secret = hexToBytes("000102030405060708090a0b0c0d0e0f")!!
    private val link = PaymentLink.v2(secret, 150)!!
    private val body = "112drXXUifSrRnXLGbXg8E3b"

    private fun valid(text: String): PaymentLink {
        val parsed = PaymentLinkCodec.parse(text)
        assertTrue("expected a valid link, got $parsed for $text", parsed is PaymentLinkParse.Valid)
        return (parsed as PaymentLinkParse.Valid).link
    }

    @Test
    fun `the https link hides the secret behind the directive`() {
        val random = Random(7)
        repeat(50) {
            val s = ByteArray(16).also(random::nextBytes)
            val l = PaymentLink.v2(s, random.nextInt(4_000_000).toLong())!!
            val url = l.url()
            // The page sees "2" only: nothing of the secret comes before `:~:`.
            assertEquals("https://monero.one/c#2", url.substringBefore(":~:"))
            assertTrue(url.startsWith("https://monero.one/c#2:~:m1="))
            val encoded = Base58Integer.encode(s, EnvelopeV2.SECRET_LENGTH)!!
            assertFalse(url.substringBefore(":~:").contains(encoded))
            assertEquals(l, valid(url))
        }
    }

    @Test
    fun `every shape opens the same link`() {
        for (text in listOf(
            "https://monero.one/c#2:~:m1=$body",
            "https://monero.one/c#2$body",
            "HTTPS://MONERO.ONE/C#2$body",
            "monero.one/c#2:~:m1=$body",
            "monero.one/c#2$body",
            "  https://monero.one/c/#2:~:m1=$body\n"
        )) {
            assertEquals(text, link, valid(text))
            assertTrue(text, PaymentLinkCodec.isClaimLink(text))
        }
    }

    @Test
    fun `v1 links open in every shape`() {
        val words = ("cogs aloof wobbly island zigzags setup saxophone jockey request wise mighty border " +
            "different axes innocent subtly northern testing fidget lids absorb benches madness nocturnal aloof").split(" ")
        val v1 = PaymentLink(words, 150, 100_000_000_000, 1_791_000_000, "0123456789abcdef")
        assertTrue(v1.url().startsWith("https://monero.one/c#v=1&"))
        assertEquals(v1, valid(v1.url()))
        assertEquals(v1, valid(v1.url().removePrefix("https://")))
    }

    @Test
    fun `other directives are ignored and a missing body is incomplete`() {
        assertEquals(link, valid("https://monero.one/c#2:~:text=hello&m1=$body"))
        assertEquals(link, valid("https://monero.one/c#2:~:m1=$body&text=hello"))
        for (text in listOf(
            "https://monero.one/c#2:~:",
            "https://monero.one/c#2:~:m1=",
            "https://monero.one/c#2:~:text=$body",
            "https://monero.one/c#:~:m1=$body",
            "https://monero.one/c#22:~:m1=$body",
            "https://monero.one/c#2:~:m1=${body.dropLast(3)}",
            "https://monero.one/c#-:~:m1=$body",
            "https://monero.one/c",
            "https://monero.one/c#"
        )) {
            assertSame(text, PaymentLinkParse.Incomplete, PaymentLinkCodec.parse(text))
        }
        assertSame(PaymentLinkParse.NeedsUpdate, PaymentLinkCodec.parse("https://monero.one/c#3:~:m1=$body"))
        assertSame(PaymentLinkParse.NeedsUpdate, PaymentLinkCodec.parse("https://monero.one/c#3$body"))
    }

    @Test
    fun `other schemes and pages are not claim links`() {
        for (text in listOf(
            // The removed app-only shapes.
            "https://monero.one/a#2$body",
            "https://monero.one/a/#2$body",
            "monero.one/a#2$body",
            "moneroone://c#2$body",
            "MONEROONE://C#2$body",
            "moneroone://c/#2$body",
            "moneroone://c",
            "moneroone://",
            "moneroone://a#2$body",
            "https://monero.one/ab#2$body",
            "https://monero.one/a/b#2$body",
            "https://monero.one/u#2$body",
            "moneroone:c#2$body",
            "monero://c#2$body",
            "http://monero.one/c#2:~:m1=$body",
            "https://evil.one/c#2:~:m1=$body"
        )) {
            assertSame(text, PaymentLinkParse.NotALink, PaymentLinkCodec.parse(text))
            assertFalse(text, PaymentLinkCodec.isClaimLink(text))
        }
    }

    @Test
    fun `the sentence's period after the link is not part of it`() {
        // A chat app may take the full stop that follows the link in the share text.
        assertEquals(link, valid(link.url() + "."))
        assertEquals(link, valid("https://monero.one/c#2$body."))
        assertEquals(link, valid(link.url() + ")"))
    }

    @Test
    fun `the share text carries the https link`() {
        assertEquals(
            "I sent you 0.02 XMR. Tap to claim it in Monero One: https://monero.one/c#2:~:m1=$body",
            shareText("0.02", link)
        )
        // "monero.one" at the end of a sentence is no claim link.
        assertSame(PaymentLinkParse.NotALink, PaymentLinkCodec.parse("monero.one"))
    }

    @Test
    fun `only errors from before the broadcast skip the hidden attempt's scans`() {
        assertTrue(fundingFailedBeforeBroadcast("Create Transaction failed: not enough money"))
        assertTrue(fundingFailedBeforeBroadcast("Wallet is not connected yet. Wait for the wallet to reconnect and try again."))
        assertTrue(fundingFailedBeforeBroadcast("Wallet is not connected"))
        assertTrue(fundingFailedBeforeBroadcast("The wallet changed. Close this payment and start again."))
        assertTrue(fundingFailedBeforeBroadcast("A transaction is still in progress. Wait for it to finish before sending again."))
        assertFalse(fundingFailedBeforeBroadcast("Send Transaction failed: daemon is busy"))
        assertFalse(fundingFailedBeforeBroadcast("Transaction failed"))
        assertFalse(fundingFailedBeforeBroadcast(""))
    }

    @Test
    fun `a failed commit reads unclear and other link errors read as they are`() {
        val unclear = "The payment could not be confirmed. If it went through, the link appears in Activity."
        assertEquals(unclear, linkFundingUnconfirmed())
        assertEquals(unclear, linkFundingErrorText("Send Transaction failed: daemon is busy"))
        assertEquals(
            "Create Transaction failed: not enough money",
            linkFundingErrorText("Create Transaction failed: not enough money")
        )
        assertEquals(
            "Wallet is not connected yet. Wait for the wallet to reconnect and try again.",
            linkFundingErrorText("Wallet is not connected yet. Wait for the wallet to reconnect and try again.")
        )
    }
}
