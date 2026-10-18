package one.monero.moneroone.core.wallet.seed

/** The seed formats the restore screen accepts, by word count. */
enum class SeedFormat(val wordCount: Int) {
    POLYSEED(16),
    BIP39(24),
    LEGACY(25);

    companion object {
        fun forWordCount(count: Int): SeedFormat? = entries.firstOrNull { it.wordCount == count }
    }
}

/** One word list as the entry screen uses it, with the matching rule of its format. */
interface SeedWordList {
    val name: String
    val isEnglish: Boolean

    /** The words to show (composed). */
    val displayWords: List<String>

    /** [displayWords] folded with [SeedText.fold], for suggestions. */
    val foldedWords: List<String>

    /** The list index the restore code reads for [token] (a normalized cell), or null. */
    fun match(token: String): Int?

    /** The word a cell shows for [token], which matched [index]. */
    fun displayWord(token: String, index: Int): String = displayWords[index]
}

private class LegacyList(val language: LegacyMnemonic.Language) : SeedWordList {
    override val name get() = language.spec.englishName
    override val isEnglish get() = language.spec.englishName == "English"
    override val displayWords get() = language.words
    override val foldedWords by lazy { displayWords.map(SeedText::fold) }
    override fun match(token: String) = if (token.isEmpty()) null else language.indexOf(token)
    override fun displayWord(token: String, index: Int) = language.canonicalWord(token, index)
}

private class PolyseedList(val language: Polyseed.Language) : SeedWordList {
    override val name get() = language.spec.englishName
    override val isEnglish get() = language.spec.englishName == "English"
    override val displayWords get() = language.displayWords
    override val foldedWords by lazy { displayWords.map(SeedText::fold) }
    override fun match(token: String): Int? {
        // A cell holds one word; text that NFKD splits (odd spaces) is not one.
        if (token.isEmpty() || token.any { it == ' ' }) return null
        return language.find(token).takeIf { it >= 0 }
    }
}

private class Bip39List(val language: Bip39Words.Language) : SeedWordList {
    override val name get() = language.toString()
    override val isEnglish get() = language.isEnglish
    override val displayWords get() = language.displayWords
    override val foldedWords by lazy { displayWords.map(SeedText::fold) }
    override fun match(token: String) = language.match(token)
}

/**
 * Seed phrase checks for the restore screen and the restore path, per
 * format: wallet2's legacy rules ([LegacyMnemonic]), the Polyseed library
 * ([Polyseed]) and hd-wallet-kit for BIP39 ([Bip39Words]).
 */
object SeedPhraseValidator {
    const val MAX_SUGGESTIONS = 3

    sealed interface Verdict {
        /**
         * [displayWords] go back into the cells; [restoreWords] go to the
         * restore code (the same words, except BIP39 keeps the kit's NFKD form).
         */
        data class Valid(
            val format: SeedFormat,
            val displayWords: List<String>,
            val restoreWords: List<String>,
            val polyseedBirthdayUnix: Long? = null
        ) : Verdict

        /** A Polyseed with the passphrase (encrypted) bit. */
        data object Encrypted : Verdict

        /** Words not in the list ([positions] are 0-based). */
        data class UnknownWords(val positions: List<Int>) : Verdict

        /** Every word is known, but the phrase is not a valid seed (checksum, triple, language, features, key). */
        data object Invalid : Verdict

        data object WrongCount : Verdict
    }

    sealed interface Status {
        data class Progress(val filled: Int, val total: Int) : Status

        /** 1-based word numbers. */
        data class CheckWords(val numbers: List<Int>) : Status
        data object InvalidPhrase : Status
        data object Passphrase : Status
        data class Valid(val polyseedBirthdayUnix: Long?) : Status
    }

    data class Review(
        val status: Status,
        /** 0-based cells to show as invalid. */
        val invalidCells: Set<Int>,
        val verdict: Verdict?
    )

    private val lists = HashMap<SeedFormat, List<SeedWordList>>()

    /** The word lists of [format], in the order its decoder tries them. */
    fun lists(format: SeedFormat): List<SeedWordList> = synchronized(lists) {
        lists.getOrPut(format) {
            when (format) {
                SeedFormat.LEGACY -> SeedWordLists.legacy.map { LegacyList(it) }
                SeedFormat.POLYSEED -> SeedWordLists.polyseed.map { PolyseedList(it) }
                SeedFormat.BIP39 -> Bip39Words.languages.map { Bip39List(it) }
            }
        }
    }

    /** The list most of [tokens] match; ties go to English, then list order. */
    fun activeList(format: SeedFormat, tokens: List<String>): SeedWordList {
        val all = lists(format)
        val words = tokens.filter { it.isNotEmpty() }
        var best = all.first { it.isEnglish }
        var bestCount = words.count { best.match(it) != null }
        for (list in all) {
            if (list === best) continue
            val count = words.count { list.match(it) != null }
            if (count > bestCount) {
                best = list
                bestCount = count
            }
        }
        return best
    }

    /** The word a cell shows after commit: the list word [token] matches in [list], or null. */
    fun canonicalWord(list: SeedWordList, token: String): String? =
        list.match(token)?.let { list.displayWord(token, it) }

    /**
     * Up to [MAX_SUGGESTIONS] words for [token] in [list]: the word the
     * matching rule reads first, then words that start with it, ignoring
     * accents, in list order.
     */
    fun suggestions(list: SeedWordList, token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        canonicalWord(list, token)?.let { out += it }
        val prefix = SeedText.fold(token)
        val folded = list.foldedWords
        for (i in folded.indices) {
            if (out.size >= MAX_SUGGESTIONS) break
            if (folded[i].startsWith(prefix)) out += list.displayWords[i]
        }
        return out.take(MAX_SUGGESTIONS)
    }

