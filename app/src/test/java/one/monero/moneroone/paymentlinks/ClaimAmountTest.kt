package one.monero.moneroone.paymentlinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a claim delivers: the balance less the claim sweep's fee estimate (iOS EnvelopeRecord.netAmount). */
class ClaimAmountTest {

    private val secret = "000102030405060708090a0b0c0d0e0f"

    private fun received(amount: Long = 0, check: EnvelopeSnapshot? = null, net: Long? = null, state: ReceivedState = ReceivedState.PENDING) =
        ReceivedLink(
            id = "r", words = null, height = 100, amount = amount, expiry = 0, receivedAtMillis = 1,
            state = state, lastCheck = check, secret = secret, netAmount = net
        )

    private fun check(balance: Long, fee: Long? = null, unlocked: Long = balance) =
        EnvelopeSnapshot(1, true, true, 10, balance, unlocked, sweepFee = fee)

    @Test
    fun netIsTheBalanceLessTheFee() {
        assertEquals(20_122_200_000L, ReceivedLink.net(27_219_200_000, 7_097_000_000))
        // A fee that takes it all leaves nothing to claim.
        assertNull(ReceivedLink.net(1_000, 1_000))
        assertNull(ReceivedLink.net(500, 1_000))
    }

    @Test
    fun aCheckWithAFeeSetsTheNetAmount() {
        val link = received().noting(check(27_219_200_000, fee = 30_740_000))
        assertEquals(27_188_460_000L, link.netAmount)
        assertEquals(27_188_460_000L, link.claimAmount)
        assertEquals(27_219_200_000L, link.knownAmount)
    }

    @Test
    fun aCheckWithoutAFeeKeepsTheNetAmountWhileTheBalanceHolds() {
        val checked = received().noting(check(27_219_200_000, fee = 30_740_000))
        assertEquals(27_188_460_000L, checked.noting(check(27_219_200_000)).netAmount)
        // The balance moved: the old net amount no longer holds, the balance stands in.
        val moved = checked.noting(check(40_000_000_000))
        assertNull(moved.netAmount)
        assertEquals(40_000_000_000L, moved.claimAmount)
    }

    @Test
    fun aFailedEstimateOnAMovedBalanceDropsTheNetAmount() {
        val checked = received().noting(check(27_219_200_000, fee = 30_740_000))
        assertNull(checked.noting(check(0, fee = null)).netAmount)
    }

    @Test
    fun claimAmountFallsBackToTheV1AmountThenTheBalance() {
        // A v1 link carries its amount: the sender put the claim fee on top.
        assertEquals(20_000_000_000L, received(amount = 20_000_000_000, check = check(27_219_200_000)).claimAmount)
        assertEquals(27_219_200_000L, received(check = check(27_219_200_000)).claimAmount)
        assertNull(received().claimAmount)
        assertEquals(5L, received(amount = 20_000_000_000, net = 5).claimAmount)
    }

    @Test
    fun oldRecordsWithABalanceNeedANetAmount() {
        assertTrue(received(check = check(27_219_200_000)).needsNetAmount)
        assertFalse(received(check = check(27_219_200_000), net = 1).needsNetAmount)
        // Not checked, empty, a v1 link with its amount, claimed, or its secret gone: nothing to fill.
        assertFalse(received().needsNetAmount)
        assertFalse(received(check = check(0)).needsNetAmount)
        assertFalse(received(amount = 20_000_000_000, check = check(27_219_200_000)).needsNetAmount)
        assertFalse(received(check = check(27_219_200_000), state = ReceivedState.CLAIMED).needsNetAmount)
        assertFalse(received(check = check(27_219_200_000)).forgettingSecret().needsNetAmount)
    }

    @Test
    fun openLinksSumWhatTheClaimsDeliver() {
        val a = received(check = check(27_219_200_000), net = 27_188_460_000)
        val b = received(check = check(10_000_000_000)).copy(id = "s")
        val open = OpenLinkRules.openLinks(emptyList(), listOf(a, b), "w", 0)
        assertEquals(37_188_460_000L, open.receivedAmount)
    }

    @Test
    fun oldStoredRecordsReadWithoutANetAmount() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = """{"id":"r","words":null,"height":100,"amount":0,"expiry":0,"receivedAtMillis":1,"secret":"$secret",""" +
            """"lastCheck":{"checkedAt":1,"fundingSeen":true,"fundingMined":true,"confirmations":10,"balance":5,"unlocked":5}}"""
        val link = json.decodeFromString(ReceivedLink.serializer(), old)
        assertNull(link.netAmount)
        assertNull(link.lastCheck?.sweepFee)
        assertTrue(link.needsNetAmount)
    }
}
