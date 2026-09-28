package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.data.Subaddress
import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.core.locale.pluralTr
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.data.util.XmrFormat
import java.math.BigInteger

data class AddressUsage(val payments: Int = 0, val received: BigInteger = BigInteger.ZERO)
data class ReceiveAddressRow(val address: Subaddress, val name: String, val labeled: Boolean, val usage: AddressUsage) {
    val index get() = address.addressIndex
    val description: String get() = if (usage.payments == 0) tr("Unused") else
        "${XmrFormat.format(usage.received)} XMR · ${pluralTr("%s payments", usage.payments)}"
}

/** Incoming payments, including the pool but excluding failed transactions. */
object ReceiveAddressLogic {
    const val WARNING_THRESHOLD = 150
    const val STOP_THRESHOLD = 190

    fun usage(transactions: List<TransactionInfo>): Map<Int, AddressUsage> {
        val usage = mutableMapOf<Int, AddressUsage>()
        for (tx in transactions.distinctBy { it.hash }) {
            if (tx.accountIndex != 0 || tx.direction != TransactionInfo.Direction.Direction_In || tx.isFailed) continue
            val old = usage[tx.addressIndex] ?: AddressUsage()
            usage[tx.addressIndex] = AddressUsage(old.payments + 1, old.received + BigInteger.valueOf(tx.amount))
        }
        return usage
    }

    fun name(index: Int, label: String = ""): String = when {
        index == 0 -> tr("Main Address")
        label.isNotBlank() && !Subaddress.DEFAULT_LABEL_FORMATTER.matcher(label).matches() -> label
        else -> tr("Subaddress #%s", index)
    }

    fun rows(addresses: List<Subaddress>, transactions: List<TransactionInfo>, labels: Map<Int, String>): List<ReceiveAddressRow> {
        val usage = usage(transactions)
        return addresses.filter { it.accountIndex == 0 && it.address.isNotBlank() }.distinctBy { it.addressIndex }
            .sortedByDescending { it.addressIndex }.map {
                val label = labels[it.addressIndex] ?: it.label
                ReceiveAddressRow(it, name(it.addressIndex, label), label.isNotBlank() &&
                    !Subaddress.DEFAULT_LABEL_FORMATTER.matcher(label).matches(), usage[it.addressIndex] ?: AddressUsage())
            }
    }

    fun unusedAfterLastUsed(rows: List<ReceiveAddressRow>): Int =
        ((rows.maxOfOrNull { it.index } ?: 0) - (rows.filter { it.usage.payments > 0 }.maxOfOrNull { it.index } ?: 0)).coerceAtLeast(0)

    fun creationWarning(rows: List<ReceiveAddressRow>): String? = when (val unused = unusedAfterLastUsed(rows)) {
        in STOP_THRESHOLD..Int.MAX_VALUE -> tr("Use one of your unused addresses first. A restore from the seed would miss payments to new ones.")
        in WARNING_THRESHOLD until STOP_THRESHOLD -> pluralTr("%s unused addresses in a row. A restore from the seed finds payments only up to 200 past the last used one.", unused)
        else -> null
    }

    /** A manual choice stays until it receives a new payment. Named addresses are reserved. */
    fun nextIndex(selected: Int, baseline: Int?, rows: List<ReceiveAddressRow>, rotate: Boolean): Int? {
        val current = rows.firstOrNull { it.index == selected }
        if (!rotate) return if (current != null) selected else 0
        if (current != null && baseline != null && current.usage.payments <= baseline) return selected
        val lastReserved = rows.filter { it.usage.payments > 0 || it.labeled }.maxOfOrNull { it.index } ?: 0
        return rows.filter { it.index > lastReserved && it.usage.payments == 0 && !it.labeled }.minOfOrNull { it.index }
    }
}
