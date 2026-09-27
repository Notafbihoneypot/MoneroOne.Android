package one.monero.moneroone.core.wallet

/** A rebuild selects unused files and keeps every prior file available for recovery. */
object WalletRecovery {
    fun rebuild(
        wallet: WalletInfo,
        seedWords: List<String>,
        reservedIds: Set<String>,
        filesExist: (String) -> Boolean
    ): WalletInfo {
        var count = wallet.syncResetCount.coerceAtLeast(0)
        var nextId: String
        do {
            check(count < Int.MAX_VALUE) { "Wallet reset count exhausted" }
            nextId = WalletCacheIds.derivedWalletId(seedWords, ++count)
        } while (nextId in reservedIds || nextId in wallet.allCacheIds || filesExist(nextId))
        return wallet.copy(
            syncResetCount = count,
            derivedWalletId = nextId,
            retainedCacheIds = (wallet.retainedCacheIds + listOfNotNull(wallet.derivedWalletId)).distinct()
        )
    }
}
