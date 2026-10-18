package one.monero.moneroone.core.wallet.seed

import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.hdwalletkit.WordList
import java.text.Normalizer
import io.horizontalsystems.hdwalletkit.Language as KitLanguage

/**
 * BIP39 word lists from hd-wallet-kit, the library the restore path uses.
 * The kit decides validity (word list + SHA-256 checksum); this adds the
 * matching rules for typing: exact list words after NFKD (Spanish and French
 * also without accents, as the kit accepts them), and a unique prefix of 4
 * or more letters.
 *
 * [Language.words] are the kit's stored words, which are NFKD. BIP39 hashes
 * the NFKD phrase and the kit does not normalize, so the restore path must
 * receive these words, not the typed text.
 */
object Bip39Words {
    const val LIST_SIZE = 2048
    private const val MIN_PREFIX = 4

    class Language internal constructor(val kitLanguage: KitLanguage, val words: List<String>) {
        private val exact = HashMap<String, Int>(LIST_SIZE * 2)
        private val folded: HashMap<String, Int>? =
            if (kitLanguage == KitLanguage.Spanish || kitLanguage == KitLanguage.French) HashMap(LIST_SIZE * 2) else null
        private val keys: List<String>
        private val foldedKeys: List<String>?

        /** The words in composed form, for cells and suggestions. */
        val displayWords: List<String> = words.map { Normalizer.normalize(it, Normalizer.Form.NFC) }

        init {
            require(words.size == LIST_SIZE) { "$kitLanguage: ${words.size} words" }
            keys = words.map { nfkd(it) }
            foldedKeys = folded?.let { keys.map { stripAccents(it) } }
            keys.forEachIndexed { index, key ->
                exact.putIfAbsent(key, index)
                folded?.putIfAbsent(stripAccents(key), index)
            }
        }

        /** The list index for [token]: an exact word, or a unique prefix of 4+ letters. */
        fun match(token: String): Int? {
            if (token.isEmpty()) return null
            val key = nfkd(token)
            exact[key]?.let { return it }
            folded?.get(stripAccents(key))?.let { return it }
            val letters = Normalizer.normalize(token, Normalizer.Form.NFC)
            if (letters.codePointCount(0, letters.length) < MIN_PREFIX) return null
            val foldedKey = foldedKeys?.let { stripAccents(key) }
            var found = -1
            for (i in keys.indices) {
                val hit = if (foldedKey != null) foldedKeys!![i].startsWith(foldedKey) else keys[i].startsWith(key)
                if (hit) {
                    if (found >= 0) return null
                    found = i
                }
            }
            return found.takeIf { it >= 0 }
        }

        val isEnglish: Boolean get() = kitLanguage == KitLanguage.English

        override fun toString(): String = kitLanguage.name
    }

    /** In the kit's Language order (Mnemonic.validate tries them in this order). */
    val languages: List<Language> by lazy {
        KitLanguage.values().map { lang ->
            val list = WordList.wordList(lang)
            Language(lang, List(LIST_SIZE) { list.get(it) })
        }
    }

    /** The kit's validation (word list and checksum). */
    fun isValid(words: List<String>): Boolean = try {
        Mnemonic().validate(words)
        true
    } catch (e: Exception) {
        false
    }

    private fun nfkd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)

    /** The kit's normalize for Spanish and French: NFKD without non-ASCII characters. */
    private fun stripAccents(nfkd: String): String {
        val out = StringBuilder(nfkd.length)
        for (ch in nfkd) if (ch.code < 0x80) out.append(ch)
        return out.toString()
    }
}
