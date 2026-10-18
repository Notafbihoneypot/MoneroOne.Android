package one.monero.moneroone.ui.screens.onboarding

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.monero.moneroone.core.wallet.seed.SeedFormat
import one.monero.moneroone.core.wallet.seed.SeedPasteParser
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator.Review
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator.Status
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator.Verdict
import one.monero.moneroone.core.wallet.seed.SeedText
import one.monero.moneroone.core.wallet.seed.SeedWordLists
import timber.log.Timber
import java.util.Locale

/**
 * Owns the seed step's words for the restore flow's back-stack entry: they
 * live in memory only and survive a rotation or theme change, never saved
 * instance state or disk. Cleared when the entry leaves the back stack.
 */
class SeedEntryViewModel : ViewModel() {
    val entry = SeedEntryState()

    /** The phrase Continue accepted, for the naming step. */
    var accepted: Verdict.Valid? = null

    init {
        // The lists load on first use; load them now, off Main, so the first
        // keystroke does not wait for the files.
        viewModelScope.launch {
            entry.listsReady = withContext(Dispatchers.Default) {
                runCatching {
                    SeedWordLists.prewarm()
                    SeedFormat.entries.forEach { SeedPhraseValidator.lists(it) }
                }.onFailure { Timber.e("Word lists failed to load (${it.javaClass.simpleName})") }.isSuccess
            }
        }
    }

    fun wipe() {
        accepted = null
        entry.wipe()
    }

    override fun onCleared() = wipe()
}

/**
 * The word cells of the seed step (seed-entry spec 2.2 to 2.5): typing,
 * commit and canonical words, paste, suggestions and the live review. Plain
 * snapshot state, so the rules run in unit tests. Every action returns an
 * [Effect] for the screen to apply (focus moves, clearing the clipboard,
 * scrolling).
 */
class SeedEntryState {

    sealed interface Focus {
        data object Keep : Focus
        data object Hide : Focus
        data class Cell(val index: Int) : Focus
    }

    /** Where the screen scrolls after an action (iOS SeedEntryScrollRequest). */
    enum class Scroll {
        /** The status row under the grid, when it has news: a full paste, a paste that fits nowhere, the keyboard closing after a commit. */
        Status,

        /** The top of the step (word count, Paste, word 1), after Clear. */
        Top
    }

    data class Effect(val focus: Focus = Focus.Keep, val clearClipboard: Boolean = false, val scroll: Scroll? = null) {
        companion object {
            val None = Effect()
        }
    }

    /**
     * All 25 cells. The word count shows the first 16, 24 or 25. Hidden cells
     * stay empty: a count change empties every cell, and a full paste empties
     * the cells past its count.
     */
    private val cells = mutableStateListOf(*Array(CELL_COUNT) { TextFieldValue() })

    /** A cell is committed after space/return/Next, a paste, a suggestion or focus loss. */
    private val committed = mutableStateListOf(*Array(CELL_COUNT) { false })

    var format by mutableStateOf(SeedFormat.LEGACY)
        private set

    var focusedIndex by mutableStateOf<Int?>(null)
        private set

    /** A paste with a word count that fits nowhere. Cleared by the next edit. */
    var showsCountError by mutableStateOf(false)
        private set

    /** The word lists are loaded; until then cells are not checked. */
    var listsReady by mutableStateOf(false)

    val wordCount: Int get() = format.wordCount

    fun cell(index: Int): TextFieldValue = cells[index]

    /** Any cell has text (Clear empties all of them). */
    val hasText: Boolean get() = cells.any { it.text.isNotBlank() }

    val review: Review by derivedStateOf {
        val count = format.wordCount
        val texts = List(count) { cells[it].text }
        if (!listsReady) {
            Review(Status.Progress(texts.count { it.isNotBlank() }, count), emptySet(), null)
        } else {
            SeedPhraseValidator.review(format, texts, List(count) { committed[it] }, focusedIndex)
        }
    }

    /** The phrase when it is valid: display words for the cells, restore words for the wallet. */
    val validPhrase: Verdict.Valid? get() = review.verdict as? Verdict.Valid

    /** Up to three list words for the focused cell's text. */
    val suggestions: List<String> by derivedStateOf {
        val index = focusedIndex
        if (!listsReady || index == null || index >= format.wordCount) return@derivedStateOf emptyList()
        val token = token(index)
        if (token.isEmpty()) return@derivedStateOf emptyList()
        SeedPhraseValidator.suggestions(SeedPhraseValidator.activeList(format, visibleTokens()), token)
    }

