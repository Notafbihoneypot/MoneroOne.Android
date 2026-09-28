package one.monero.moneroone.ui.screens.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.horizontalsystems.monerokit.model.TransactionInfo

/** Keep the wallet's ledger across chart mode switches, and reset it on a wallet change. */
@Composable
internal fun rememberBalanceLedger(balance: Long, transactions: List<TransactionInfo>, walletSessionId: Long): BalanceLedger =
    remember(balance, transactions, walletSessionId) {
        BalanceLedger(balance, transactions.mapNotNull { it.toBalanceChange() })
    }

/** The kit's displayed balance excludes pending incoming transactions. */
private fun TransactionInfo.toBalanceChange(): BalanceChange? = BalanceChange.of(
    id = hash,
    incoming = direction == TransactionInfo.Direction.Direction_In,
    isPending = isPending,
    isFailed = isFailed,
    amount = amount,
    fee = fee,
    timestampMs = timestamp * 1000
)
