package one.monero.moneroone.paymentlinks

import one.monero.moneroone.core.wallet.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The link shapes (privacy hardening, 2026-10-16): the https link puts the v2 body
 * behind the fragment directive `:~:m1=`; the app-only link is /a with a plain
 * fragment, which the site redirects to moneroone://c; the older shapes still open.
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
            "https://monero.one/a#2$body",
            "https://monero.one/a/#2$body",
            "monero.one/a#2$body",
            "moneroone://c#2$body",
            "MONEROONE://C#2$body",
            "moneroone://c/#2$body",
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
        assertTrue(v1.appUrl().startsWith("https://monero.one/a#v=1&"))
        assertEquals(v1, valid(v1.url()))
        assertEquals(v1, valid(v1.appUrl()))
        assertEquals(v1, valid(v1.appUrl().replace("https://monero.one/a", "moneroone://c")))
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
            "moneroone://c",
            "moneroone://c#"
        )) {
            assertSame(text, PaymentLinkParse.Incomplete, PaymentLinkCodec.parse(text))
        }
        assertSame(PaymentLinkParse.NeedsUpdate, PaymentLinkCodec.parse("https://monero.one/c#3:~:m1=$body"))
        assertSame(PaymentLinkParse.NeedsUpdate, PaymentLinkCodec.parse("moneroone://c#3$body"))
    }

    @Test
    fun `other schemes and pages are not claim links`() {
        for (text in listOf(
            "moneroone://",
            "moneroone://cx#2$body",
            "moneroone://u#2$body",
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
        assertEquals(link, valid(link.appUrl() + "."))
        assertEquals(link, valid("moneroone://c#2$body."))
        assertEquals(link, valid(link.url() + ")"))
    }

    @Test
    fun `the setting picks the shape, off by default`() {
        // The app-only link has a plain fragment: the site redirects /a before any script runs.
        assertEquals("https://monero.one/a#2$body", link.appUrl())
        val prefs = FakeSharedPreferences()
        assertFalse(LinkSharePrefs.appOnly(prefs))
        assertEquals(link.url(), link.url(LinkSharePrefs.appOnly(prefs)))
        LinkSharePrefs.setAppOnly(prefs, true)
        assertTrue(LinkSharePrefs.appOnly(prefs))
        assertEquals(link.appUrl(), link.url(LinkSharePrefs.appOnly(prefs)))
        LinkSharePrefs.setAppOnly(prefs, false)
        assertEquals(link.url(), link.url(LinkSharePrefs.appOnly(prefs)))
    }

    @Test
    fun `the share text names the app for an app-only link`() {
        assertEquals(
            "I sent you 0.02 XMR. Tap to claim it in Monero One: https://monero.one/c#2:~:m1=$body",
            shareText("0.02", link, appOnly = false)
        )
        val app = shareText("0.02", link, appOnly = true)
        assertEquals("I sent you 0.02 XMR. Open this link in Monero One: https://monero.one/a#2$body. Get the app at monero.one", app)
        // The link as a chat app would cut it from the sentence opens.
        val cut = app.substringAfter("Monero One: ").substringBefore(" Get the app")
        assertEquals(link, valid(cut))
        // "monero.one" at the end of the sentence is no claim link.
        assertSame(PaymentLinkParse.NotALink, PaymentLinkCodec.parse("monero.one"))
    }
}
