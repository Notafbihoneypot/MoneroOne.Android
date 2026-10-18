package one.monero.moneroone.core.wallet.seed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator.Verdict
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Random

/** wallet2 25-word rules (electrum-words.cpp port) and the bundled lists. */
class LegacyMnemonicTest {

    private val valid = ("sequence atlas unveil summon pebbles tuesday beer rudely snake rockets different " +
        "fuselage woven tagged bested dented vegan hover rapid fawns obvious muppet randomly seasons randomly")
        .split(" ")

    @Before
    fun setUp() = SeedTestSupport.init()

    @Test
    fun `spec phrase is valid English`() {
        val decoded = LegacyMnemonic.decode(valid)
        assertNull(decoded.problem)
        assertEquals("English", decoded.language?.spec?.englishName)
        val verdict = SeedPhraseValidator.validate(SeedFormat.LEGACY, valid)
        assertTrue(verdict is Verdict.Valid)
        assertEquals(valid, (verdict as Verdict.Valid).restoreWords)
    }

    @Test
    fun `three letter prefixes are valid and canonicalize to the full words`() {
        val prefixes = valid.map { it.take(3) }
        assertNull(LegacyMnemonic.decode(prefixes).problem)
        val verdict = SeedPhraseValidator.validate(SeedFormat.LEGACY, prefixes) as Verdict.Valid
        assertEquals(valid, verdict.displayWords)
        assertEquals(valid, verdict.restoreWords)
        // Same key as the full words.
        assertArrayEquals(LegacyMnemonic.decode(valid).key, LegacyMnemonic.decode(prefixes).key)
    }

    @Test
    fun `wallet2 reads only the prefix, in any case`() {
        val typo = valid.toMutableList().apply { this[0] = "SEQxyz" }
        assertNull(LegacyMnemonic.decode(typo).problem)
    }

    @Test
    fun `swapping words 1 and 2 fails the checksum`() {
        val swapped = valid.toMutableList().apply { this[0] = valid[1]; this[1] = valid[0] }
        assertEquals(LegacyMnemonic.Problem.CHECKSUM, LegacyMnemonic.decode(swapped).problem)
        assertEquals(Verdict.Invalid, SeedPhraseValidator.validate(SeedFormat.LEGACY, swapped))
    }

    @Test
    fun `unknown word 5 is reported`() {
        val words = valid.toMutableList().apply { this[4] = "xyzzy" }
        assertEquals(LegacyMnemonic.Problem.UNKNOWN_WORDS, LegacyMnemonic.decode(words).problem)
        assertEquals(Verdict.UnknownWords(listOf(4)), SeedPhraseValidator.validate(SeedFormat.LEGACY, words))
    }

    @Test
    fun `wrong word count`() {
        assertEquals(LegacyMnemonic.Problem.WORD_COUNT, LegacyMnemonic.decode(valid.dropLast(1)).problem)
        assertEquals(Verdict.WrongCount, SeedPhraseValidator.validate(SeedFormat.LEGACY, valid.dropLast(1)))
    }

    @Test
    fun `every language round-trips random keys and is detected`() {
        val random = Random(20261001)
        for (language in SeedWordLists.legacy) {
            var checked = 0
            while (checked < 25) {
                val key = ByteArray(32).also(random::nextBytes)
                val words = LegacyMnemonic.bytesToWords(key, language)
                assertEquals(25, words.size)
                // English (old) has duplicate prefixes; wallet2 reads the later word for those
                // (trimmed_word_map), so such phrases do not round-trip in wallet2 either.
                if (language.spec.allowDuplicatePrefixes && words.any { language.indexOf(it) != language.exactIndexOf(it) }) continue
                checked++
                val decoded = LegacyMnemonic.decode(words)
                assertNull("$language: ${decoded.problem}", decoded.problem)
                assertEquals(language.spec.englishName, decoded.language?.spec?.englishName)
                assertArrayEquals(key, decoded.key)
                // The validator agrees, and keeps the list words.
                val verdict = SeedPhraseValidator.validate(SeedFormat.LEGACY, words)
                assertTrue("$language: $verdict", verdict is Verdict.Valid)
                assertEquals(
                    words.map { it.lowercase(java.util.Locale.ROOT) },
                    (verdict as Verdict.Valid).restoreWords.map { it.lowercase(java.util.Locale.ROOT) }
                )
            }
        }
    }

