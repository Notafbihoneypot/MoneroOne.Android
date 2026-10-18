package one.monero.moneroone.ui.screens.onboarding

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.monero.moneroone.core.wallet.seed.SeedFormat
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator.Status
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator.Verdict
import one.monero.moneroone.core.wallet.seed.SeedTestSupport
import one.monero.moneroone.ui.screens.onboarding.SeedEntryState.Effect
import one.monero.moneroone.ui.screens.onboarding.SeedEntryState.Focus
import one.monero.moneroone.ui.screens.onboarding.SeedEntryState.Scroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The seed step's cell rules (seed-entry spec 2.2 to 2.5) without the UI. */
class SeedEntryStateTest {

    private val legacy = ("sequence atlas unveil summon pebbles tuesday beer rudely snake rockets different " +
        "fuselage woven tagged bested dented vegan hover rapid fawns obvious muppet randomly seasons randomly").split(" ")
    private val polyseed = "raven tail swear infant grief assist regular lamp duck valid someone little harsh puppy airport language"
        .split(" ")
    private val bip39 = List(23) { "abandon" } + "art"

    private lateinit var entry: SeedEntryState

    @Before
    fun setUp() {
        SeedTestSupport.init()
        entry = SeedEntryState().apply { listsReady = true }
    }

    private fun cells() = List(entry.wordCount) { entry.cell(it).text }

    private fun type(index: Int, text: String): Effect =
        entry.onValueChange(index, TextFieldValue(text, TextRange(text.length)))

    @Test
    fun `paste button fills each format and clears the clipboard`() {
        for ((words, format) in listOf(legacy to SeedFormat.LEGACY, bip39 to SeedFormat.BIP39, polyseed to SeedFormat.POLYSEED)) {
            entry.clearAll()
            val effect = entry.paste("  " + words.joinToString(",\n ") + "\n")
            assertEquals(Effect(Focus.Hide, clearClipboard = true, scroll = Scroll.Status), effect)
            assertEquals(format, entry.format)
            assertEquals(words, cells())
            val valid = checkNotNull(entry.validPhrase)
            assertEquals(format, valid.format)
            assertEquals(words, valid.displayWords)
        }
        // The Polyseed status carries its birthday for "Wallet created around".
        assertEquals(Status.Valid(1_638_397_746L), entry.review.status)
    }

    @Test
    fun `paste with an unsupported count shows the error and keeps the cells`() {
        entry.paste(legacy.joinToString(" "))
        val effect = entry.paste(legacy.take(13).joinToString(" "))
        assertTrue(entry.showsCountError)
        // Still cleared: the clipboard held words.
        assertEquals(Effect(Focus.Keep, clearClipboard = true, scroll = Scroll.Status), effect)
        assertEquals(legacy, cells())
        // The next edit clears the error.
        type(0, "seq")
        assertFalse(entry.showsCountError)
        // One word is no seed either, and the clipboard is left alone.
        assertEquals(Effect(scroll = Scroll.Status), entry.paste("sequence"))
        assertTrue(entry.showsCountError)
        // An empty clipboard changes nothing.
        type(0, "se")
        assertFalse(entry.showsCountError)
        assertEquals(Effect.None, entry.paste(" \n"))
        assertFalse(entry.showsCountError)
        assertEquals("se", entry.cell(0).text)
    }

    @Test
    fun `typing a prefix and a space commits the list word and moves on`() {
        entry.onFocusChanged(0, true)
        assertEquals(Effect.None, type(0, "SEQ"))
        assertEquals("seq", entry.cell(0).text)
        assertEquals(TextRange(3), entry.cell(0).selection)
        assertEquals(Effect(Focus.Cell(1)), type(0, "seq "))
        assertEquals("sequence", entry.cell(0).text)
        // Return/Next commits too.
        entry.onFocusChanged(0, false)
        entry.onFocusChanged(1, true)
        type(1, "atl")
        assertEquals(Effect(Focus.Cell(2)), entry.commit(1))
        assertEquals("atlas", entry.cell(1).text)
        // A space alone in an empty cell is dropped.
        assertEquals(Effect.None, type(2, " "))
        assertEquals("", entry.cell(2).text)
    }

