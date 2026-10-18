package one.monero.moneroone.core.wallet.seed

import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator.Status
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.Normalizer

class SeedPhraseValidatorTest {

    private val legacy = ("sequence atlas unveil summon pebbles tuesday beer rudely snake rockets different " +
        "fuselage woven tagged bested dented vegan hover rapid fawns obvious muppet randomly seasons randomly")
        .split(" ")
    private val en1 = "raven tail swear infant grief assist regular lamp duck valid someone little harsh puppy airport language"
        .split(" ")
    private val bip39 = List(23) { "abandon" } + "art"

    @Before
    fun setUp() = SeedTestSupport.init()

    private fun committed(n: Int) = List(n) { true }

    @Test
    fun `bip39 abandon art is valid, abandon x24 is not`() {
        assertTrue(SeedPhraseValidator.validate(SeedFormat.BIP39, bip39) is Verdict.Valid)
        assertEquals(Verdict.Invalid, SeedPhraseValidator.validate(SeedFormat.BIP39, List(24) { "abandon" }))
    }

    @Test
    fun `bip39 unique prefixes of 4 letters expand`() {
        val typed = List(23) { "aban" } + "art"
        val verdict = SeedPhraseValidator.validate(SeedFormat.BIP39, typed) as Verdict.Valid
        assertEquals(bip39, verdict.restoreWords)
        // "aba" is too short to expand, "abandonx" is not a word.
        val list = SeedPhraseValidator.activeList(SeedFormat.BIP39, listOf("abandon"))
        assertEquals(null, list.match("aba"))
        assertEquals(null, list.match("abandonx"))
    }

    @Test
    fun `bip39 spanish restore words are the kit's NFKD words`() {
        val spanish = Bip39Words.languages.single { it.kitLanguage.name == "Spanish" }
        // A valid Spanish phrase: the English abandon/art indices.
        val english = Bip39Words.languages.first()
        val indices = bip39.map { checkNotNull(english.match(it)) }
        val nfkd = indices.map { spanish.words[it] }
        val composed = nfkd.map { Normalizer.normalize(it, Normalizer.Form.NFC) }
        val stripped = nfkd.map { word -> word.filter { it.code < 0x80 } }
        for (typed in listOf(nfkd, composed, stripped)) {
            val verdict = SeedPhraseValidator.validate(SeedFormat.BIP39, typed) as Verdict.Valid
            assertEquals(nfkd, verdict.restoreWords)
            assertEquals(composed, verdict.displayWords)
        }
    }

    @Test
    fun `review shows progress while cells are empty`() {
        val cells = legacy.take(7) + List(18) { "" }
        val review = SeedPhraseValidator.review(SeedFormat.LEGACY, cells, committed(25), focused = null)
        assertEquals(Status.Progress(7, 25), review.status)
        assertTrue(review.invalidCells.isEmpty())
    }

    @Test
    fun `review marks committed unknown words only`() {
        val cells = legacy.toMutableList().apply { this[2] = "xyzzy"; this[6] = "qqq"; this[11] = "zzzz" }
        val all = SeedPhraseValidator.review(SeedFormat.LEGACY, cells, committed(25), focused = null)
        assertEquals(Status.CheckWords(listOf(3, 7, 12)), all.status)
        assertEquals(setOf(2, 6, 11), all.invalidCells)

        // The cell being typed (uncommitted, focused) is not marked yet.
        val typing = committed(25).toMutableList().apply { this[11] = false }
        val review = SeedPhraseValidator.review(SeedFormat.LEGACY, cells, typing, focused = 11)
        assertEquals(Status.CheckWords(listOf(3, 7)), review.status)
    }

