package one.monero.moneroone.paymentlinks

import one.monero.moneroone.core.wallet.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Test

class PaymentLinkSafetyTest {
    private fun sent() = SentLink(
        id = "0123456789abcdef", height = 100, amount = 1, expiry = 1_900_000_000,
        createdAtMillis = 1, walletId = "funding-wallet", envelopeAddress = "4env",
        feeReserve = 1, state = SentState.CREATED, secret = "000102030405060708090a0b0c0d0e0f"
    )

    @Test
    fun `a saved funding attempt cannot be funded again after restart or failure`() {
        val prefs = FakeSharedPreferences()
        val store = PaymentLinkStore(prefs)
        assertTrue(store.createSent(sent()))
        store.updateSent(sent().id) { it.copy(state = SentState.NOT_FUNDED) }
        val reopened = PaymentLinkStore(prefs)
        assertFalse(reopened.createSent(sent()))
        assertEquals(SentState.NOT_FUNDED, reopened.sent(sent().id)?.state)
        assertEquals(sent().secret, reopened.sent(sent().id)?.secret)
    }

    @Test
    fun `unreadable funded records cannot be overwritten by a new link`() {
        val prefs = FakeSharedPreferences()
        val damaged = "[{\"id\":\"funded-link\",\"secret\":\"retained-recovery-data\"}"
        prefs.edit().putString("paymentlinks.sent.v1", damaged).commit()
        val store = PaymentLinkStore(prefs)
        assertFalse(store.createSent(sent()))
        assertFalse(store.putSent(sent()))
        assertEquals(damaged, prefs.getString("paymentlinks.sent.v1", null))
        assertTrue(store.sent.value.isEmpty())
    }

    @Test
    fun `unreadable received records are preserved when another link is opened`() {
        val prefs = FakeSharedPreferences()
        val damaged = "unreadable received recovery data"
        prefs.edit().putString("paymentlinks.received.v1", damaged).commit()
        val store = PaymentLinkStore(prefs)
        val received = ReceivedLink(sent().id, null, 100, 0, 0, 1, secret = sent().secret)
        assertFalse(store.putReceived(received))
        assertEquals(damaged, prefs.getString("paymentlinks.received.v1", null))
    }

    @Test
    fun `claimed links retain recovery secrets after reopening storage`() {
        val prefs = FakeSharedPreferences()
        val store = PaymentLinkStore(prefs)
        val received = ReceivedLink(sent().id, null, 100, 0, 0, 1, secret = sent().secret,
            state = ReceivedState.CLAIMED, claimTxid = "mined-claim")
        assertTrue(store.putReceived(received))
        assertEquals(received.secret, PaymentLinkStore(prefs).received(received.id)?.secret)
    }
}
