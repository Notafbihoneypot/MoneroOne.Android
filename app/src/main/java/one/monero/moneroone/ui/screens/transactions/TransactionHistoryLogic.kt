package one.monero.moneroone.ui.screens.transactions

import io.horizontalsystems.monerokit.model.TransactionInfo
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.core.wallet.ReceiveAddressLogic
import one.monero.moneroone.core.wallet.ReceiveAddressRow
import one.monero.moneroone.data.util.XmrFormat
import java.math.BigDecimal
import java.math.BigInteger

data class TransactionTotals(val count: Int, val received: BigInteger, val sent: BigInteger)
data class FiatTotals(val received: BigDecimal, val sent: BigDecimal)
data class TransactionDetailField(val label: String, val value: String, val secret: Boolean = false)

object TransactionHistoryLogic {
    fun filter(transactions: List<TransactionInfo>, filter: TransactionFilter, receivingIndex: Int?, query: String,
               addresses: List<ReceiveAddressRow>): List<TransactionInfo> = transactions.filter { tx ->
        val incoming = tx.direction == TransactionInfo.Direction.Direction_In
        val kindMatches = when (filter) {
            TransactionFilter.ALL -> true
            TransactionFilter.RECEIVED -> incoming
            TransactionFilter.SENT -> !incoming
            TransactionFilter.PENDING -> !tx.isFailed && (tx.isPending || tx.confirmations == 0L)
        }
        val addressMatches = receivingIndex == null || (incoming && tx.accountIndex == 0 && tx.addressIndex == receivingIndex)
        val row = if (incoming) addresses.firstOrNull { it.index == tx.addressIndex } else null
        val searchable = listOfNotNull(tx.hash, tx.notes, row?.name, row?.address?.address, tx.address) + tx.transfers.orEmpty().map { it.address }
        kindMatches && addressMatches && (query.isBlank() || searchable.any { it.contains(query.trim(), ignoreCase = true) })
    }.sortedWith(compareBy<TransactionInfo> { !it.isPending && it.confirmations > 0 }.thenByDescending { it.timestamp })

    /** Failed transactions contribute nothing; outgoing totals include fees. */
    fun totals(transactions: List<TransactionInfo>): TransactionTotals {
        var received = BigInteger.ZERO
        var sent = BigInteger.ZERO
        val counted = transactions.filterNot { it.isFailed }
        for (tx in counted) {
            val amount = BigInteger.valueOf(tx.amount)
            if (tx.direction == TransactionInfo.Direction.Direction_In) received += amount
            else sent += amount + BigInteger.valueOf(tx.fee)
        }
        return TransactionTotals(counted.size, received, sent)
    }

    /** No partial fiat sum is presented as a complete total. */
    fun fiatTotals(transactions: List<TransactionInfo>, priceAt: (Long) -> Double?): FiatTotals? {
        var received = BigDecimal.ZERO
        var sent = BigDecimal.ZERO
        for (tx in transactions.filterNot { it.isFailed }) {
            val price = priceAt(tx.timestamp)?.takeIf { it.isFinite() && it > 0 } ?: return null
            val incoming = tx.direction == TransactionInfo.Direction.Direction_In
            val atomic = BigInteger.valueOf(tx.amount) + if (incoming) BigInteger.ZERO else BigInteger.valueOf(tx.fee)
            val value = BigDecimal(atomic).movePointLeft(12) * BigDecimal.valueOf(price)
            if (incoming) received += value else sent += value
        }
        return FiatTotals(received, sent)
    }

    fun status(tx: TransactionInfo): String = when {
        tx.isFailed -> tr("Failed")
        tx.isPending || tx.confirmations == 0L -> tr("Pending")
        tx.confirmations < 10 -> tr("Locked (%s/10 confirmations)", tx.confirmations)
        else -> tr("Confirmed")
    }

    /** These same fields render the screen and Copy All, so the exported values cannot drift. */
    fun details(tx: TransactionInfo, addresses: List<ReceiveAddressRow>, date: String,
                atTime: String?, today: String?, txKey: String?): List<TransactionDetailField> = buildList {
        val incoming = tx.direction == TransactionInfo.Direction.Direction_In
        fun field(label: String, value: String?, secret: Boolean = false) {
            if (!value.isNullOrBlank()) add(TransactionDetailField(label, value, secret))
        }
        field(tr("Type"), tr(if (incoming) "Received" else "Sent"))
        field(tr("Amount"), "${if (incoming) "+" else "−"}${XmrFormat.format(tx.amount)} XMR")
        if (!incoming) field(tr("Fee"), "${XmrFormat.format(tx.fee)} XMR")
        field(tr(if (incoming) "Value when received" else "Value when sent"), atTime)
        field(tr("Value today"), today)
        field(tr("Status"), status(tx))
        field(tr("Confirmations"), tx.confirmations.toString())
        field(tr("Date"), date)
        field(tr("Block Height"), if (tx.blockheight > 0) tx.blockheight.toString() else tr("Pending"))
        field(tr("Memo"), tx.notes)
        field(tr("Transaction ID"), tx.hash)
        if (incoming) {
            val row = addresses.firstOrNull { it.index == tx.addressIndex && tx.accountIndex == 0 }
            val name = row?.name ?: ReceiveAddressLogic.name(tx.addressIndex, tx.subaddressLabel.orEmpty())
            field(tr("Received on (%s)", name), row?.address?.address ?: tx.address ?: tr("Unavailable"))
        } else {
            val destinations = tx.transfers.orEmpty().filter { !it.address.isNullOrBlank() }
            if (destinations.isEmpty()) field(tr("Recipient"), tx.address?.takeIf { it.isNotBlank() }
                ?: tr("Not available for transactions sent before this wallet was restored"))
            destinations.forEachIndexed { index, transfer ->
                val label = if (destinations.size == 1) tr("Sent to") else tr("Sent to (%s of %s)", index + 1, destinations.size)
                field(label, if (destinations.size == 1) transfer.address else "${transfer.address}\n${XmrFormat.format(transfer.amount)} XMR")
            }
        }
        field(tr("Transaction Key"), txKey, secret = true)
        field(tr("Block Explorer"), "https://xmrchain.net/tx/${tx.hash}")
    }

    fun copyAll(fields: List<TransactionDetailField>): String = fields.joinToString("\n") { "${it.label}: ${it.value}" }
}
