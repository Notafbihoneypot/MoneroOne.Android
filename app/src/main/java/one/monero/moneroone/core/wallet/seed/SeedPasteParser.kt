package one.monero.moneroone.core.wallet.seed

/**
 * Splits pasted text into seed words: NFKC + lowercase ([SeedText.normalizeCell]),
 * split on whitespace (newlines, tabs, U+3000), commas and semicolons
 * (ASCII and CJK), drop numbering ("12", "12.", "12)", "12:", "#12", "(12)")
 * and numbering glued to a word ("12.word"), strip quotes and brackets at
 * the ends of a word, and trailing periods. An apostrophe inside a word
 * stays (Lojban "fu'ivla"; U+2019 becomes U+0027 in [SeedText.normalizeCell]).
 */
object SeedPasteParser {
    private val NUMBERING = Regex("^[#(\\[]?\\d+[.):\\]]?$")
    private val GLUED_NUMBERING = Regex("^[#(\\[]?\\d+[.):\\]](.+)$")
    private const val WRAPPERS = "\"'`“”‘’„‚«»‹›「」『』()[]{}<>（）【】"

    fun parse(text: String): List<String> {
        val normalized = SeedText.normalizeCell(text)
        val words = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            clean(current.toString())?.let { words += it }
            current.setLength(0)
        }
        for (ch in normalized) {
            if (SeedText.isSeparator(ch)) flush() else current.append(ch)
        }
        flush()
        return words
    }

    private fun clean(raw: String): String? {
        var token = strip(raw)
        if (token.isEmpty() || NUMBERING.matches(token)) return null
        GLUED_NUMBERING.matchEntire(token)?.let { token = strip(it.groupValues[1]) }
        token = strip(token.trimEnd('.', '。'))
        return token.ifEmpty { null }
    }

    private fun strip(token: String): String = token.trim { it in WRAPPERS || it.isWhitespace() }
}
