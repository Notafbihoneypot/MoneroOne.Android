package one.monero.moneroone.core.wallet

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WalletRecoveryTest {
    private val words = listOf("test", "seed")
    private val original = WalletInfo(id = "wallet", name = "Wallet", derivedWalletId = "legacy-id")

    @Test
    fun `repeated rebuilds retain every previous cache and preserve wallet metadata`() {
        val first = WalletRecovery.rebuild(original, words, emptySet()) { false }
        val second = WalletRecovery.rebuild(first, words, emptySet()) { false }
        assertEquals(listOf("legacy-id", first.derivedWalletId), second.retainedCacheIds)
        assertEquals(2, second.syncResetCount)
        assertEquals(original.id, second.id)
        assertEquals(original.name, second.name)
        assertEquals(3, second.allCacheIds.size)
    }

    @Test
    fun `rebuild never reuses a file even if metadata forgot its owner`() {
        val occupied = WalletCacheIds.derivedWalletId(words, 1)
        val next = WalletRecovery.rebuild(original, words, emptySet()) { it == occupied }
        assertEquals(2, next.syncResetCount)
        assertNotEquals(occupied, next.derivedWalletId)
    }

    @Test
    fun `rebuild skips another wallet and its retained files`() {
        val reserved = (1..3).map { WalletCacheIds.derivedWalletId(words, it) }.toSet()
        val next = WalletRecovery.rebuild(original, words, reserved) { false }
        assertEquals(4, next.syncResetCount)
    }

    @Test
    fun `rebuild preserves original key and transaction cache bytes`() {
        val dir = Files.createTempDirectory("wallet-recovery-test").toFile()
        try {
            val cache = dir.resolve("legacy-id").apply { writeText("transaction keys") }
            val keys = dir.resolve("legacy-id.keys").apply { writeText("original spend keys") }
            WalletRecovery.rebuild(original, words, emptySet()) { id -> dir.resolve(id).exists() }
            assertEquals("transaction keys", cache.readText())
            assertEquals("original spend keys", keys.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `retained files survive store reload rename and another rebuild`() {
        val prefs = FakeSharedPreferences()
        val store = WalletStore(prefs)
        val first = WalletRecovery.rebuild(original, words, emptySet()) { false }
        store.addWallet(first)
        store.updateWallet(store.wallets().single().copy(name = "Renamed"))
        val reloaded = WalletStore(prefs).wallets().single()
        val second = WalletRecovery.rebuild(reloaded, words, emptySet()) { false }
        assertTrue(second.allCacheIds.containsAll(first.allCacheIds))
        assertEquals("Renamed", second.name)
    }

    @Test
    fun `older wallet rows decode without recovery metadata`() {
        val row = WalletStore.decodeWallets("""[{"id":"wallet","name":"Old"}]""").single()
        assertTrue(row.retainedCacheIds.isEmpty())
    }

    @Test
    fun `counter exhaustion cannot wrap around and reuse old files`() {
        assertThrows(IllegalStateException::class.java) {
            WalletRecovery.rebuild(original.copy(syncResetCount = Int.MAX_VALUE), words, emptySet()) { false }
        }
    }
}