    @Test
    fun `review of a full phrase`() {
        val valid = SeedPhraseValidator.review(SeedFormat.LEGACY, legacy, committed(25), focused = null)
        assertEquals(Status.Valid(null), valid.status)

        val swapped = legacy.toMutableList().apply { this[0] = legacy[1]; this[1] = legacy[0] }
        assertEquals(Status.InvalidPhrase, SeedPhraseValidator.review(SeedFormat.LEGACY, swapped, committed(25), null).status)

        // Last word still being typed as a prefix: valid already, with the full word.
        val prefix = legacy.toMutableList().apply { this[24] = "ran" }
        val typing = committed(25).toMutableList().apply { this[24] = false }
        val review = SeedPhraseValidator.review(SeedFormat.LEGACY, prefix, typing, focused = 24)
        assertEquals(Status.Valid(null), review.status)
        assertEquals(legacy, (review.verdict as Verdict.Valid).restoreWords)

        // Last word typed so far is not a word yet: still progress, not an error.
        val partial = legacy.toMutableList().apply { this[24] = "r" }
        assertEquals(Status.Progress(25, 25), SeedPhraseValidator.review(SeedFormat.LEGACY, partial, typing, focused = 24).status)
    }

    @Test
    fun `review of polyseed phrases`() {
        val review = SeedPhraseValidator.review(SeedFormat.POLYSEED, en1, committed(16), null)
        assertEquals(Status.Valid(1_638_397_746L), review.status)

        // Typed 4-letter prefixes: valid, canonical full words.
        val prefixes = en1.map { it.take(4) }
        val prefixReview = SeedPhraseValidator.review(SeedFormat.POLYSEED, prefixes, committed(16), null)
        assertEquals(Status.Valid(1_638_397_746L), prefixReview.status)
        assertEquals(en1, (prefixReview.verdict as Verdict.Valid).restoreWords)
    }

    @Test
    fun `hidden cells do not count`() {
        // 25 cells kept, 16 visible: only the first 16 are checked.
        val cells = en1 + List(9) { "xyzzy" }
        assertEquals(Status.Valid(1_638_397_746L), SeedPhraseValidator.review(SeedFormat.POLYSEED, cells, committed(25), null).status)
    }

    @Test
    fun `active list prefers the language most words match, then English`() {
        assertEquals("English", SeedPhraseValidator.activeList(SeedFormat.LEGACY, emptyList()).name)
        assertEquals("English", SeedPhraseValidator.activeList(SeedFormat.LEGACY, listOf("xyzzy")).name)
        assertEquals("Spanish", SeedPhraseValidator.activeList(SeedFormat.LEGACY, listOf("ábaco", "abdomen", "abeja")).name)
        assertEquals("English", SeedPhraseValidator.activeList(SeedFormat.POLYSEED, en1).name)
    }

    @Test
    fun `suggestions start with the word the rule reads`() {
        val english = SeedPhraseValidator.activeList(SeedFormat.LEGACY, emptyList())
        assertEquals(listOf("sequence"), SeedPhraseValidator.suggestions(english, "seq"))
        assertEquals(3, SeedPhraseValidator.suggestions(english, "s").size)
        assertTrue(SeedPhraseValidator.suggestions(english, "").isEmpty())
        val spanish = SeedPhraseValidator.lists(SeedFormat.LEGACY).single { it.name == "Spanish" }
        // Accent-blind: "abac" suggests "ábaco".
        assertEquals("ábaco", SeedPhraseValidator.suggestions(spanish, "abac").first())
    }

    @Test
    fun `canonical words on commit`() {
        val english = SeedPhraseValidator.activeList(SeedFormat.LEGACY, emptyList())
        assertEquals("sequence", SeedPhraseValidator.canonicalWord(english, "seq"))
        assertEquals("sequence", SeedPhraseValidator.canonicalWord(english, "seqxyz"))
        assertEquals(null, SeedPhraseValidator.canonicalWord(english, "xyzzy"))
        val german = SeedPhraseValidator.lists(SeedFormat.LEGACY).single { it.name == "German" }
        assertEquals("Abakus", SeedPhraseValidator.canonicalWord(german, "abakus"))
    }
}
