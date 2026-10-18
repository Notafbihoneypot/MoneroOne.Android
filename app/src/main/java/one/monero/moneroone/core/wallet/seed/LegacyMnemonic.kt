package one.monero.moneroone.core.wallet.seed

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Port of wallet2's 25-word mnemonic rules (pure Kotlin, no native code):
 * monero `src/mnemonics/electrum-words.cpp` (find_seed_language,
 * checksum_test, create_checksum_index, words_to_bytes, bytes_to_words) and
 * `language_base.h` (utf8prefix, the trimmed word map, WordHash/WordEqual).
 *
 * wallet2 reads a word by its first [Spec.prefixLength] characters (code
 * points), case-insensitively (towlower per code point, no Unicode
 * normalization), so "abbxy" is "abbey" in English.
 */
object LegacyMnemonic {
    const val SEED_WORDS = 25
    const val LIST_SIZE = 1626

    class Spec(
        val englishName: String,
        val file: String,
        val prefixLength: Int,
        /** EnglishOld: populate_maps(ALLOW_DUPLICATE_PREFIXES | ALLOW_SHORT_WORDS). */
        val allowDuplicatePrefixes: Boolean = false
    )

    /** wallet2's find_seed_language order. */
    val SPECS = listOf(
        Spec("Chinese (simplified)", "legacy/chinese_simplified.txt", 1),
        Spec("English", "legacy/english.txt", 3),
        Spec("Dutch", "legacy/dutch.txt", 4),
        Spec("French", "legacy/french.txt", 4),
        Spec("Spanish", "legacy/spanish.txt", 4),
        Spec("German", "legacy/german.txt", 4),
        Spec("Italian", "legacy/italian.txt", 4),
        Spec("Portuguese", "legacy/portuguese.txt", 4),
        Spec("Japanese", "legacy/japanese.txt", 3),
        Spec("Russian", "legacy/russian.txt", 4),
        Spec("Esperanto", "legacy/esperanto.txt", 4),
        Spec("Lojban", "legacy/lojban.txt", 4),
        Spec("English (old)", "legacy/english_old.txt", 4, allowDuplicatePrefixes = true),
    )

    /** One wallet2 language. [words] are the list words (NFC; German nouns capitalized). */
    class Language internal constructor(val spec: Spec, val words: List<String>) {
        val prefixLength: Int get() = spec.prefixLength

        /** word_map: full word -> index. */
        private val wordIndex = HashMap<String, Int>(LIST_SIZE * 2)

        /** trimmed_word_map: prefix -> index; a later duplicate overwrites the index. */
        private val trimmedIndex = HashMap<String, Int>(LIST_SIZE * 2)

        /** The map key as first inserted (operator[] keeps the existing key object). */
        private val trimmedKey = HashMap<String, String>(LIST_SIZE * 2)

        init {
            require(words.size == LIST_SIZE) { "${spec.englishName}: ${words.size} words" }
            words.forEachIndexed { index, word ->
                wordIndex[wordKey(word)] = index
                val trimmed = utf8Prefix(word, prefixLength)
                val key = wordKey(trimmed)
                check(key !in trimmedIndex || spec.allowDuplicatePrefixes) {
                    "Duplicate prefix in ${spec.englishName}"
                }
                trimmedKey.putIfAbsent(key, trimmed)
                trimmedIndex[key] = index
            }
        }

        /** The index wallet2 reads for [word] (prefix rule), or null when no list word matches. */
        fun indexOf(word: String): Int? = trimmedIndex[wordKey(utf8Prefix(word, prefixLength))]

        /** The index of [word] as a full list word (case-insensitive), or null. */
        fun exactIndexOf(word: String): Int? = wordIndex[wordKey(word)]

        /** The trimmed-map key create_checksum_index concatenates for [word]. */
        internal fun trimmedKeyOf(word: String): String? = trimmedKey[wordKey(utf8Prefix(word, prefixLength))]

        /**
         * The list word to show and store for [typed], which matches [index]:
         * a full list word stays as typed (in the list's case), anything else
         * becomes the word wallet2 reads.
         */
        fun canonicalWord(typed: String, index: Int): String =
            exactIndexOf(typed)?.let { words[it] } ?: words[index]

        override fun toString(): String = spec.englishName
    }

    enum class Problem { WORD_COUNT, UNKNOWN_WORDS, CHECKSUM, TRIPLE }

    /**
     * words_to_bytes for a 25-word seed. [language] is the language wallet2
     * settles on (the checksum-failing fallback too), [key] the 32 bytes it
     * restores from (before sc_reduce32), [problem] null when wallet2 accepts.
     */
    class Decoded(val language: Language?, val indices: IntArray?, val key: ByteArray?, val problem: Problem?)