    /** The full check of [words] (cells or stored words) as [format]. */
    fun validate(format: SeedFormat, words: List<String>): Verdict {
        if (words.size != format.wordCount) return Verdict.WrongCount
        val tokens = words.map { SeedText.normalizeCell(it.trim()) }
        if (tokens.any { it.isEmpty() }) return Verdict.WrongCount
        return when (format) {
            SeedFormat.LEGACY -> validateLegacy(tokens)
            SeedFormat.POLYSEED -> validatePolyseed(tokens)
            SeedFormat.BIP39 -> validateBip39(tokens)
        }
    }

    private fun unknownWords(format: SeedFormat, tokens: List<String>): Verdict {
        val active = activeList(format, tokens)
        val unknown = tokens.indices.filter { active.match(tokens[it]) == null }
        return if (unknown.isEmpty()) Verdict.Invalid else Verdict.UnknownWords(unknown)
    }

    private fun validateLegacy(tokens: List<String>): Verdict {
        val decoded = LegacyMnemonic.decode(tokens)
        return when (decoded.problem) {
            null -> {
                val language = checkNotNull(decoded.language)
                val indices = checkNotNull(decoded.indices)
                val key = checkNotNull(decoded.key)
                val nullKey = Ed25519Scalar.isZero(key)
                key.fill(0)
                if (nullKey) {
                    Verdict.Invalid
                } else {
                    val words = tokens.mapIndexed { i, token -> language.canonicalWord(token, indices[i]) }
                    Verdict.Valid(SeedFormat.LEGACY, words, words)
                }
            }
            LegacyMnemonic.Problem.UNKNOWN_WORDS -> unknownWords(SeedFormat.LEGACY, tokens)
            LegacyMnemonic.Problem.WORD_COUNT -> Verdict.WrongCount
            LegacyMnemonic.Problem.CHECKSUM, LegacyMnemonic.Problem.TRIPLE -> Verdict.Invalid
        }
    }

    private fun validatePolyseed(tokens: List<String>): Verdict {
        if (tokens.any { it.contains(' ') }) return Verdict.Invalid
        val decoded = Polyseed.decode(tokens.joinToString(" "))
        return when (decoded.status) {
            Polyseed.Status.OK -> {
                val data = checkNotNull(decoded.data)
                val language = checkNotNull(decoded.language)
                val encrypted = data.isEncrypted
                val birthday = data.birthdayUnix
                data.wipe()
                if (encrypted) {
                    Verdict.Encrypted
                } else {
                    val words = tokens.map { language.displayWords[language.find(it)] }
                    Verdict.Valid(SeedFormat.POLYSEED, words, words, birthday)
                }
            }
            Polyseed.Status.ERR_LANG -> unknownWords(SeedFormat.POLYSEED, tokens)
            Polyseed.Status.ERR_NUM_WORDS -> Verdict.WrongCount
            else -> Verdict.Invalid
        }
    }

    private fun validateBip39(tokens: List<String>): Verdict {
        val active = activeList(SeedFormat.BIP39, tokens) as Bip39List
        val indices = tokens.map { active.match(it) }
        if (indices.any { it == null }) return unknownWords(SeedFormat.BIP39, tokens)
        val restoreWords = indices.map { active.language.words[it!!] }
        if (!Bip39Words.isValid(restoreWords)) return Verdict.Invalid
        val displayWords = indices.map { active.language.displayWords[it!!] }
        return Verdict.Valid(SeedFormat.BIP39, displayWords, restoreWords)
    }

    /**
     * The live status for the visible [cells] of [format]. A word is marked
     * invalid only once committed ([committed]) or when its cell is not
     * [focused], never while it is still being typed.
     */
    fun review(format: SeedFormat, cells: List<String>, committed: List<Boolean>, focused: Int?): Review {
        val count = format.wordCount
        val tokens = List(count) { SeedText.normalizeCell(cells.getOrElse(it) { "" }.trim()) }
        val active = activeList(format, tokens)
        val invalid = tokens.indices.filter { i ->
            tokens[i].isNotEmpty() &&
                (committed.getOrElse(i) { true } || i != focused) &&
                active.match(tokens[i]) == null
        }
        if (invalid.isNotEmpty()) {
            return Review(Status.CheckWords(invalid.map { it + 1 }), invalid.toSet(), null)
        }
        val filled = tokens.count { it.isNotEmpty() }
        if (filled < count) return Review(Status.Progress(filled, count), emptySet(), null)

        // Check the words the cells will hold after commit (the active list's
        // words), so a prefix still being typed reads like the full word.
        val expanded = tokens.map { token -> canonicalWord(active, token) ?: token }
        return when (val verdict = validate(format, expanded)) {
            is Verdict.Valid -> Review(Status.Valid(verdict.polyseedBirthdayUnix), emptySet(), verdict)
            Verdict.Encrypted -> Review(Status.Passphrase, emptySet(), verdict)
            // Only the cell being typed can be unknown here: the word is not finished yet.
            is Verdict.UnknownWords -> Review(Status.Progress(count, count), emptySet(), verdict)
            Verdict.Invalid, Verdict.WrongCount -> Review(Status.InvalidPhrase, emptySet(), verdict)
        }
    }
}