    @Test
    fun `an overflowing triple is invalid even with a good checksum`() {
        val english = SeedWordLists.englishLegacy
        val n = LegacyMnemonic.LIST_SIZE.toLong()
        var triple: IntArray? = null
        loop@ for (w1 in 0 until 1626) for (w2 in 0 until 1626) for (w3 in 1600 until 1626) {
            val exact = w1 + n * (((n - w1) + w2) % n) + n * n * (((n - w2) + w3) % n)
            if (exact > 0xFFFFFFFFL && (exact and 0xFFFFFFFFL) % n != w1.toLong()) {
                triple = intArrayOf(w1, w2, w3)
                break@loop
            }
        }
        assertNotNull(triple)
        val words = valid.take(24).toMutableList()
        for (i in 0 until 3) words[i] = english.words[triple!![i]]
        val index = checkNotNull(LegacyMnemonic.createChecksumIndex(words, english))
        words += words[index]
        assertEquals(LegacyMnemonic.Problem.TRIPLE, LegacyMnemonic.decode(words).problem)
        assertEquals(Verdict.Invalid, SeedPhraseValidator.validate(SeedFormat.LEGACY, words))
    }

    @Test
    fun `the encoder matches the BIP39 conversion golden vectors`() {
        // Bip39ConversionGoldenTest: spend key -> wallet2 English words.
        val vectors = mapOf(
            "4fe2e8fa6ad56846a4b70b5cf85a8a5ff310d8eb5daaf5b11af9591d79fc0a02" to
                "coal gourmet geometry raking lilac sewage pawnshop rudely bays ascend gifts reinvest " +
                "voted moisture kept podcast vocal paradise acidic espionage hijack wrap vogue waist sewage",
            "076bfda9fa30dba503321e5f6f310b356cb616bbbfc64fd086a65ad91fc30402" to
                "inbound bite sighting mighty academy phone pool erosion oxidant went natural shackles " +
                "juicy pipeline hotel gang divers adrenalin iris pumpkins mice bowling venomous village sighting",
            "c7a25a46826fc6d2228471fd9f92db0d8afd1d5aa93e0cce2c8d12042adcbb0c" to
                "jukebox unmask cycling assorted nouns iguana focus nimbly necklace atrium acquire army " +
                "washing stick comb fainted snout obliged awkward vampire village sonic oilfield pheasants stick",
            "962f0a6feb6a681827cfd33508d9ef8fbef9f274963511d13a4b0eebd52e3a01" to
                "peeled examine razor sack ourselves rims soya hesitate nowhere bamboo koala alarms " +
                "rapid sushi fall peeled gambit cadets losing because adept cocoa vocal vowels alarms"
        )
        for ((key, phrase) in vectors) {
            assertEquals(phrase, LegacyMnemonic.bytesToWords(SeedTestSupport.unhex(key), SeedWordLists.englishLegacy).joinToString(" "))
        }
    }

    @Test
    fun `lowercase keys are unique in every list`() {
        for (language in SeedWordLists.legacy) {
            val keys = language.words.map { LegacyMnemonic.wordKey(it) }
            assertEquals("$language full words", keys.size, keys.toSet().size)
            if (!language.spec.allowDuplicatePrefixes) {
                val prefixes = language.words.map { LegacyMnemonic.wordKey(LegacyMnemonic.utf8Prefix(it, language.prefixLength)) }
                assertEquals("$language prefixes", prefixes.size, prefixes.toSet().size)
            }
            language.words.forEachIndexed { i, word -> assertEquals(i, language.exactIndexOf(word)) }
        }
    }

    @Test
    fun `manifest matches the hardcoded language specs`() {
        val manifest = Json.parseToJsonElement(File(SeedTestSupport.assetDir, "manifest.json").readText()).jsonObject
        val legacy = manifest.getValue("legacy").jsonArray.map { it.jsonObject }
        assertEquals(LegacyMnemonic.SPECS.size, legacy.size)
        for (entry in legacy) {
            val file = entry.getValue("file").jsonPrimitive.content
            val spec = LegacyMnemonic.SPECS.single { it.file == file }
            assertEquals(entry.getValue("englishName").jsonPrimitive.content, spec.englishName)
            assertEquals(entry.getValue("uniquePrefixLength").jsonPrimitive.int, spec.prefixLength)
            assertEquals(1626, entry.getValue("count").jsonPrimitive.int)
        }
        val polyseed = manifest.getValue("polyseed").jsonArray.map { it.jsonObject }
        assertEquals(Polyseed.SPECS.size, polyseed.size)
        for (entry in polyseed) {
            val file = entry.getValue("file").jsonPrimitive.content
            val spec = Polyseed.SPECS.single { it.file == file }
            assertEquals(entry.getValue("englishName").jsonPrimitive.content, spec.englishName)
            val separator = entry.getValue("separator").jsonPrimitive.content.replace("\\u3000", "　")
            assertEquals(separator, spec.separator)
            assertEquals(entry.getValue("isSorted").jsonPrimitive.boolean, spec.isSorted)
            assertEquals(entry.getValue("hasPrefix").jsonPrimitive.boolean, spec.hasPrefix)
            assertEquals(entry.getValue("hasAccents").jsonPrimitive.boolean, spec.hasAccents)
            assertEquals(entry.getValue("compose").jsonPrimitive.boolean, spec.compose)
            assertEquals(2048, entry.getValue("count").jsonPrimitive.int)
        }
    }
}
