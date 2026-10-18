package one.monero.moneroone.core.wallet.seed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator.Verdict
import one.monero.moneroone.core.wallet.seed.SeedTestSupport.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Polyseed port against golden vectors from the C library
 * (external/polyseed, built on the Mac with CommonCrypto PBKDF2 and
 * CoreFoundation NFKD/NFC: the tests.c phrases, 3 random phrases per
 * language, an encrypted and a reserved-feature phrase).
 */
class PolyseedTest {

    private val en1 = "raven tail swear infant grief assist regular lamp duck valid someone little harsh puppy airport language"

    private val vectors: List<JsonObject> by lazy {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("polyseed-vectors.json")).bufferedReader().readText()
        Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
    }

    @Before
    fun setUp() = SeedTestSupport.init()

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `all golden vectors decode like the C library`() {
        assertEquals(45, vectors.size)
        for (v in vectors) {
            val name = v.str("name")
            val decoded = Polyseed.decode(v.str("phrase"), v.getValue("coin").jsonPrimitive.int)
            assertEquals(name, v.getValue("status").jsonPrimitive.int, decoded.status.code)
            if (decoded.status != Polyseed.Status.OK) {
                assertNull(name, decoded.data)
                continue
            }
            val data = checkNotNull(decoded.data)
            assertEquals(name, v.str("lang"), decoded.language?.spec?.englishName)
            assertEquals(name, v.str("secretHex"), hex(data.secret))
            assertEquals(name, v.getValue("birthday").jsonPrimitive.int, data.birthday)
            assertEquals(name, v.getValue("birthdayUnix").jsonPrimitive.long, data.birthdayUnix)
            assertEquals(name, v.getValue("features").jsonPrimitive.int, data.features)
            assertEquals(name, v.getValue("encrypted").jsonPrimitive.boolean, data.isEncrypted)
            assertEquals(name, v.str("keyHex"), hex(Polyseed.keygen(data)))
            assertEquals(name, v.str("spendKeyHex"), hex(Polyseed.spendKey(data)))
        }
    }

    @Test
    fun `random vectors encode back to the same phrase`() {
        val random = vectors.filter { it.containsKey("encodeLang") && it.getValue("status").jsonPrimitive.int == 0 }
        assertTrue(random.size >= 31)
        for (v in random) {
            val decoded = Polyseed.decode(v.str("phrase"))
            val language = SeedWordLists.polyseed.single { it.spec.englishName == v.str("encodeLang") }
            assertEquals(v.str("name"), v.str("phrase"), Polyseed.encode(checkNotNull(decoded.data), language))
        }
    }

    @Test
    fun `en1 data decodes in every language it is encoded in`() {
        val data = checkNotNull(Polyseed.decode(en1).data)
        for (language in SeedWordLists.polyseed) {
            val phrase = Polyseed.encode(data, language)
            val decoded = Polyseed.decode(phrase)
            assertEquals(language.toString(), Polyseed.Status.OK, decoded.status)
            assertEquals(language.spec.englishName, decoded.language?.spec?.englishName)
            assertEquals(hex(data.secret), hex(checkNotNull(decoded.data).secret))
        }
    }

    @Test
    fun `any single word change of en1 is invalid`() {
        val words = en1.split(" ")
        val english = SeedWordLists.polyseed.first()
        for (i in words.indices) {
            for (delta in listOf(1, 2, 977)) {
                val index = (english.find(words[i]) + delta) % Polyseed.LIST_SIZE
                val changed = words.toMutableList().apply { this[i] = english.words[index] }
                val decoded = Polyseed.decode(changed.joinToString(" "))
                assertNotEquals("word ${i + 1} +$delta", Polyseed.Status.OK, decoded.status)
                assertTrue(SeedPhraseValidator.validate(SeedFormat.POLYSEED, changed) !is Verdict.Valid)
            }
        }
    }

    @Test
    fun `garbage phrases have the wrong word count`() {
        assertEquals(Polyseed.Status.ERR_NUM_WORDS, Polyseed.decode("xxx xxx").status)
        assertEquals(Polyseed.Status.ERR_NUM_WORDS, Polyseed.decode(List(120) { "xxx" }.joinToString(" ", postfix = " ")).status)
        assertEquals(Polyseed.Status.ERR_NUM_WORDS, Polyseed.decode("$en1  ").status)
    }

    @Test
    fun `birthday date is the polyseed epoch plus birthday times the time step`() {
        // birthday.h: EPOCH 1635768000 (1 Nov 2021 12:00 UTC), TIME_STEP 2629746 s.
        for (v in vectors.filter { it.getValue("birthday") !is JsonNull }) {
            val birthday = v.getValue("birthday").jsonPrimitive.int
            val expected = 1_635_768_000L + birthday * 2_629_746L
            assertEquals(expected, Polyseed.birthdayUnix(birthday))
            assertEquals(v.getValue("birthdayUnix").jsonPrimitive.long, Polyseed.birthdayUnix(birthday))
        }
        // ref_en1 (birthday 1) was created around December 2021: the status line's month and year.
        val en1Birthday = checkNotNull(Polyseed.decode(en1).data).birthdayUnix
        assertEquals(1_638_397_746L, en1Birthday)
        assertEquals("December 2021", SeedDates.monthYear(en1Birthday, "MMMM yyyy", Locale.US))
        // birthday 563 (ref_es1) is in 2066 per the C code: 1635768000 + 563 * 2629746.
        val es1 = vectors.single { it.str("name") == "ref_es1" }
        val es1Unix = es1.getValue("birthdayUnix").jsonPrimitive.long
        assertEquals(1_635_768_000L + 563 * 2_629_746L, es1Unix)
        val utc = SimpleDateFormat("MMMM yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals(utc.format(Date(es1Unix * 1000)), SeedDates.monthYear(es1Unix, "MMMM yyyy", Locale.US))
    }

    @Test
    fun `restore height is a month before the birthday`() {
        val birthday = checkNotNull(Polyseed.decode(en1).data).birthdayUnix
        val atBirthday = 1_009_827L + (birthday - 1_458_748_658L) / 120
        assertEquals(atBirthday - 21_600, Polyseed.restoreHeight(birthday))
        assertEquals(2_485_302L, Polyseed.restoreHeight(birthday))
        // At the v2 fork time: the fork block minus one month.
        assertEquals(1_009_827L - 21_600, Polyseed.restoreHeight(1_458_748_658L))
    }

    @Test
    fun `legacy words restore the same spend key`() {
        for (v in vectors.filter { it.getValue("status").jsonPrimitive.int == 0 && !it.getValue("encrypted").jsonPrimitive.boolean }) {
            val data = checkNotNull(Polyseed.decode(v.str("phrase")).data)
            val words = Polyseed.legacyWords(data)
            assertEquals(25, words.size)
            val decoded = LegacyMnemonic.decode(words)
            assertNull(decoded.problem)
            assertEquals(v.str("spendKeyHex"), hex(checkNotNull(decoded.key)))
        }
    }

    @Test
    fun `validator verdicts for the special vectors`() {
        val byName = vectors.associateBy { it.str("name") }
        fun verdict(name: String) = SeedPhraseValidator.validate(SeedFormat.POLYSEED, byName.getValue(name).str("phrase").split(" "))

        val en1Verdict = verdict("ref_en1") as Verdict.Valid
        assertEquals(en1.split(" "), en1Verdict.restoreWords)
        assertEquals(1_638_397_746L, en1Verdict.polyseedBirthdayUnix)
        // Prefixes canonicalize to the full words.
        assertEquals(en1.split(" "), (verdict("en1_truncated4") as Verdict.Valid).displayWords)
        assertEquals(Verdict.Encrypted, verdict("encrypted_en"))
        assertEquals(Verdict.Invalid, verdict("reserved_feature_en"))
        assertEquals(Verdict.Invalid, verdict("ref_es_mult"))
        assertEquals(Verdict.UnknownWords(listOf(0)), verdict("ref_en3"))
        assertEquals(Verdict.UnknownWords(listOf(3)), verdict("ref_en4"))
        // Spanish without accents gives the accented (composed) list words.
        val es1 = byName.getValue("ref_es1").str("phrase").split(" ")
        assertEquals(es1, (verdict("ref_es2") as Verdict.Valid).displayWords)
        assertEquals(es1, (verdict("ref_es3") as Verdict.Valid).displayWords)
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 matches RFC 7914`() {
        val out = Polyseed.pbkdf2HmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(out)
        )
    }

    @Test
    fun `data with checksum round-trips through encode and decode`() {
        val secret = ByteArray(32).also { java.util.Random(7).nextBytes(it) }
        for (i in 19 until 32) secret[i] = 0
        secret[18] = (secret[18].toInt() and 0x3F).toByte()
        val data = Polyseed.dataWithChecksum(secret, birthday = 42, features = 0)
        val phrase = Polyseed.encode(data, SeedWordLists.polyseed.first())
        val decoded = Polyseed.decode(phrase)
        assertEquals(Polyseed.Status.OK, decoded.status)
        assertEquals(hex(secret), hex(checkNotNull(decoded.data).secret))
        assertEquals(42, decoded.data?.birthday)
        assertNotNull(decoded.language)
    }
}
