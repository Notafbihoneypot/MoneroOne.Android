package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.Seed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.monero.moneroone.core.wallet.seed.SeedFormat
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator
import one.monero.moneroone.core.wallet.seed.SeedTestSupport
import one.monero.moneroone.core.wallet.seed.SeedTestSupport.hex
import one.monero.moneroone.core.wallet.seed.SeedTestSupport.unhex
import one.monero.moneroone.core.wallet.seed.SeedWordLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32

/**
 * 25-word seeds in every wallet2 language: the validator, then the kit
 * handoff ([SeedValidation.kitSeed]), then the key and address wallet2
 * reads. legacy-vectors.json holds one phrase per language from a wallet2
 * oracle (Python; word lists and rules from monero src/mnemonics, keys with
 * libsodium) and one phrase whose address wallet2 printed on an Android 14
 * emulator. The phrases carry the marked words of their language: German
 * capital umlauts, Spanish accents, Lojban apostrophes.
 */
class LegacyKitWordsTest {

    private class Vector(val lang: String, val source: String, val words: List<String>, val keyHex: String, val address: String)

    private val vectors: List<Vector> by lazy {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("legacy-vectors.json")).bufferedReader().readText()
        Json.parseToJsonElement(text).jsonArray.map { element ->
            val o = element.jsonObject
            fun field(name: String) = o.getValue(name).jsonPrimitive.content
            Vector(field("lang"), field("source"), field("phrase").split(" "), field("keyHex"), field("address"))
        }
    }

    private val german: Vector get() = vectors.single { it.lang == "German" }
    private val capitalUmlauts = listOf("Ästhetik", "Ökologie", "Ölberg", "Ölkrise", "Übermut")

    @Before
    fun setUp() = SeedTestSupport.init()

    /** What restoreNormalized stores for [typed]: the validator's restore words, lowercase. */
    private fun storedWords(typed: List<String>): List<String> {
        val verdict = SeedPhraseValidator.validate(SeedFormat.LEGACY, typed.map { it.lowercase(Locale.ROOT) })
        check(verdict is SeedPhraseValidator.Verdict.Valid) { "the validator rejected the phrase" }
        return verdict.restoreWords.map { it.lowercase(Locale.ROOT) }
    }

    private fun kitWords(stored: List<String>): List<String> =
        (SeedValidation.kitSeed(stored, SeedType.ELECTRUM_25) as Seed.Electrum).mnemonic

    @Test
    fun `the reference derivation matches known values`() {
        val ref = MoneroAddressReference
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", hex(ref.keccak256(ByteArray(0))))
        assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45", hex(ref.keccak256("abc".toByteArray())))
        // 135 bytes: both padding bits in one byte. 136 bytes: the padding is a block of its own.
        assertEquals("cbdfd9dee5faad3818d6b06f95a219fd290b0e1706f6a82e5a595b9ce9faca62", hex(ref.keccak256(ByteArray(135) { it.toByte() })))
        assertEquals("7ce759f1ab7f9ce437719970c26b0a66ff11fe3e38e17df89cf5d29c7d7f807e", hex(ref.keccak256(ByteArray(136) { it.toByte() })))
        assertEquals("5866666666666666666666666666666666666666666666666666666666666666", hex(ref.publicKey(ByteArray(32).also { it[0] = 1 })))
        assertEquals("c9a3f86aae465f0e56513864510f3997561fa2c9e85ea21dc2292309f3cd6022", hex(ref.publicKey(ByteArray(32).also { it[0] = 2 })))
        assertEquals("1c95988d7431ecd670cf7d73f45befc6feffffffffffffffffffffffffffff0f", hex(ref.scReduce32(ByteArray(32) { -1 })))
    }

    @Test
    fun `every language restores its address through the validator and the kit handoff`() {
        assertEquals(
            SeedWordLists.legacy.map { it.spec.englishName },
            vectors.filter { it.source == "oracle" }.map { it.lang }
        )
        for (v in vectors) {
            // What a restore stores (lowercase), and the same words in the list's case.
            for (stored in listOf(storedWords(v.words.map { it.lowercase(Locale.ROOT) }), v.words)) {
                val kit = kitWords(stored)
                assertEquals(v.lang, v.words, kit)
                assertTrue(v.lang, kit.all { Normalizer.isNormalized(it, Normalizer.Form.NFC) })
                // The handoff matches byte for byte: the same key whatever towlower does.
                val key = checkNotNull(wallet2Key(kit, IDENTITY)) { "${v.lang}: wallet2 would refuse the handoff" }
                assertEquals(v.lang, v.keyHex, hex(key))
                assertEquals(v.lang, v.keyHex, wallet2Key(kit, ASCII_ONLY)?.let { hex(it) })
                assertEquals(v.lang, v.keyHex, wallet2Key(kit, FULL)?.let { hex(it) })
                assertEquals(v.lang, v.address, MoneroAddressReference.primaryAddress(key))
            }
        }
    }

    @Test
    fun `German words with a capital umlaut reach wallet2 in the list's case`() {
        val v = german
        assertTrue(v.words.containsAll(capitalUmlauts))
        assertTrue(v.words.last() in capitalUmlauts) // the checksum word too
        val lowercase = v.words.map { it.lowercase(Locale.ROOT) }
        // Stored as before, lowercase, from either input.
        assertEquals(lowercase, storedWords(lowercase))
        assertEquals(lowercase, storedWords(v.words))
        // A towlower that maps only ASCII cannot read "ästhetik" as "Ästhetik"...
        assertNull(wallet2Key(lowercase, ASCII_ONLY))
        assertEquals(v.keyHex, wallet2Key(lowercase, FULL)?.let { hex(it) })
        // ...but reads the handoff, which has the list's case.
        for (stored in listOf(storedWords(lowercase), v.words)) {
            val kit = kitWords(stored)
            assertEquals(v.words, kit)
            assertEquals(v.keyHex, wallet2Key(kit, ASCII_ONLY)?.let { hex(it) })
        }
        assertEquals(v.address, MoneroAddressReference.primaryAddress(unhex(v.keyHex)))
    }

    @Test
    fun `rows stored by older builds reach wallet2 in list form and stay as stored`() {
        val v = german
        // Older builds stored the words as typed, lowercase; wallet2 reads "ästhe" as "Ästhetik" (prefix).
        val stored = v.words.map { it.lowercase(Locale.ROOT) }.toMutableList()
        stored[stored.indexOf("ästhetik")] = "ästhe"
        val before = stored.toList()
        val cacheId = WalletCacheIds.derivedWalletId(stored, 0)

        val kit = kitWords(stored)

        assertEquals(v.words, kit)
        assertEquals(before, stored)
        assertEquals(cacheId, WalletCacheIds.derivedWalletId(stored, 0))
        // Cache ids come from the stored words: the handoff words would give another id.
        assertNotEquals(cacheId, WalletCacheIds.derivedWalletId(kit, 0))
        assertEquals(v.keyHex, wallet2Key(kit, ASCII_ONLY)?.let { hex(it) })
    }

    @Test
    fun `words that do not decode go to the kit as stored`() {
        val lowercase = german.words.map { it.lowercase(Locale.ROOT) }
        val badChecksum = lowercase.toMutableList().apply { this[24] = this[0] }
        val unknownWord = lowercase.toMutableList().apply { this[3] = "xyzzy" }
        for (words in listOf(badChecksum, unknownWord, lowercase.take(24))) {
            assertEquals(words, SeedValidation.legacyKitWords(words))
        }
        assertNull(wallet2Key(badChecksum, FULL))
    }

    private companion object {
        val FULL: (Int) -> Int = { Character.toLowerCase(it) }
        val ASCII_ONLY: (Int) -> Int = { if (it in 'A'.code..'Z'.code) it + ('a' - 'A') else it }
        val IDENTITY: (Int) -> Int = { it }

        /**
         * wallet2's words_to_bytes (electrum-words.cpp: find_seed_language,
         * checksum_test, the word triples) over the bundled lists, with
         * [towlower] in place of the platform's towlower inside
         * WordHash/WordEqual. Returns the recovery key, or null where wallet2
         * refuses the words.
         */
        fun wallet2Key(words: List<String>, towlower: (Int) -> Int): ByteArray? {
            fun canonical(s: String) = buildString { s.codePoints().forEach { appendCodePoint(towlower(it)) } }
            fun prefix(s: String, n: Int) = if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))
            if (words.size != 25) return null
            for (language in SeedWordLists.legacy) {
                val n = language.prefixLength
                val trimmed = HashMap<String, Int>()
                val trimmedKey = HashMap<String, String>()
                language.words.forEachIndexed { index, word ->
                    val t = prefix(word, n)
                    trimmedKey.putIfAbsent(canonical(t), t)
                    trimmed[canonical(t)] = index
                }
                val indices = words.map { trimmed[canonical(prefix(it, n))] ?: -1 }
                if (indices.any { it < 0 }) continue
                val data = words.dropLast(1)
                val crc = CRC32().apply {
                    update(data.joinToString("") { trimmedKey.getValue(canonical(prefix(it, n))) }.toByteArray(Charsets.UTF_8))
                }.value
                // A language that fails the checksum is only wallet2's fallback, which fails the same test later.
                if (canonical(prefix(data[(crc % data.size).toInt()], n)) != canonical(prefix(words.last(), n))) continue
                val size = 1626L
                val key = ByteArray(32)
                for (t in 0 until 8) {
                    val w1 = indices[3 * t].toLong()
                    val w2 = indices[3 * t + 1].toLong()
                    val w3 = indices[3 * t + 2].toLong()
                    val value = (w1 + size * (((size - w1) + w2) % size) + size * size * (((size - w2) + w3) % size)) and 0xFFFFFFFFL
                    if (value % size != w1) return null
                    for (b in 0 until 4) key[4 * t + b] = (value ushr (8 * b)).toByte()
                }
                return key
            }
            return null
        }
    }
}