    // --- Editing ---------------------------------------------------------

    /**
     * The word count control. Another count starts over: every word (hidden
     * cells too), the invalid marks, the status line and the suggestions go
     * (user, 2026-10-05). Focus moves to word 1 when a cell had it; otherwise
     * the keyboard stays down. The selected count changes nothing. A paste
     * that fits another count sets the count in [insert] and keeps its words.
     */
    fun setFormat(newFormat: SeedFormat): Effect {
        if (newFormat == format) return Effect.None
        val hadFocus = focusedIndex != null
        dropWords()
        format = newFormat
        return if (hadFocus) Effect(Focus.Cell(0)) else Effect.None
    }

    fun onFocusChanged(index: Int, focused: Boolean) {
        if (focused) {
            focusedIndex = index
            return
        }
        if (focusedIndex == index) focusedIndex = null
        settle(index)
    }

    /** The screen left composition (rotation): nothing has focus any more. */
    fun dropFocus() {
        focusedIndex?.let { settle(it) }
        focusedIndex = null
    }

    /** Next/Done on the keyboard, Tab or Enter: commit the word and move on. */
    fun commit(index: Int): Effect {
        settle(index)
        return advance(index)
    }

    /**
     * Text from the cell's field. Space, comma, tab or newline commits the
     * word and moves on; two or more words fill the grid like a paste. An
     * IME composition (Japanese, Chinese, Korean, gesture typing) is never
     * rewritten.
     */
    fun onValueChange(index: Int, new: TextFieldValue): Effect {
        val old = cells[index]
        val composition = new.composition
        if (composition != null) {
            val before = new.text.substring(0, composition.min.coerceIn(0, new.text.length))
            val finished = if (before.any(SeedText::isSeparator)) SeedPasteParser.parse(before) else emptyList()
            if (finished.isEmpty()) {
                if (new.text != old.text) {
                    committed[index] = false
                    showsCountError = false
                }
                cells[index] = new
                return Effect.None
            }
            // Gesture typing commits a word, a space and the next word being
            // composed in one batch: settle the finished word and move the
            // composing one to the next cell.
            val rest = SeedText.normalizeCell(new.text.substring(composition.min).trim())
            return spill(index, finished, rest)
        }
        if (new.text.none(SeedText::isSeparator)) {
            if (new.text == old.text) {
                cells[index] = new
                return Effect.None
            }
            // Locale.ROOT: the app ships Turkish, where "I" lowercases to a dotless "ı".
            val lower = new.text.lowercase(Locale.ROOT)
            cells[index] = if (lower == new.text) {
                new
            } else {
                TextFieldValue(lower, TextRange(new.selection.start.coerceAtMost(lower.length), new.selection.end.coerceAtMost(lower.length)))
            }
            committed[index] = false
            showsCountError = false
            return Effect.None
        }
        // Two or more words arrived at once: a paste (or dictation).
        val pasted = SeedPasteParser.parse(insertedText(old.text, new.text))
        if (pasted.size >= 2) return insert(pasted, index).copy(clearClipboard = true)

        val tokens = SeedPasteParser.parse(new.text)
        return when (tokens.size) {
            0 -> {
                // Only separators: nothing to commit.
                cells[index] = TextFieldValue()
                Effect.None
            }
            1 -> {
                setWord(index, tokens[0], committed = false)
                showsCountError = false
                commit(index)
            }
            else -> insert(tokens, index)
        }
    }

    /** Backspace in an empty cell: focus the previous cell, caret at the end. */
    fun deleteBackwardInEmptyCell(index: Int): Effect {
        if (index <= 0 || focusedIndex != index || cells[index].text.isNotEmpty()) return Effect.None
        // Set now, so a second report of the same key press moves only once.
        focusedIndex = index - 1
        val previous = cells[index - 1]
        cells[index - 1] = previous.copy(selection = TextRange(previous.text.length))
        return Effect(Focus.Cell(index - 1))
    }

    fun acceptSuggestion(word: String): Effect {
        val index = focusedIndex ?: return Effect.None
        setWord(index, word, committed = true)
        showsCountError = false
        return advance(index)
    }

    /**
     * The Paste button. The clipboard is cleared after a multi-word paste.
     * An empty clipboard changes nothing (iOS disables its paste control then).
     */
    fun paste(text: String): Effect {
        val tokens = SeedPasteParser.parse(text)
        if (tokens.isEmpty()) return Effect.None
        val effect = insert(tokens, null)
        return if (tokens.size >= 2) effect.copy(clearClipboard = true) else effect
    }

