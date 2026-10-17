package one.monero.moneroone.paymentlinks

import io.horizontalsystems.monerokit.CakeWalletStyleConverter
import kotlinx.serialization.json.Json
import one.monero.moneroone.core.wallet.SeedValidation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Link format v2: the spec's two vectors (docs/2026-10-03-payment-links-spec.md,
 * "Link format v2"), Base58 edge cases and parse errors. The same checks as
 * iOS `PaymentLinkV2Tests`. The addresses here come from plain-Kotlin key
 * math ([MoneroKeyMath]); `PaymentLinkV2NativeTest` checks them through wallet2.
 */
class PaymentLinkV2Test {

    private class Vector(
        val secret: String,
        val height: Long,
        /** The shape before the fragment directive, as the spec's vectors give it: still parses. */
        val link: String,
        /** The https link made now: the body behind `:~:m1=`. */
        val url: String,
        val k: String,
        val spend: String,
        val address: String,
        val id: String
    )

    private val vector1 = Vector(
        secret = "000102030405060708090a0b0c0d0e0f",
        height = 150,
        link = "https://monero.one/c#2112drXXUifSrRnXLGbXg8E3b",
        url = "https://monero.one/c#2:~:m1=112drXXUifSrRnXLGbXg8E3b",
        k = "6a5bd15ceb3ca1dc24f7056feabfb1b825296bc5b26cfdcff8ad8362145ce908",
        spend = "6a5bd15ceb3ca1dc24f7056feabfb1b825296bc5b26cfdcff8ad8362145ce908",
        address = "49brhubHMBe7enmsCDmGAJi63rX3uaGutSTf5afWoCeyDWsaXDhXwuN9WYB19H9dZeRGaYeZ74YpqX4qKGCMcyGP9bv31Hz",
        id = "de4b02dc06db96f7"
    )

    /** k ≥ l: the reduction matters here. */
    private val vector2 = Vector(
        secret = "0102030405060708090a0b0c0d0e0f10",
        height = 3_500_000,
        link = "https://monero.one/c#218DfbjXLth7APvt3qQPgtfJwRq",
        url = "https://monero.one/c#2:~:m1=18DfbjXLth7APvt3qQPgtfJwRq",
        k = "ade11baac32c1fd6a72d6050d89f2aaced5462acadae8a6967b62cfdd0229d10",
        spend = "c00d264da9c90c7ed19068adf9a54b97ed5462acadae8a6967b62cfdd0229d00",
        address = "48vAo8XgJkBXtD3HqhVCvF9vpavqBuGK2WSPXTQSMeyXAy6xzEmgFHZiwkKYcnNv2fDsW9S31nnpHN6J3pMGrRiTR3NC7nL",
        id = "a6e2ad8e98af4322"
    )

    /** Vector 2 with the view key hashed from the unreduced k: a port must not get this. */
    private val vector2Wrong =
        "48vAo8XgJkBXtD3HqhVCvF9vpavqBuGK2WSPXTQSMeyXAx8P6Ap3hRX7Li7dnM4KsM6Bs7zNgVaF71rcS7ny1K1nNYDGj6Y"

    private fun bytes(hex: String): ByteArray = hexToBytes(hex)!!

    private fun valid(text: String): PaymentLink {
        val parsed = PaymentLinkCodec.parse(text)
        assertTrue("expected a valid link, got $parsed for $text", parsed is PaymentLinkParse.Valid)
        return (parsed as PaymentLinkParse.Valid).link
    }

    private fun checkVector(v: Vector) {
        val secret = bytes(v.secret)
        assertEquals(v.k, EnvelopeV2.k(secret).toHex())
        assertEquals(v.spend, EnvelopeV2.spendKey(secret).toHex())
        assertEquals(v.id, EnvelopeV2.id(secret))

        val link = PaymentLink.v2(secret, v.height)!!
        assertEquals(v.url, link.url())
        assertEquals(2, link.version)
        assertEquals(v.id, link.id)
        assertEquals(v.secret, link.secretHex)
        assertEquals(0L, link.amount)
        assertEquals(0L, link.expiry)

        // The words are wallet2's legacy words of the reduced key, with a good checksum.
        assertEquals(25, link.words.size)
        assertArrayEquals(bytes(v.spend), LegacyWords.decode(link.words))
        assertNull(SeedValidation.electrumSeedProblem(link.words))

        val parsed = valid(v.link)
        assertEquals(link, parsed)
        assertEquals(link, valid(v.url))
        assertEquals(v.height, parsed.height)
        assertEquals(link.words, parsed.words)

        // The address of the reduced spend key, with the view key wallet2 derives from it.
        val spend = bytes(v.spend)
        val address = MoneroKeyMath.address(MoneroKeyMath.publicKey(spend), MoneroKeyMath.publicKey(MoneroKeyMath.viewKey(spend)))
        assertEquals(v.address, address)
    }

