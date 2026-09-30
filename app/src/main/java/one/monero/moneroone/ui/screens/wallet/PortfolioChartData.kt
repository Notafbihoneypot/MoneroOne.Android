package one.monero.moneroone.ui.screens.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.horizontalsystems.monerokit.model.TransactionInfo

/** The wallet's ledger for balance history, rebuilt when the balance or its transactions change. */
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
