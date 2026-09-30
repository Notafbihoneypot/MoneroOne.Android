package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.data.Subaddress
import io.horizontalsystems.monerokit.model.TransactionInfo
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class ReceiveAddressLogicTest {
    private fun tx(index: Int, hash: String = "tx-$index", failed: Boolean = false, incoming: Boolean = true) =
        TransactionInfo(if (incoming) 0 else 1, true, failed, 500, 10, 0, hash, 100, "", 0, index, 0, 0, "", emptyList())
    private fun rows(indices: List<Int>, transactions: List<TransactionInfo> = emptyList(), labels: Map<Int, String> = emptyMap()) =
        ReceiveAddressLogic.rows(indices.map { Subaddress(0, it, "address-$it", "") }, transactions, labels)

    @Test fun `payment usage includes pool excludes failed outgoing other accounts and duplicate hashes`() {
        val otherAccount = tx(1, "other").apply { accountIndex = 1 }
        val usage = ReceiveAddressLogic.usage(listOf(tx(1), tx(1), tx(1, "failed", failed = true), tx(1, "send", incoming = false), otherAccount))
        assertEquals(AddressUsage(1, BigInteger.valueOf(500)), usage[1])
    }
    @Test fun `manual selection stays until that address gets a new payment`() {
        val old = rows(listOf(0, 1, 2), listOf(tx(1)))
        assertEquals(1, ReceiveAddressLogic.nextIndex(1, 1, old, true))
        val paid = rows(listOf(0, 1, 2), listOf(tx(1), tx(1, "next")))
        assertEquals(2, ReceiveAddressLogic.nextIndex(1, 1, paid, true))
    }
    @Test fun `rotation skips named and used addresses and never chooses primary`() {
        val list = rows(listOf(0, 1, 2, 3, 4), listOf(tx(2)), mapOf(3 to "🎁 Gifts"))
        assertEquals(4, ReceiveAddressLogic.nextIndex(2, null, list, true))
        assertNull(ReceiveAddressLogic.nextIndex(0, null, rows(listOf(0)), true))
    }
    @Test fun `rotation off keeps selection and only repairs missing addresses`() {
        val list = rows(listOf(0, 1), listOf(tx(1)))
        assertEquals(1, ReceiveAddressLogic.nextIndex(1, null, list, false))
        assertEquals(0, ReceiveAddressLogic.nextIndex(99, null, list, false))
    }
    @Test fun `manual primary selection is preserved until another payment`() {
        assertEquals(0, ReceiveAddressLogic.nextIndex(0, 0, rows(listOf(0, 1)), true))
        assertEquals(1, ReceiveAddressLogic.nextIndex(0, 0, rows(listOf(0, 1), listOf(tx(0))), true))
    }
    @Test fun `lookahead counts indices after last payment not number of rows or labels`() {
        val list = rows(listOf(0, 50, 240), listOf(tx(50)), mapOf(240 to "Reserved"))
        assertEquals(190, ReceiveAddressLogic.unusedAfterLastUsed(list))
        assertNotNull(ReceiveAddressLogic.creationWarning(list))
        assertNull(ReceiveAddressLogic.creationWarning(rows(listOf(0, 149))))
        assertNotNull(ReceiveAddressLogic.creationWarning(rows(listOf(0, 150))))
    }
    @Test fun `search finds a number with or without the hash, and names and addresses ignoring case and accents`() {
        val list = rows(listOf(0, 5, 12), labels = mapOf(12 to "☕ Café"))
        fun found(query: String) = list.filter { ReceiveAddressLogic.matchesSearch(it, query) }.map { it.index }
        assertEquals(listOf(12, 5, 0), found("  "))
        assertEquals(listOf(5), found("#5"))
        assertEquals(listOf(12), found("12"))
        assertEquals(listOf(12), found("cafe"))
        assertEquals(listOf(5), found("ADDRESS-5"))
        assertEquals(emptyList<Int>(), found("#7"))
    }
    @Test fun `talkback reads a named subaddress with its number and what it received`() {
        val list = rows(listOf(0, 3), listOf(tx(3)), mapOf(3 to "🎁 Gifts"))
        assertEquals("🎁 Gifts, subaddress 3, received 0.0000000005 XMR, 1 payment", ReceiveAddressLogic.spokenRow(list[0]))
        assertEquals("Main Address, unused", ReceiveAddressLogic.spokenRow(list[1]))
    }
    @Test fun `a label splits into its leading emoji and name and joins back as ios packs it`() {
        assertEquals("🎁" to "Gifts", ReceiveAddressLogic.splitLabel("🎁 Gifts"))
        assertEquals("👍🏽" to "Tips", ReceiveAddressLogic.splitLabel("👍🏽 Tips"))
        assertEquals("👨‍👩‍👧" to "Family", ReceiveAddressLogic.splitLabel("👨‍👩‍👧 Family"))
        assertEquals("🇺🇸" to "Travel", ReceiveAddressLogic.splitLabel("🇺🇸 Travel"))
        assertEquals("☕️" to "", ReceiveAddressLogic.splitLabel("☕️"))
        assertEquals("" to "1st shop", ReceiveAddressLogic.splitLabel("1st shop"))
        assertEquals("" to "#1 donations", ReceiveAddressLogic.splitLabel("#1 donations"))
        assertEquals("" to "", ReceiveAddressLogic.splitLabel(""))
        assertEquals("🎁 Gifts", ReceiveAddressLogic.joinLabel("🎁", "  Gifts "))
        assertEquals("🎁", ReceiveAddressLogic.joinLabel("🎁", " "))
        assertEquals("Gifts", ReceiveAddressLogic.joinLabel("", "Gifts"))
    }
    @Test fun `named addresses keep their name and default library timestamp labels do not reserve them`() {
        assertEquals("🎁 Gifts", rows(listOf(1), labels = mapOf(1 to "🎁 Gifts")).single().name)
        assertFalse(ReceiveAddressLogic.rows(listOf(Subaddress(0, 1, "address", "2026-09-28-12:00:00")), emptyList(), emptyMap()).single().labeled)
    }
}