    fun decode(words: List<String>, languages: List<Language> = SeedWordLists.legacy): Decoded {
        if (words.size != SEED_WORDS) return Decoded(null, null, null, Problem.WORD_COUNT)
        val (language, indices) = findSeedLanguage(words, languages)
            ?: return Decoded(null, null, null, Problem.UNKNOWN_WORDS)
        if (!checksumTest(words, language)) return Decoded(language, indices, null, Problem.CHECKSUM)
        val n = language.words.size.toLong()
        val key = ByteArray(32)
        for (i in 0 until (SEED_WORDS - 1) / 3) {
            val w1 = indices[i * 3].toLong()
            val w2 = indices[i * 3 + 1].toLong()
            val w3 = indices[i * 3 + 2].toLong()
            // uint32 arithmetic, as in C++: the n*n term can wrap, and a
            // wrapped triple fails the check below ("mumble mumble").
            val w0 = (w1 + n * (((n - w1) + w2) % n) + n * n * (((n - w2) + w3) % n)) and 0xFFFFFFFFL
            if (w0 % n != w1) return Decoded(language, indices, null, Problem.TRIPLE)
            for (b in 0 until 4) key[i * 4 + b] = (w0 ushr (8 * b)).toByte() // SWAP32LE
        }
        return Decoded(language, indices, key, null)
    }

    /**
     * find_seed_language with has_checksum = true: the first language where
     * every word matches and the checksum passes, else the LAST language
     * where every word matches (the fallback), else null.
     */
    internal fun findSeedLanguage(words: List<String>, languages: List<Language>): Pair<Language, IntArray>? {
        var fallback: Pair<Language, IntArray>? = null
        for (language in languages) {
            val indices = IntArray(words.size)
            var fullMatch = true
            for (i in words.indices) {
                val index = language.indexOf(words[i])
                if (index == null) {
                    fullMatch = false
                    break
                }
                indices[i] = index
            }
            if (!fullMatch) continue
            if (checksumTest(words, language)) return language to indices
            fallback = language to indices
        }
        return fallback
    }

    /** checksum_test: the last word equals, by prefix, the word create_checksum_index picks. */
    internal fun checksumTest(seed: List<String>, language: Language): Boolean {
        if (seed.isEmpty()) return false
        val lastWord = seed.last()
        val data = seed.dropLast(1)
        val index = createChecksumIndex(data, language) ?: return false
        // wallet2 trims only when the byte length exceeds the prefix length;
        // utf8prefix of a shorter word is the word itself, so this is the same.
        val trimmedChecksum = utf8Prefix(data[index], language.prefixLength)
        val trimmedLastWord = utf8Prefix(lastWord, language.prefixLength)
        return wordKey(trimmedChecksum) == wordKey(trimmedLastWord)
    }

    /** CRC32 of the concatenated trimmed-map keys, mod the word count. Null where wallet2 throws. */
    internal fun createChecksumIndex(words: List<String>, language: Language): Int? {
        val trimmedWords = StringBuilder()
        for (word in words) trimmedWords.append(language.trimmedKeyOf(word) ?: return null)
        val crc = CRC32().apply { update(trimmedWords.toString().toByteArray(StandardCharsets.UTF_8)) }.value
        return (crc % words.size).toInt()
    }

    /** bytes_to_words: 4 bytes (little-endian) give 3 words; the 25th is the checksum word. */
    fun bytesToWords(key: ByteArray, language: Language): List<String> {
        require(key.isNotEmpty() && key.size % 4 == 0) { "key length ${key.size}" }
        val n = language.words.size.toLong()
        val words = ArrayList<String>(key.size / 4 * 3 + 1)
        for (i in 0 until key.size / 4) {
            var w0 = 0L
            for (b in 3 downTo 0) w0 = (w0 shl 8) or (key[i * 4 + b].toLong() and 0xFF)
            val w1 = w0 % n
            val w2 = ((w0 / n) + w1) % n
            val w3 = (((w0 / n) / n) + w2) % n
            words += language.words[w1.toInt()]
            words += language.words[w2.toInt()]
            words += language.words[w3.toInt()]
        }
        words += words[checkNotNull(createChecksumIndex(words, language))]
        return words
    }

    /** language_base.h utf8prefix: the first [count] characters (code points). */
    internal fun utf8Prefix(s: String, count: Int): String {
        if (s.codePointCount(0, s.length) <= count) return s
        return s.substring(0, s.offsetByCodePoints(0, count))
    }

    /** WordHash/WordEqual: utf8canonical with towlower per code point. */
    internal fun wordKey(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out.appendCodePoint(Character.toLowerCase(cp))
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
