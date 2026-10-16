package one.monero.moneroone.paymentlinks

import io.horizontalsystems.monerokit.MoneroKit
import one.monero.moneroone.core.wallet.SeedValidation
import one.monero.moneroone.core.wallet.WalletInfo

/**
 * A wallet a claim or a reclaim can sweep into (iOS parity). The open wallet takes the
 * sweep at its live receive address. A closed wallet takes it at its saved main address
 * and stays closed: the app keeps the open wallet, and the closed one sees the money
 * when it next syncs.
 */
data class ClaimDestination(
    /** WalletInfo.id. */
    val walletId: String,
    val name: String,
    /** Where the sweep goes. Null while the open wallet's addresses load. */
    val address: String?,
    /** The wallet open now. */
    val isOpen: Boolean,
    /** The kit wallet (cache id) the wallet opens with, so a sweep the app loses is found there. */
    val kitWalletId: String?
)

object ClaimDestinations {
    /**
     * The wallets a link can sweep into, in wallet order: the open wallet, and each closed
     * wallet whose saved main address passes [isValidAddress]. Android wallets are all on
     * mainnet, as the links are. View-only wallets can receive, so they count. A wallet
     * with no saved address (not opened since it was added) is left out.
     */
    fun eligible(
        wallets: List<WalletInfo>,
        openWalletId: String?,
        liveAddress: String?,
        isValidAddress: (String) -> Boolean
    ): List<ClaimDestination> = wallets.mapNotNull { wallet ->
        if (wallet.id == openWalletId) {
            ClaimDestination(wallet.id, wallet.name, liveAddress, isOpen = true, kitWalletId = wallet.derivedWalletId)
        } else {
            wallet.cachedPrimaryAddress?.takeIf { it.isNotEmpty() && isValidAddress(it) }?.let {
                ClaimDestination(wallet.id, wallet.name, it, isOpen = false, kitWalletId = wallet.derivedWalletId)
            }
        }
    }

    /**
     * Where a sweep goes until the user picks: a claim goes to the open wallet; a reclaim
     * to the wallet that funded the link while it is eligible, else to the open wallet.
     */
    fun default(eligible: List<ClaimDestination>, reclaim: Boolean, fundingWalletId: String?): ClaimDestination? {
        val funding = fundingWalletId?.takeIf { reclaim }?.let { id -> eligible.firstOrNull { it.walletId == id } }
        return funding ?: eligible.firstOrNull { it.isOpen }
    }

    /** The wallet the user picked while it is still eligible, else [default]. */
    fun chosen(
        eligible: List<ClaimDestination>,
        pickedWalletId: String?,
        reclaim: Boolean,
        fundingWalletId: String?
    ): ClaimDestination? =
        pickedWalletId?.let { id -> eligible.firstOrNull { it.walletId == id } } ?: default(eligible, reclaim, fundingWalletId)

    /** A saved main address a sweep can go to: a mainnet main address without a null key that wallet2 accepts. */
    fun isSavedMainAddress(address: String): Boolean =
        SeedValidation.isPlausiblePrimaryAddress(address) && runCatching { MoneroKit.validateAddress(address) }.isSuccess
}
