package one.monero.moneroone.core.wallet.seed

import org.junit.Assert.assertEquals
import org.junit.Test

class SeedPasteParserTest {
    private val words = listOf("sequence", "atlas", "unveil", "summon")

    @Test
    fun `numbered lists`() {
        assertEquals(words, SeedPasteParser.parse("1. sequence\n2. atlas\n3. unveil\n4. summon\n"))
        assertEquals(words, SeedPasteParser.parse("1) sequence 2) atlas 3) unveil 4) summon"))
        assertEquals(words, SeedPasteParser.parse("#1 sequence #2 atlas #3 unveil #4 summon"))
        assertEquals(words, SeedPasteParser.parse("1: sequence 2: atlas 3: unveil 4: summon"))
        assertEquals(words, SeedPasteParser.parse("(1) sequence (2) atlas (3) unveil (4) summon"))
        assertEquals(words, SeedPasteParser.parse("1 sequence\n2 atlas\n3 unveil\n4 summon"))
    }

    @Test
    fun `numbering glued to the word`() {
        assertEquals(words, SeedPasteParser.parse("1.sequence 2.atlas 3)unveil 4:summon"))
    }

    @Test
    fun `separators and spacing`() {
        assertEquals(words, SeedPasteParser.parse("sequence, atlas,unveil;summon"))
        assertEquals(words, SeedPasteParser.parse("sequence\tatlas\t\tunveil  summon"))
        assertEquals(words, SeedPasteParser.parse("  sequence\r\natlas\n\nunveil   summon  "))
        assertEquals(words, SeedPasteParser.parse("sequence　atlas unveil summon"))
        assertEquals(words, SeedPasteParser.parse("sequence，atlas；unveil、summon"))
    }

    @Test
    fun `case, quotes, brackets and trailing periods`() {
        assertEquals(words, SeedPasteParser.parse("SEQUENCE Atlas UnVeil summon."))
        assertEquals(words, SeedPasteParser.parse("\"sequence atlas unveil summon\""))
        assertEquals(words, SeedPasteParser.parse("“sequence” 'atlas' [unveil] (summon)."))
        assertEquals(words, SeedPasteParser.parse("sequence. atlas. unveil. summon。"))
    }

    @Test
    fun `full-width letters and Turkish capitals use the locale-independent form`() {
        assertEquals(listOf("sequence", "atlas"), SeedPasteParser.parse("ｓｅｑｕｅｎｃｅ ＡＴＬＡＳ"))
        assertEquals(listOf("bitten", "imitate"), SeedPasteParser.parse("BITTEN IMITATE"))
    }

    @Test
    fun `lojban apostrophes stay inside words`() {
        val lojban = listOf("fu'ivla", "latro'a", "ci'izra", "fa'orma'o")
        assertEquals(lojban, SeedPasteParser.parse("fu'ivla latro'a ci'izra fa'orma'o"))
        assertEquals(lojban, SeedPasteParser.parse("1. fu'ivla\n2. latro'a\n3. ci'izra\n4. fa'orma'o"))
        assertEquals(lojban, SeedPasteParser.parse("FU'IVLA, Latro'a; ci'izra fa'orma'o."))
        // Smart punctuation (notes apps, word processors) and the modifier letter apostrophe.
        assertEquals(lojban, SeedPasteParser.parse("fu\u2019ivla latro\u02BCa ci\u2018izra fa\u2019orma\u201Bo"))
        // Quotes around a word or the whole phrase still go.
        assertEquals(lojban, SeedPasteParser.parse("'fu'ivla latro'a ci'izra fa'orma'o'"))
        assertEquals(lojban, SeedPasteParser.parse("\u2018fu\u2019ivla\u2019 \u201Clatro\u2019a\u201D (ci'izra) [fa'orma'o]"))
        assertEquals("fu'ivla", SeedText.normalizeCell("FU\u2019IVLA"))
    }

    @Test
    fun `japanese words keep their composed kana`() {
        assertEquals(listOf("あいこくしん", "がっこう"), SeedPasteParser.parse("あいこくしん　がっこう"))
    }

    @Test
    fun `empty input`() {
        assertEquals(emptyList<String>(), SeedPasteParser.parse(""))
        assertEquals(emptyList<String>(), SeedPasteParser.parse(" 1. 2. , ; "))
    }
}
