package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.Seed
import io.horizontalsystems.monerokit.toElectrum
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.monero.moneroone.core.wallet.seed.LegacyMnemonic
import one.monero.moneroone.core.wallet.seed.Polyseed
import one.monero.moneroone.core.wallet.seed.SeedFormat
import one.monero.moneroone.core.wallet.seed.SeedPasteParser
import one.monero.moneroone.core.wallet.seed.SeedPhraseValidator
import one.monero.moneroone.core.wallet.seed.SeedTestSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.Locale

/**
 * A restored 16-word Polyseed through every stored-seed consumer: the words
 * the restore stores, WalletSecrets, the wallet row's source, the kit seed
 * (open, heal and reset sync all go through SeedValidation.kitSeed), the
 * Backup Seed legacy words and the derived wallet id.
 */
class PolyseedPlumbingTest {

    private val en1 = "raven tail swear infant grief assist regular lamp duck valid someone little harsh puppy airport language"
        .split(" ")

    private val vectors: List<JsonObject> by lazy {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("polyseed-vectors.json")).bufferedReader().readText()
        Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
    }

    private val warnings = mutableListOf<String>()
    private val recorder = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == android.util.Log.WARN) warnings += message
        }
    }

    @Before
    fun setUp() {
        SeedTestSupport.init()
        Timber.plant(recorder)
    }

    @After
    fun tearDown() {
        Timber.uproot(recorder)
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    /** What WalletViewModel.restoreNormalized stores for typed or pasted cells. */
    private fun storedWords(cells: List<String>): List<String> {
        val verdict = SeedPhraseValidator.validate(SeedFormat.POLYSEED, cells) as SeedPhraseValidator.Verdict.Valid
        return verdict.restoreWords.map { it.lowercase(Locale.ROOT) }
    }

    private fun kitWords(words: List<String>): List<String> =
        (SeedValidation.kitSeed(words, SeedType.POLYSEED_16) as Seed.Electrum).mnemonic

    @Test
    fun `every language pastes, stores and opens with the C library's spend key`() {
        val usable = vectors.filter {
            it.getValue("status").jsonPrimitive.int == 0 && !it.getValue("encrypted").jsonPrimitive.boolean &&
                it.getValue("coin").jsonPrimitive.int == 0
        }
        assertTrue(usable.size >= 30)
        for (v in usable) {
            val name = v.str("name")
            val words = storedWords(SeedPasteParser.parse(v.str("phrase")))
            assertEquals(name, 16, words.size)

            // WalletSecrets joins and splits on a plain space.
            val secrets = WalletSecrets(FakeSharedPreferences())
            secrets.saveSeed("w", words, SeedType.POLYSEED_16)
            val (loaded, type) = checkNotNull(secrets.loadSeed("w"))
            assertEquals(name, words, loaded)
            assertEquals(name, SeedType.POLYSEED_16, type)
            // Lockstep: the cache id derives from the stored words, unchanged.
            assertEquals(name, WalletCacheIds.derivedWalletId(words, 0), WalletCacheIds.derivedWalletId(loaded, 0))

            // The kit gets the 25 legacy words of the same spend key.
            val electrum = SeedValidation.kitSeed(loaded, type)
            assertTrue(name, electrum is Seed.Electrum)
            assertEquals(name, electrum, electrum.toElectrum())
            val legacy = (electrum as Seed.Electrum).mnemonic
            assertEquals(name, "", electrum.passphrase)
            assertNull(name, SeedValidation.electrumSeedProblem(legacy))
            assertEquals(name, legacy, SeedValidation.canonicalElectrumWords(legacy))
            assertEquals(name, v.str("spendKeyHex"), SeedTestSupport.hex(checkNotNull(LegacyMnemonic.decode(legacy).key)))
            // Backup Seed shows the same legacy words.
            assertEquals(name, legacy, SeedValidation.polyseedLegacyWords(loaded))
        }
    }

    @Test
    fun `prefix-typed polyseed opens the same wallet as the full words`() {
        val prefixes = en1.map { it.take(4) }
        assertEquals(en1, storedWords(prefixes))
        assertEquals(kitWords(en1), kitWords(storedWords(prefixes)))
        // Upper case and accents typed or not give the same stored words.
        assertEquals(en1, storedWords(en1.map { it.uppercase(Locale.ROOT) }))
        val es1 = vectors.single { it.str("name") == "ref_es1" }.str("phrase").split(" ")
        val es2 = vectors.single { it.str("name") == "ref_es2" }.str("phrase").split(" ")
        assertEquals(storedWords(es1), storedWords(es2))
    }

    @Test
    fun `stored polyseeds that must not become wallets are rejected`() {
        val changed = en1.toMutableList().apply { this[5] = "grief" }
        val encrypted = vectors.single { it.str("name") == "encrypted_en" }.str("phrase").split(" ")
        val reserved = vectors.single { it.str("name") == "reserved_feature_en" }.str("phrase").split(" ")
        for (words in listOf(changed, encrypted, reserved, en1.take(15))) {
            try {
                SeedValidation.kitSeed(words, SeedType.POLYSEED_16)
                fail("kitSeed accepted an invalid Polyseed")
            } catch (_: InvalidSeedException) {
            }
            try {
                SeedValidation.validate(words, SeedType.POLYSEED_16)
                fail("validate accepted an invalid Polyseed")
            } catch (_: InvalidSeedException) {
            }
        }
        // Rejected by the Polyseed check, before any native key derivation.
        assertEquals(4, warnings.count { it == "Phrase rejected: Polyseed validation failed" })
        val logged = warnings.flatMap { it.split(Regex("\\W+")) }.toSet()
        assertTrue("a seed word reached the log", en1.none { it in logged })
    }

    @Test
    fun `wallet source maps polyseed both ways and uses the iOS raw value`() {
        assertEquals(WalletSource.POLYSEED, WalletSource.fromSeedType(SeedType.POLYSEED_16))
        assertEquals(SeedType.POLYSEED_16, WalletSource.POLYSEED.toSeedType())
        assertEquals(WalletSource.POLYSEED, WalletSource.fromWordCount(16))
        assertEquals(false, WalletSource.POLYSEED.isViewOnly)

        val row = WalletInfo(id = "p", name = "Polyseed", source = WalletSource.POLYSEED, restoreHeight = 2_485_302L)
        val encoded = WalletStore.encodeWallets(listOf(row))
        assertTrue(encoded, "\"polyseed\"" in encoded)
        assertEquals(listOf(row), WalletStore.decodeWallets(encoded))
    }

    @Test
    fun `restore height and date come from the polyseed birthday`() {
        val verdict = SeedPhraseValidator.validate(SeedFormat.POLYSEED, en1) as SeedPhraseValidator.Verdict.Valid
        val birthday = checkNotNull(verdict.polyseedBirthdayUnix)
        assertEquals(1_638_397_746L, birthday)
        // Never later than the birthday: a month of blocks before it. The
        // iOS native library stores the same height; iOS Settings > Restore
        // Height shows 2,484,814, the day-rounded table height under it.
        assertEquals(2_485_302L, Polyseed.restoreHeight(birthday))
    }
}