    @Test
    fun `a commit that makes the phrase valid closes the keyboard`() {
        entry.paste(legacy.joinToString(" "))
        entry.onFocusChanged(2, true)
        type(2, "")
        // Still invalid: the next cell gets focus.
        assertEquals(Effect(Focus.Cell(3)), type(2, "zzz "))
        // The fix makes the phrase valid: the keyboard closes, so Continue shows.
        type(2, "")
        assertEquals(Effect(Focus.Hide, scroll = Scroll.Status), type(2, "unv "))
        assertEquals(legacy, checkNotNull(entry.validPhrase).restoreWords)
        // Next/Done and a suggestion do the same.
        type(2, "unv")
        assertEquals(Effect(Focus.Hide, scroll = Scroll.Status), entry.commit(2))
        type(2, "unv")
        assertEquals(Effect(Focus.Hide, scroll = Scroll.Status), entry.acceptSuggestion("unveil"))
        // And words pasted into a cell that complete the phrase.
        type(22, "")
        type(23, "")
        assertEquals(Effect(Focus.Cell(22)), entry.commit(21))
        assertEquals(Effect(Focus.Hide, clearClipboard = true, scroll = Scroll.Status), entry.onValueChange(22, TextFieldValue("randomly seasons")))
        assertEquals(legacy, cells())
    }

    @Test
    fun `prefix-typed phrases give the full words to the restore`() {
        legacy.forEachIndexed { i, word -> type(i, word.take(3) + " ") }
        assertEquals(legacy, checkNotNull(entry.validPhrase).restoreWords)
        assertEquals(legacy, cells())

        entry.clearAll()
        entry.setFormat(SeedFormat.POLYSEED)
        polyseed.forEachIndexed { i, word -> type(i, word.take(4).uppercase() + "\n") }
        val valid = checkNotNull(entry.validPhrase)
        assertEquals(polyseed, valid.restoreWords)
        assertEquals(1_638_397_746L, valid.polyseedBirthdayUnix)
    }

    @Test
    fun `a wrong word is flagged once committed, never while typed`() {
        entry.onFocusChanged(0, true)
        type(0, "zzz")
        assertEquals(Status.Progress(1, 25), entry.review.status)
        assertTrue(entry.review.invalidCells.isEmpty())
        type(0, "zzz ")
        assertEquals(Status.CheckWords(listOf(1)), entry.review.status)
        assertEquals(setOf(0), entry.review.invalidCells)
        assertNull(entry.validPhrase)
        // Fixed: the flag goes away.
        type(0, "")
        type(0, "seq")
        assertTrue(entry.review.invalidCells.isEmpty())
    }

    @Test
    fun `checksum failures read as an invalid phrase`() {
        entry.paste((legacy.dropLast(1) + "sequence").joinToString(" "))
        assertEquals(Status.InvalidPhrase, entry.review.status)
        assertNull(entry.validPhrase)

        entry.paste(List(24) { "abandon" }.joinToString(" "))
        assertEquals(SeedFormat.BIP39, entry.format)
        assertEquals(Status.InvalidPhrase, entry.review.status)

        val swapped = polyseed.toMutableList().apply { this[5] = "grief" }
        entry.paste(swapped.joinToString(" "))
        assertEquals(Status.InvalidPhrase, entry.review.status)
    }

