package one.monero.moneroone.core.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The memo that the user writes on Send reaches the kit's send, which saves
 * it as a local wallet2 note, as on iOS. These tests use their own kit, so no
 * transaction is made.
 */
class SendRequestTest {

    private val address = "4" + "A".repeat(94)

    /** One kit send call, as the kit gets it. */
    private data class KitCall(val amount: Long, val address: String, val memo: String?, val sweepAll: Boolean)

    private val calls = mutableListOf<KitCall>()
    private val kit = KitSend { amount, address, memo, sweepAll -> calls += KitCall(amount, address, memo, sweepAll) }

    @Test
    fun theMemoReachesTheKitSend() {
        SendRequest.of(address, amount = 1_500_000_000_000L, memo = "Coffee with Ana", sweepAll = false).sendWith(kit)
        assertEquals(listOf(KitCall(1_500_000_000_000L, address, "Coffee with Ana", false)), calls)
    }

    @Test
    fun sendAllKeepsTheMemo() {
        SendRequest.of(address, amount = 0L, memo = "Rent", sweepAll = true).sendWith(kit)
        assertEquals(listOf(KitCall(0L, address, "Rent", true)), calls)
    }

    @Test
    fun anEmptyMemoFieldSendsNoMemo() {
        SendRequest.of(address, amount = 1L, memo = "", sweepAll = false).sendWith(kit)
        assertNull(calls.single().memo)
    }

    @Test
    fun theMemoGoesAsTyped() {
        // iOS sends the field text with no change: spaces and line breaks stay.
        val typed = "  Invoice 42\nPaid in full  "
        SendRequest.of(address, amount = 1L, memo = typed, sweepAll = false).sendWith(kit)
        assertEquals(typed, calls.single().memo)
    }
}
