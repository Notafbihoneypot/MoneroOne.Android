package one.monero.moneroone.core.wallet.seed

import java.text.Normalizer
import java.util.Locale

/** Text forms the seed entry uses. */
object SeedText {
    /**
     * The form a cell holds: typographic apostrophes as U+0027, then NFKC
     * (the wallet2 lists are NFKC-stable, and NFKC folds full-width letters
     * and odd spaces), then a lowercase that does not depend on the locale
     * (the app ships Turkish).
     */
    fun normalizeCell(text: String): String =
        Normalizer.normalize(asciiApostrophes(text), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /**
     * Lojban words such as "fu'ivla" hold U+0027. Notes apps and word
     * processors save it as U+2019 (smart punctuation), some keyboards as
     * U+02BC. No word list holds these characters.
     */
    private const val APOSTROPHES = "\u2018\u2019\u201B\u02BC"

    private fun asciiApostrophes(text: String): String =
        if (text.none { it in APOSTROPHES }) text else buildString(text.length) {
            for (ch in text) append(if (ch in APOSTROPHES) '\'' else ch)
        }

    /** Accent-blind form for suggestions: NFKD without combining accents (U+0300..U+036F), lowercase. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) if (ch !in '̀'..'ͯ') out.append(ch)
        return out.toString().lowercase(Locale.ROOT)
    }

    /** True for characters that end a word while typing (space, comma, semicolon, tab, newline, CJK separators). */
    fun isSeparator(ch: Char): Boolean =
        Character.isWhitespace(ch) || Character.isSpaceChar(ch) ||
            ch == ',' || ch == ';' || ch == '，' || ch == '；' || ch == '、'
}