    /** The Clear button: every cell. The screen goes back to the top. */
    fun clearAll(): Effect {
        dropWords()
        return Effect(scroll = Scroll.Top)
    }

    /** The flow ended: drop every word. */
    fun wipe() {
        dropWords()
        format = SeedFormat.LEGACY
        focusedIndex = null
    }

    // --- Private -----------------------------------------------------------

    /**
     * Words from the Paste button ([index] null) or pasted into a cell. A
     * supported count replaces everything and sets the word count; otherwise
     * the words fill the grid from the cell they were pasted into, when they fit.
     */
    private fun insert(tokens: List<String>, index: Int?): Effect {
        SeedFormat.forWordCount(tokens.size)?.let { pasted ->
            format = pasted
            val words = tokens.map { canonical(it, tokens) }
            for (i in 0 until CELL_COUNT) {
                val word = words.getOrElse(i) { "" }
                cells[i] = TextFieldValue(word, TextRange(word.length))
                committed[i] = i < words.size
            }
            showsCountError = false
            return Effect(Focus.Hide, scroll = Scroll.Status)
        }
        if (index != null && tokens.isNotEmpty() && index + tokens.size <= wordCount) {
            val context = settledTokens() + tokens
            tokens.forEachIndexed { offset, token -> setWord(index + offset, canonical(token, context), committed = true) }
            showsCountError = false
            return moveOrHide(index + tokens.size)
        }
        showsCountError = true
        return Effect(scroll = Scroll.Status)
    }

    private fun spill(index: Int, finished: List<String>, rest: String): Effect {
        if (index + finished.size > wordCount) {
            showsCountError = true
            return Effect(scroll = Scroll.Status)
        }
        val context = settledTokens() + finished
        finished.forEachIndexed { offset, token -> setWord(index + offset, canonical(token, context), committed = true) }
        showsCountError = false
        val next = index + finished.size
        if (rest.isEmpty()) return moveOrHide(next)
        if (next >= wordCount) return Effect(Focus.Hide)
        setWord(next, rest, committed = false)
        return Effect(Focus.Cell(next))
    }

    private fun dropWords() {
        for (i in 0 until CELL_COUNT) {
            cells[i] = TextFieldValue()
            committed[i] = false
        }
        showsCountError = false
    }

    /**
     * Normalizes the cell and, when it matches a list word, shows that word.
     * Hidden cells are skipped: a count change emptied them.
     */
    private fun settle(index: Int) {
        if (index >= wordCount) return
        val token = token(index)
        val word = canonical(token, visibleTokens())
        if (word != cells[index].text) cells[index] = TextFieldValue(word, TextRange(word.length))
        committed[index] = true
    }

    private fun advance(index: Int): Effect = moveOrHide(index + 1)

    /**
     * Focus [next], or close the keyboard: after the last cell, and once a
     * commit makes the phrase valid, so Continue and the status row show.
     */
    private fun moveOrHide(next: Int): Effect =
        if (next < wordCount && validPhrase == null) Effect(Focus.Cell(next)) else Effect(Focus.Hide, scroll = Scroll.Status)

    private fun setWord(index: Int, word: String, committed: Boolean) {
        cells[index] = TextFieldValue(word, TextRange(word.length))
        this.committed[index] = committed
    }

    /** The list word [token] stands for in the list [context] matches best, else [token]. */
    private fun canonical(token: String, context: List<String>): String {
        if (token.isEmpty() || !listsReady) return token
        val list = SeedPhraseValidator.activeList(format, context)
        return SeedPhraseValidator.canonicalWord(list, token) ?: token
    }

    private fun token(index: Int): String = SeedText.normalizeCell(cells[index].text.trim())

    private fun visibleTokens(): List<String> = List(wordCount) { token(it) }

    private fun settledTokens(): List<String> = (0 until wordCount).filter { committed[it] }.map { token(it) }

    companion object {
        const val CELL_COUNT = 25

        /** The text [new] added to [old]: what lies between their common prefix and suffix. */
        internal fun insertedText(old: String, new: String): String {
            var start = 0
            val max = minOf(old.length, new.length)
            while (start < max && old[start] == new[start]) start++
            var end = 0
            while (end < max - start && old[old.length - 1 - end] == new[new.length - 1 - end]) end++
            return new.substring(start, new.length - end)
        }
    }
}
