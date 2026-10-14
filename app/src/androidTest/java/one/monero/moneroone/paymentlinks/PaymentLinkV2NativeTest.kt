package one.monero.moneroone.paymentlinks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.horizontalsystems.monerokit.MoneroKit
import io.horizontalsystems.monerokit.Seed
import io.horizontalsystems.monerokit.model.WalletManager
import one.monero.moneroone.core.wallet.SeedType
import one.monero.moneroone.core.wallet.SeedValidation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * On a device: the spec's v2 vectors through the kit's real wallet2 library,
 * on the paths the envelope runtime uses. [EnvelopeFactory.restoredAddress] is
 * the restore check before funding (wallet2 `recoveryWallet` from the 25
 * words), and [SeedValidation.validate] gives the address it must match.
 * No network is used.
 *
 * Run it alone, never with connectedAndroidTest (that uninstalls the app):
 * `adb install -t` both APKs, then
 * `am instrument -w -e class one.monero.moneroone.paymentlinks.PaymentLinkV2NativeTest
 * one.monero.moneroone.debug.test/androidx.test.runner.AndroidJUnitRunner`.
 */
@RunWith(AndroidJUnit4::class)
class PaymentLinkV2NativeTest {

    private class Vector(val secret: String, val height: Long, val spend: String, val address: String, val id: String)

    private val vector1 = Vector(
        "000102030405060708090a0b0c0d0e0f", 150,
        "6a5bd15ceb3ca1dc24f7056feabfb1b825296bc5b26cfdcff8ad8362145ce908",
        "49brhubHMBe7enmsCDmGAJi63rX3uaGutSTf5afWoCeyDWsaXDhXwuN9WYB19H9dZeRGaYeZ74YpqX4qKGCMcyGP9bv31Hz",
        "de4b02dc06db96f7"
    )

    private val vector2 = Vector(
        "0102030405060708090a0b0c0d0e0f10", 3_500_000,
        "c00d264da9c90c7ed19068adf9a54b97ed5462acadae8a6967b62cfdd0229d00",
        "48vAo8XgJkBXtD3HqhVCvF9vpavqBuGK2WSPXTQSMeyXAy6xzEmgFHZiwkKYcnNv2fDsW9S31nnpHN6J3pMGrRiTR3NC7nL",
        "a6e2ad8e98af4322"
    )

    private val vector2Wrong =
        "48vAo8XgJkBXtD3HqhVCvF9vpavqBuGK2WSPXTQSMeyXAx8P6Ap3hRX7Li7dnM4KsM6Bs7zNgVaF71rcS7ny1K1nNYDGj6Y"

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ids = mutableListOf<String>()

    @After
    fun tearDown() {
        ids.forEach { EnvelopeFiles.delete(context, it) }
    }

    /** A fresh envelope id (16 hex characters) for a restore check. */
    private fun testId(): String = UUID.randomUUID().toString().replace("-", "").take(16).also { ids += it }

    private fun check(v: Vector) {
        val link = PaymentLink.v2(hexToBytes(v.secret)!!, v.height)!!
        assertEquals(v.id, link.id)

        // The envelope runtime's restore check: wallet2 recoveryWallet from the words.
        val restored = EnvelopeFactory.restoredAddress(context, testId(), link.words, link.height)
        assertEquals("wallet2 restore", v.address, restored)

        // The address the restore must match before funding, and the keys behind it.
        val validated = SeedValidation.validate(link.words, SeedType.ELECTRUM_25)
        assertEquals("SeedValidation", v.address, validated.primaryAddress)
        val keys = MoneroKit.getKeys(Seed.Electrum(link.words, ""))
        assertEquals("spend key", v.spend, keys.privateSpendKey)
        assertEquals("MoneroKit.getAddress", v.address, MoneroKit.getAddress(Seed.Electrum(link.words, ""), 0, 0))
        assertNotEquals(vector2Wrong, restored)
    }

    @Test
    fun vector1RestoresToTheSpecAddress() = check(vector1)

    @Test
    fun vector2RestoresToTheSpecAddress() = check(vector2)

    /**
     * wallet2 reduces what it restores and hashes the reduced spend key for the
     * view key, so the words of the unreduced k open the same wallet, never the
     * spec's "wrong" one. The app never makes such words.
     */
    @Test
    fun unreducedKWordsOpenTheSameWalletInWallet2() {
        val k = EnvelopeV2.k(hexToBytes(vector2.secret)!!)
        val words = LegacyWords.encode(k)
        val dir = File(context.filesDir, "v2test-unreduced-${System.nanoTime()}").apply { mkdirs() }
        try {
            val wallet = WalletManager.getInstance().recoveryWallet(File(dir, "w"), "", words.joinToString(" "), "", vector2.height)
            try {
                assertTrue("wallet2 recovery failed: ${wallet.status}", wallet.status.isOk)
                assertEquals(vector2.address, wallet.address)
                assertEquals(vector2.spend, wallet.secretSpendKey)
            } finally {
                wallet.close()
            }
        } finally {
            dir.deleteRecursively()
        }
        // The app's own address check: either it rejects the words, or it gives the same wallet.
        val own = runCatching { SeedValidation.validate(words, SeedType.ELECTRUM_25).primaryAddress }.getOrNull()
        assertTrue("SeedValidation gave $own", own == null || own == vector2.address)
    }

    /** A new link goes through the same checks [EnvelopeFactory.create] runs before funding. */
    @Test
    fun aNewLinkPassesTheRestoreCheck() {
        val draft = EnvelopeFactory.create(context, 1_000_000_000, 500, 1_800_000_000)
        ids += draft.link.id
        assertEquals(2, draft.link.version)
        assertEquals(draft.address, SeedValidation.validate(draft.link.words, SeedType.ELECTRUM_25).primaryAddress)
        assertTrue(draft.link.url().length <= 55)
        assertEquals(draft.link, (PaymentLinkCodec.parse(draft.link.url()) as PaymentLinkParse.Valid).link)
    }
}
