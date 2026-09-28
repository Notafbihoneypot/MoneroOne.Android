package one.monero.moneroone.ui.screens.transactions

import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.model.Transfer
import one.monero.moneroone.core.wallet.ReceiveAddressLogic
import io.horizontalsystems.monerokit.data.Subaddress
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class TransactionHistoryLogicTest {
    private fun tx(incoming: Boolean = true, index: Int = 0, amount: Long = 1000, fee: Long = 10, failed: Boolean = false) =
        TransactionInfo(if (incoming) 0 else 1, false, failed, amount, fee, 50, "hash-$incoming-$index-$amount", 100, "", 0, index, 12, 0, "", emptyList())
    @Test fun `totals include sent fees exclude failures and do not overflow long`() {
        val totals = TransactionHistoryLogic.totals(listOf(tx(amount = Long.MAX_VALUE), tx(amount = 1), tx(false), tx(false, failed = true)))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE, totals.received)
        assertEquals(BigInteger.valueOf(1010), totals.sent)
        assertEquals(3, totals.count)
    }
    @Test fun `address filter excludes outgoing and other accounts`() {
        val incoming = tx(index = 2)
        val otherAccount = tx(index = 2).apply { accountIndex = 1 }
        assertEquals(listOf(incoming), TransactionHistoryLogic.filter(listOf(incoming, tx(false, 2), otherAccount), TransactionFilter.ALL, 2, "", emptyList()))
    }
    @Test fun `search includes named addresses and outgoing recipients`() {
        val incoming = tx(index = 2)
        val rows = ReceiveAddressLogic.rows(listOf(Subaddress(0, 2, "receive-address", "")), listOf(incoming), mapOf(2 to "🎁 Gifts"))
        assertEquals(listOf(incoming), TransactionHistoryLogic.filter(listOf(incoming), TransactionFilter.ALL, null, "gift", rows))
        val sent = tx(false).apply { transfers = listOf(Transfer(1000, "recipient-address")) }
        assertEquals(listOf(sent), TransactionHistoryLogic.filter(listOf(sent), TransactionFilter.ALL, null, "recipient", rows))
    }
    @Test fun `failed transaction never appears as pending`() {
        val failed = tx(failed = true).apply { confirmations = 0; isPending = true }
        assertTrue(TransactionHistoryLogic.filter(listOf(failed), TransactionFilter.PENDING, null, "", emptyList()).isEmpty())
    }
    @Test fun `missing historical quote suppresses complete fiat totals`() {
        val first = tx(); val later = tx(false).apply { timestamp = 200 }
        assertNull(TransactionHistoryLogic.fiatTotals(listOf(first, later)) { if (it == 100L) 200.0 else null })
        val totals = TransactionHistoryLogic.fiatTotals(listOf(first, later)) { 200.0 }!!
        assertEquals(0, totals.sent.compareTo(java.math.BigDecimal("0.000000202")))
    }
    @Test fun `copy all carries recipients historical value and marks transaction key sensitive`() {
        val sent = tx(false).apply { transfers = listOf(Transfer(1000, "recipient")) }
        val fields = TransactionHistoryLogic.details(sent, emptyList(), "Sep 28", "$2.00", "$3.00", "abc")
        val text = TransactionHistoryLogic.copyAll(fields)
        assertTrue(text.contains("Sent to: recipient"))
        assertTrue(text.contains("Value when sent: $2.00"))
        assertTrue(text.contains("Value today: $3.00"))
        assertEquals(listOf("abc"), fields.filter { it.secret }.map { it.value })
        assertTrue(TransactionHistoryLogic.details(tx(), emptyList(), "date", null, null, null).none { it.secret })
    }
    @Test fun `missing recipients are stated instead of substituted with our receive address`() {
        val fields = TransactionHistoryLogic.details(tx(false), emptyList(), "date", null, null, null)
        assertTrue(fields.single { it.label == "Recipient" }.value.contains("not available", ignoreCase = true))
    }
}