    @Test
    fun `keccak256 matches the empty-input vector`() {
        assertEquals(
            "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            MoneroKeyMath.keccak256(ByteArray(0)).toHex()
        )
    }

    @Test
    fun `vector 1 gives the link, keys, id and address`() {
        checkVector(vector1)
        assertEquals(46, vector1.link.length)
    }

    @Test
    fun `vector 2 gives the link, keys, id and address`() {
        checkVector(vector2)
    }

    @Test
    fun `vector 2 reduces k, and hashing the unreduced k gives the wrong wallet`() {
        val secret = bytes(vector2.secret)
        val k = EnvelopeV2.k(secret)
        val spend = EnvelopeV2.spendKey(secret)
        assertFalse(k.contentEquals(spend))
        assertTrue(Ed25519Scalar.fromLittleEndian(k) >= Ed25519Scalar.L)

        // k·B equals sc_reduce32(k)·B, so only the view key can differ.
        assertArrayEquals(MoneroKeyMath.publicKey(spend), MoneroKeyMath.publicKey(k))
        val wrongView = MoneroKeyMath.viewKey(k)
        assertFalse(wrongView.contentEquals(MoneroKeyMath.viewKey(spend)))
        val wrong = MoneroKeyMath.address(MoneroKeyMath.publicKey(spend), MoneroKeyMath.publicKey(wrongView))
        assertEquals(vector2Wrong, wrong)
        assertNotEquals(vector2.address, wrong)

        // The link's words carry the reduced key, never k.
        val words = PaymentLink.v2(secret, vector2.height)!!.words
        assertArrayEquals(spend, LegacyWords.decode(words))
        assertNotEquals(LegacyWords.encode(k), words)
    }

    @Test
    fun `legacy words match the kit's own encoder`() {
        val encoder = CakeWalletStyleConverter::class.java
            .getDeclaredMethod("encodeMoneroMnemonic", ByteArray::class.java)
            .apply { isAccessible = true }
        val random = Random(7)
        repeat(50) {
            val key = Ed25519Scalar.reduce32(ByteArray(32).also(random::nextBytes))
            @Suppress("UNCHECKED_CAST")
            val kit = encoder.invoke(CakeWalletStyleConverter, key) as List<String>
            assertEquals(kit, LegacyWords.encode(key))
            assertArrayEquals(key, LegacyWords.decode(kit))
        }
    }

    @Test
    fun `base58 integers`() {
        assertEquals("1", Base58Integer.encode(0uL))
        assertEquals("z", Base58Integer.encode(57uL))
        assertEquals("21", Base58Integer.encode(58uL))
        assertEquals("3b", Base58Integer.encode(150uL))
        assertEquals("jpXCZedGfVQ", Base58Integer.encode(ULong.MAX_VALUE))
        assertEquals(ULong.MAX_VALUE, Base58Integer.decodeULong("jpXCZedGfVQ"))
        assertNull(Base58Integer.decodeULong("jpXCZedGfVR"))
        assertEquals(150uL, Base58Integer.decodeULong("1113b"))
        assertNull(Base58Integer.decodeULong(""))
        assertNull(Base58Integer.decodeULong("0"))
        assertNull(Base58Integer.decodeULong("O"))
        assertNull(Base58Integer.decodeULong("I"))
        assertNull(Base58Integer.decodeULong("l"))
    }

    @Test
    fun `base58 secrets`() {
        val max = ByteArray(16) { 0xff.toByte() }
        assertEquals("YcVfxkQb6JRzqk5kF2tNLv", Base58Integer.encode(max, 22))
        assertNull(Base58Integer.encode(max, 21))
        assertArrayEquals(max, Base58Integer.decode("YcVfxkQb6JRzqk5kF2tNLv", 16))
        // One past 2^128 - 1 does not fit 16 bytes.
        assertNull(Base58Integer.decode("YcVfxkQb6JRzqk5kF2tNLw", 16))

        val zero = ByteArray(16)
        assertEquals("1".repeat(22), Base58Integer.encode(zero, 22))
        assertArrayEquals(zero, Base58Integer.decode("1".repeat(22), 16))

        val random = Random(58)
        repeat(200) {
            val secret = ByteArray(16).also(random::nextBytes)
            val text = Base58Integer.encode(secret, 22)!!
            assertEquals(22, text.length)
            assertArrayEquals(secret, Base58Integer.decode(text, 16))
        }
    }

    @Test
    fun `random links round-trip`() {
        val random = Random(2)
        repeat(50) {
            val secret = ByteArray(16).also(random::nextBytes)
            val height = random.nextInt(4_000_000).toLong()
            val link = PaymentLink.v2(secret, height)!!
            val parsed = valid(link.url())
            assertEquals(link, parsed)
            assertTrue(link.url().length <= "https://monero.one/c#2:~:m1=".length + 22 + 11)
        }
        // A new secret makes a new link.
        assertNotEquals(EnvelopeV2.newSecret().toHex(), EnvelopeV2.newSecret().toHex())
    }