    @Test
    fun `an encrypted polyseed asks for no passphrase restore`() {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("polyseed-vectors.json")).bufferedReader().readText()
        val encrypted = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "encrypted_en" }.getValue("phrase").jsonPrimitive.content
        entry.paste(encrypted)
        assertEquals(Status.Passphrase, entry.review.status)
        assertNull(entry.validPhrase)
    }

    @Test
    fun `suggestions follow the focused cell and accepting one moves on`() {
        entry.onFocusChanged(0, true)
        assertEquals(emptyList<String>(), entry.suggestions)
        type(0, "ab")
        val suggestions = entry.suggestions
        assertTrue(suggestions.size in 1..3)
        assertTrue(suggestions.all { it.startsWith("ab") })
        assertEquals(Effect(Focus.Cell(1)), entry.acceptSuggestion(suggestions[1]))
        assertEquals(suggestions[1], entry.cell(0).text)
        // No focused cell: no suggestions and nothing to accept.
        entry.onFocusChanged(0, false)
        assertEquals(emptyList<String>(), entry.suggestions)
        assertEquals(Effect.None, entry.acceptSuggestion("abbey"))
    }

    @Test
    fun `backspace in an empty cell moves back once`() {
        type(0, "seq ")
        entry.onFocusChanged(1, true)
        assertEquals(Effect(Focus.Cell(0)), entry.deleteBackwardInEmptyCell(1))
        assertEquals(0, entry.focusedIndex)
        assertEquals(TextRange("sequence".length), entry.cell(0).selection)
        // The same key press reported again does nothing.
        assertEquals(Effect.None, entry.deleteBackwardInEmptyCell(1))
        // The first cell and cells with text do not move.
        assertEquals(Effect.None, entry.deleteBackwardInEmptyCell(0))
        entry.onFocusChanged(0, false)
        entry.onFocusChanged(1, true)
        type(1, "a")
        assertEquals(Effect.None, entry.deleteBackwardInEmptyCell(1))
    }

    @Test
    fun `switching the count clears every word and focuses word 1`() {
        // 25 words, word 3 not in the list: an invalid mark.
        entry.paste(legacy.toMutableList().apply { this[2] = "zzz" }.joinToString(" "))
        // Word 6 being typed: suggestions.
        entry.onFocusChanged(5, true)
        type(5, "ab")
        assertTrue(entry.suggestions.isNotEmpty())
        // A paste with an unsupported count: the count error.
        entry.paste(legacy.take(13).joinToString(" "))
        assertTrue(entry.showsCountError)
        assertEquals(setOf(2), entry.review.invalidCells)

        assertEquals(Effect(Focus.Cell(0)), entry.setFormat(SeedFormat.POLYSEED))
        assertEquals(SeedFormat.POLYSEED, entry.format)
        // Words 17 to 25 are hidden at 16 words and are cleared too.
        assertEquals(List(SeedEntryState.CELL_COUNT) { "" }, List(SeedEntryState.CELL_COUNT) { entry.cell(it).text })
        assertFalse(entry.hasText)
        assertTrue(entry.review.invalidCells.isEmpty())
        assertEquals(Status.Progress(0, 16), entry.review.status)
        assertFalse(entry.showsCountError)
        assertEquals(emptyList<String>(), entry.suggestions)
        assertNull(entry.validPhrase)

        // No focused cell: the cells clear and the keyboard stays down.
        entry.onFocusChanged(5, false)
        entry.paste(polyseed.joinToString(" "))
        assertEquals(Effect.None, entry.setFormat(SeedFormat.LEGACY))
        assertEquals(List(25) { "" }, cells())
        assertEquals(Status.Progress(0, 25), entry.review.status)
    }

    @Test
    fun `tapping the selected count changes nothing`() {
        entry.paste(legacy.joinToString(" "))
        entry.onFocusChanged(3, true)
        entry.paste(legacy.take(13).joinToString(" "))
        assertEquals(Effect.None, entry.setFormat(SeedFormat.LEGACY))
        assertEquals(legacy, cells())
        assertTrue(entry.showsCountError)
        assertEquals(3, entry.focusedIndex)
    }

    @Test
    fun `a paste that fits another count sets it and keeps the pasted words`() {
        // Paste button, 25 words to 16, over a word being typed.
        entry.onFocusChanged(0, true)
        type(0, "seq")
        assertEquals(Effect(Focus.Hide, clearClipboard = true, scroll = Scroll.Status), entry.paste(polyseed.joinToString(" ")))
        assertEquals(SeedFormat.POLYSEED, entry.format)
        assertEquals(polyseed, cells())
        assertEquals(polyseed, checkNotNull(entry.validPhrase).restoreWords)

        // Paste button, 16 words to 25.
        entry.paste(legacy.joinToString(" "))
        assertEquals(SeedFormat.LEGACY, entry.format)
        assertEquals(legacy, cells())
        assertEquals(legacy, checkNotNull(entry.validPhrase).restoreWords)

        // Pasted into a cell, 25 words to 24.
        entry.clearAll()
        val effect = entry.onValueChange(0, TextFieldValue(bip39.joinToString(" ")))
        assertEquals(Effect(Focus.Hide, clearClipboard = true, scroll = Scroll.Status), effect)
        assertEquals(SeedFormat.BIP39, entry.format)
        assertEquals(bip39, cells())
        assertEquals(bip39, checkNotNull(entry.validPhrase).restoreWords)
    }

    @Test
    fun `pasting into a cell fills the grid like the paste button`() {
        entry.onFocusChanged(0, true)
        val effect = entry.onValueChange(0, TextFieldValue(legacy.joinToString(" ")))
        assertEquals(Effect(Focus.Hide, clearClipboard = true, scroll = Scroll.Status), effect)
        assertEquals(legacy, cells())

        // A few words fill forward from the cell.
        entry.clearAll()
        entry.onFocusChanged(5, true)
        val few = entry.onValueChange(5, TextFieldValue("sequence atlas unveil"))
        assertEquals(Effect(Focus.Cell(8), clearClipboard = true), few)
        assertEquals(listOf("sequence", "atlas", "unveil"), (5..7).map { entry.cell(it).text })

        // Too many to fit from cell 23: the count error.
        val tooMany = entry.onValueChange(23, TextFieldValue("sequence atlas unveil"))
        assertTrue(entry.showsCountError)
        assertTrue(tooMany.clearClipboard)
    }

    @Test
    fun `an IME composition is not rewritten until gesture typing spills a word`() {
        entry.onFocusChanged(0, true)
        val composing = TextFieldValue("SEQ", TextRange(3), composition = TextRange(0, 3))
        assertEquals(Effect.None, entry.onValueChange(0, composing))
        assertEquals(composing, entry.cell(0))

        // "sequence " committed and "atl" composing in one batch.
        val spilled = entry.onValueChange(0, TextFieldValue("sequence atl", TextRange(12), composition = TextRange(9, 12)))
        assertEquals(Effect(Focus.Cell(1)), spilled)
        assertEquals("sequence", entry.cell(0).text)
        assertEquals("atl", entry.cell(1).text)
    }

    @Test
    fun `clear and wipe drop every word`() {
        entry.paste(legacy.joinToString(" "))
        assertTrue(entry.hasText)
        // Clear goes back to the top, to word 1 and Paste.
        assertEquals(Effect(scroll = Scroll.Top), entry.clearAll())
        assertFalse(entry.hasText)
        entry.paste(polyseed.joinToString(" "))
        entry.onFocusChanged(3, true)
        entry.wipe()
        assertFalse(entry.hasText)
        assertEquals(SeedFormat.LEGACY, entry.format)
        assertNull(entry.focusedIndex)
        assertNull(entry.validPhrase)
    }

    @Test
    fun `cells are not checked before the lists load`() {
        val loading = SeedEntryState()
        loading.paste(legacy.joinToString(" "))
        assertEquals(Status.Progress(25, 25), loading.review.status)
        assertNull(loading.validPhrase)
        loading.listsReady = true
        assertEquals(legacy, checkNotNull(loading.validPhrase).restoreWords)
    }

    @Test
    fun `inserted text is the part between the common prefix and suffix`() {
        assertEquals("XY", SeedEntryState.insertedText("abc", "abXYc"))
        assertEquals("a b", SeedEntryState.insertedText("", "a b"))
        assertEquals("", SeedEntryState.insertedText("same", "same"))
        assertEquals(" atlas unveil", SeedEntryState.insertedText("sequence", "sequence atlas unveil"))
        assertEquals(" ", SeedEntryState.insertedText("aa", "a a"))
    }

    @Test
    fun `lojban words keep their apostrophes when typed`() {
        val lojban = ("ci'izra ponbau xebni brano zi'evla rinsa dirgo fu'ivla zvafa'i rirxe xango calse stedu " +
            "xrula stedu xarci mamta xasli jbota'a tersu'i vokta'a crezenzu'e mirli xirma fu'ivla").split(" ")
        for ((i, word) in lojban.withIndex()) {
            entry.onFocusChanged(i, true)
            // Letter by letter (the apostrophe from the keyboard's symbol page), then a space commits.
            for (end in 1..word.length) assertEquals(Effect.None, type(i, word.substring(0, end)))
            assertEquals(word, entry.cell(i).text)
            type(i, "$word ")
            assertEquals(word, entry.cell(i).text)
            entry.onFocusChanged(i, false)
        }
        assertEquals(lojban, cells())
        val valid = checkNotNull(entry.validPhrase)
        assertEquals(SeedFormat.LEGACY, valid.format)
        assertEquals(lojban, valid.restoreWords)

        // A typographic apostrophe (U+2019) becomes the list word's on commit.
        entry.onFocusChanged(7, true)
        type(7, "fu\u2019ivla")
        type(7, "fu\u2019ivla ")
        assertEquals("fu'ivla", entry.cell(7).text)
        assertEquals(lojban, checkNotNull(entry.validPhrase).restoreWords)
    }

    @Test
    fun `verdicts other than valid give no phrase`() {
        entry.paste(legacy.joinToString(" "))
        type(24, "zzz ")
        assertTrue(entry.review.verdict !is Verdict.Valid)
        assertNull(entry.validPhrase)
    }
}
