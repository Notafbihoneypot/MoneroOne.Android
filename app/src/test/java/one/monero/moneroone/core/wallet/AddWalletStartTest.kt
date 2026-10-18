package one.monero.moneroone.core.wallet

import io.horizontalsystems.monerokit.MoneroKit.SyncError
import io.horizontalsystems.monerokit.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AddWalletStartTest {

    private val seedPrimary =
        "494docVv9psYhYnqqDjbemaz3veCWczoUKfwiELFQ36W4TXdSg7RroeBvTxKagSdt1BYhne62ESAzT7H2TqVTkrJ7m1pCoE"
    private val otherPrimary =
        "42ey1afDFnn4886T7196doS9GPMzexD9gXpsZJDwVjeRVdFCSoHnv7KPbBeGpzJBzHRCAs9UxqeoyFQMYbqSWYTfJJQAWDm"
    private val nullKeyPrimary =
        "41fJjQDhryD11111111111111111111111111111111112N1GuTZeagfRbbKcALdcZev4QXGGuoLh2x36LhaxLSxCc2YDhi"
    private val connecting = SyncState.Connecting(waiting = false)

    @Test fun `an open file that derives from the stored seed is verified`() =
        assertTrue(AddWalletStart.walletFileVerified(true, seedPrimary, seedPrimary, connecting))

    @Test fun `a file that is not open, or not read yet, is not`() {
        assertFalse(AddWalletStart.walletFileVerified(false, seedPrimary, seedPrimary, connecting))
        assertFalse(AddWalletStart.walletFileVerified(true, null, seedPrimary, connecting))
    }

    @Test fun `a file with other keys, a burn address or no seed address is not`() {
        assertFalse(AddWalletStart.walletFileVerified(true, otherPrimary, seedPrimary, connecting))
        assertFalse(AddWalletStart.walletFileVerified(true, nullKeyPrimary, nullKeyPrimary, connecting))
        assertFalse(AddWalletStart.walletFileVerified(true, seedPrimary, null, connecting))
    }

    @Test fun `a wallet-level start error is not`() {
        val invalidWallet = SyncState.NotSynced(SyncError.InvalidNode("Invalid wallet"))
        val recoveryError = SyncState.NotSynced(SyncError.StartError("Wallet recovery error: bad seed"))
        assertFalse(AddWalletStart.walletFileVerified(true, seedPrimary, seedPrimary, invalidWallet))
        assertFalse(AddWalletStart.walletFileVerified(true, seedPrimary, seedPrimary, recoveryError))
    }

    @Test fun `a node error after the open does not hold the screen back`() {
        val noNode = SyncState.NotSynced(SyncError.StartError("Status_Error: no connection to daemon"))
        assertTrue(AddWalletStart.walletFileVerified(true, seedPrimary, seedPrimary, noNode))
    }

    @Test fun `the screen moves on while the start still runs`() = runBlocking {
        val opened = AtomicBoolean(false)
        val nodeContactDone = CompletableDeferred<Unit>()
        var startDone = false
        var startDoneAtSignal: Boolean? = null
        val result = withTimeout(5_000) {
            AddWalletStart.startSignallingOpen(
                start = {
                    opened.set(true)
                    nodeContactDone.await()
                    startDone = true
                    7
                },
                opened = { opened.get() },
                onOpened = {
                    startDoneAtSignal = startDone
                    nodeContactDone.complete(Unit)
                },
                pollMs = 5
            )
        }
        assertEquals(7, result)
        assertEquals(false, startDoneAtSignal)
        assertTrue(startDone)
    }

    @Test fun `no signal when the start ends before the file is verified`() = runBlocking {
        val signals = AtomicInteger()
        val result = withTimeout(5_000) {
            AddWalletStart.startSignallingOpen(
                start = { delay(30); false },
                opened = { false },
                onOpened = { signals.incrementAndGet() },
                pollMs = 5
            )
        }
        assertFalse(result)
        assertEquals(0, signals.get())
    }

    @Test fun `the signal comes once`() = runBlocking {
        val signals = AtomicInteger()
        withTimeout(5_000) {
            AddWalletStart.startSignallingOpen(
                start = { delay(60) },
                opened = { true },
                onOpened = { signals.incrementAndGet() },
                pollMs = 5
            )
        }
        assertEquals(1, signals.get())
    }

    @Test fun `a start that throws still throws`() = runBlocking {
        try {
            AddWalletStart.startSignallingOpen<Unit>(
                start = { throw IllegalStateException("start failed") },
                opened = { false },
                onOpened = {},
                pollMs = 5
            )
            fail("expected the start's exception")
        } catch (e: IllegalStateException) {
            assertEquals("start failed", e.message)
        }
    }
}
