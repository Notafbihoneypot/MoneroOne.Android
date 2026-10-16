package one.monero.moneroone.paymentlinks

import one.monero.moneroone.core.wallet.SeedValidation
import one.monero.moneroone.core.wallet.WalletInfo
import one.monero.moneroone.core.wallet.WalletSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wallet a claim or a reclaim sweeps into (iOS parity: the claim sheet's wallet menu). */
class ClaimDestinationsTest {

    /** A well-formed mainnet main address: "4" and 94 base58 characters. */
    private fun mainAddress(fill: Char) = "4" + "A" + fill.toString().repeat(93)

    private val mainAddr = mainAddress('B')
    private val savingsAddr = mainAddress('C')
    private val coldAddr = mainAddress('D')
    private val liveSubaddress = "8" + "E".repeat(94)

    private val valid: (String) -> Boolean = SeedValidation::isPlausiblePrimaryAddress

    private fun wallet(id: String, name: String, address: String?, source: WalletSource = WalletSource.BIP39) =
        WalletInfo(id = id, name = name, source = source, cachedPrimaryAddress = address, derivedWalletId = "cache-$id")

    private val main = wallet("main", "Main", mainAddr)
    private val savings = wallet("savings", "Savings", savingsAddr)

    @Test
    fun theOpenWalletUsesItsLiveReceiveAddress() {
        val list = ClaimDestinations.eligible(listOf(main, savings), "main", liveSubaddress, valid)
        val open = list.single { it.walletId == "main" }
        assertTrue(open.isOpen)
        assertEquals(liveSubaddress, open.address)
        assertEquals("cache-main", open.kitWalletId)
    }

    @Test
    fun aClosedWalletUsesItsSavedMainAddress() {
        val list = ClaimDestinations.eligible(listOf(main, savings), "main", liveSubaddress, valid)
        val closed = list.single { it.walletId == "savings" }
        assertFalse(closed.isOpen)
        assertEquals(savingsAddr, closed.address)
        assertEquals("cache-savings", closed.kitWalletId)
    }

    @Test
    fun theOpenWalletStaysWhileItsAddressesLoad() {
        val list = ClaimDestinations.eligible(listOf(main), "main", null, valid)
        assertEquals(listOf("main"), list.map { it.walletId })
        assertNull(list.single().address)
    }

    @Test
    fun closedWalletsWithoutAUsableSavedAddressAreLeftOut() {
        val wallets = listOf(
            main,
            wallet("none", "Never opened", null),
            wallet("empty", "Empty", ""),
            wallet("burn", "Null key", "4" + "1".repeat(94)),
            wallet("sub", "Subaddress", liveSubaddress),
            wallet("stagenet", "Stagenet", "5" + "B".repeat(94)),
            savings
        )
        val list = ClaimDestinations.eligible(wallets, "main", liveSubaddress, valid)
        assertEquals(listOf("main", "savings"), list.map { it.walletId })
    }

    @Test
    fun theValidatorDecidesForClosedWalletsOnly() {
        // wallet2 rejects Savings' saved address; the open wallet's live address is not checked here.
        val list = ClaimDestinations.eligible(listOf(main, savings), "main", liveSubaddress) { it != savingsAddr }
        assertEquals(listOf("main"), list.map { it.walletId })
    }

    @Test
    fun viewOnlyWalletsCanReceive() {
        val watch = wallet("watch", "Watch", coldAddr, WalletSource.VIEW_ONLY)
        val list = ClaimDestinations.eligible(listOf(main, watch), "main", liveSubaddress, valid)
        assertEquals(listOf("main", "watch"), list.map { it.walletId })
    }

    @Test
    fun aClaimDefaultsToTheOpenWallet() {
        val list = ClaimDestinations.eligible(listOf(savings, main), "main", liveSubaddress, valid)
        assertEquals("main", ClaimDestinations.default(list, reclaim = false, fundingWalletId = null)?.walletId)
        // A claim ignores any funding wallet.
        assertEquals("main", ClaimDestinations.default(list, reclaim = false, fundingWalletId = "savings")?.walletId)
    }

    @Test
    fun aReclaimDefaultsToTheFundingWallet() {
        val list = ClaimDestinations.eligible(listOf(main, savings), "main", liveSubaddress, valid)
        val chosen = ClaimDestinations.default(list, reclaim = true, fundingWalletId = "savings")
        assertEquals("savings", chosen?.walletId)
        assertEquals(savingsAddr, chosen?.address)
    }

    @Test
    fun aReclaimFallsBackToTheOpenWallet() {
        val noAddress = wallet("savings", "Savings", null)
        val list = ClaimDestinations.eligible(listOf(main, noAddress), "main", liveSubaddress, valid)
        // The funding wallet has no saved address, or it was removed.
        assertEquals("main", ClaimDestinations.default(list, reclaim = true, fundingWalletId = "savings")?.walletId)
        assertEquals("main", ClaimDestinations.default(list, reclaim = true, fundingWalletId = "gone")?.walletId)
    }

    @Test
    fun aPickHoldsWhileTheWalletIsEligible() {
        val list = ClaimDestinations.eligible(listOf(main, savings), "main", liveSubaddress, valid)
        assertEquals("savings", ClaimDestinations.chosen(list, "savings", reclaim = false, fundingWalletId = null)?.walletId)
        assertEquals("main", ClaimDestinations.chosen(list, "main", reclaim = true, fundingWalletId = "savings")?.walletId)
        // A pick that left the list (wallet removed) goes back to the default.
        assertEquals("main", ClaimDestinations.chosen(list, "gone", reclaim = false, fundingWalletId = null)?.walletId)
    }

    @Test
    fun noOpenWalletNoDefault() {
        val list = ClaimDestinations.eligible(listOf(savings), null, null, valid)
        assertNull(ClaimDestinations.default(list, reclaim = false, fundingWalletId = null))
    }
}