    @Test
    fun `a link without a scheme and leading 1s in the height parse`() {
        val body = vector1.link.removePrefix("https://")
        assertEquals(vector1.height, valid(body).height)
        val padded = vector1.link.removeSuffix("3b") + "1113b"
        assertEquals(vector1.height, valid(padded).height)
        // UInt64.max does not fit the Android record's Long: incomplete.
        val prefix = vector1.link.substring(0, "https://monero.one/c#2".length + 22)
        assertSame(PaymentLinkParse.Incomplete, PaymentLinkCodec.parse(prefix + "jpXCZedGfVQ"))
        assertEquals(Long.MAX_VALUE, valid(prefix + Base58Integer.encode(Long.MAX_VALUE.toULong())).height)
    }

    @Test
    fun `bad short links are incomplete`() {
        val link = vector1.link
        val prefix22 = link.substring(0, "https://monero.one/c#2".length + 22)
        val bad = listOf(
            prefix22,
            link.replace("112d", "102d"),
            link.replace("rXX", "rOX"),
            link + "l",
            "https://monero.one/c#2" + "z".repeat(22) + "1",
            prefix22 + "2".repeat(12),
            prefix22 + "zzzzzzzzzzz",
            "https://monero.one/c#2",
            "https://monero.one/c#-",
            "https://monero.one/c#"
        )
        for (text in bad) assertSame(text, PaymentLinkParse.Incomplete, PaymentLinkCodec.parse(text))
        assertSame(PaymentLinkParse.NotALink, PaymentLinkCodec.parse(link.replace("https", "http")))
    }

    @Test
    fun `another version character asks for an update`() {
        for (version in listOf("3", "a", "Z")) {
            val text = vector1.link.replace("c#2", "c#$version")
            assertSame(text, PaymentLinkParse.NeedsUpdate, PaymentLinkCodec.parse(text))
        }
    }

    @Test
    fun `v1 links still parse`() {
        val words = ("cogs aloof wobbly island zigzags setup saxophone jockey request wise mighty border " +
            "different axes innocent subtly northern testing fidget lids absorb benches madness nocturnal aloof").split(" ")
        val v1 = PaymentLink(words, 150, 100_000_000_000, 1_791_000_000, "0123456789abcdef")
        val parsed = valid(v1.url())
        assertEquals(1, parsed.version)
        assertNull(parsed.secret)
        assertEquals(v1, parsed)
    }

    @Test
    fun `the secret never appears in toString`() {
        val link = PaymentLink.v2(bytes(vector2.secret), vector2.height)!!
        val text = link.toString()
        assertFalse(text.contains(vector2.secret))
        assertFalse(text.contains(link.url().substringAfter('#')))
        for (word in link.words) assertFalse(text.contains(" $word "))
    }

    @Test
    fun `records keep the v2 secret and rebuild the link`() {
        val json = Json { ignoreUnknownKeys = true }
        val link = PaymentLink.v2(bytes(vector2.secret), vector2.height)!!
        val sent = SentLink(
            id = link.id, height = link.height, amount = 5_000_000_000, expiry = 1_800_000_000,
            createdAtMillis = 1L, walletId = "w", envelopeAddress = vector2.address, feeReserve = 1,
            state = SentState.FUNDED, secret = link.secretHex
        )
        val back = json.decodeFromString(SentLink.serializer(), json.encodeToString(SentLink.serializer(), sent))
        assertEquals(sent, back)
        assertEquals(vector2.url, back.link.url())
        assertTrue(back.words.isEmpty())
        assertFalse(back.toString().contains(vector2.secret))

        val received = ReceivedLink(link.id, null, link.height, 0, 0, 1L, secret = link.secretHex)
        val receivedBack = json.decodeFromString(ReceivedLink.serializer(), json.encodeToString(ReceivedLink.serializer(), received))
        assertEquals(vector2.url, receivedBack.link?.url())
        assertNull(receivedBack.knownAmount)
        assertNull(receivedBack.forgettingSecret().link)
        assertFalse(receivedBack.toString().contains(vector2.secret))
    }

    @Test
    fun `the claim drops a v2 secret once mined`() {
        val link = PaymentLink.v2(bytes(vector1.secret), vector1.height)!!
        val received = ReceivedLink(
            link.id, null, link.height, 0, 0, 1L, state = ReceivedState.CLAIMED, claimTxid = "tx",
            secret = link.secretHex
        )
        assertNotNull(received.link)
        assertNull(received.forgettingSecret().secret)
    }
}
