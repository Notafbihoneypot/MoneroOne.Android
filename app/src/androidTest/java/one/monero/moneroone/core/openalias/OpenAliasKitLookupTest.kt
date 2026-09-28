package one.monero.moneroone.core.openalias

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.monerokit.model.OpenAlias as KitOpenAlias
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Spec section 8.2, on a device: the kit's TXT lookup call. Only loopback
 * traffic. Never calls the wallet2 resolver.
 */
@RunWith(AndroidJUnit4::class)
class OpenAliasKitLookupTest {

    private val loopback = loopbackV4() ?: error("no IPv4 loopback")

    private fun lookup(name: String, port: Int, timeoutMs: Int): JSONObject =
        JSONObject(KitOpenAlias.lookupTxt(name, port, timeoutMs))

    /** A loopback port with nothing listening on it. */
    private fun closedPort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    @Test
    fun badArgumentsGiveError() {
        val port = closedPort()
        assertEquals("error", lookup("", port, 5_000).getString("status"))
        assertEquals("error", lookup("a".repeat(254), port, 5_000).getString("status"))
        assertEquals("error", lookup("donate.getmonero.org", 0, 5_000).getString("status"))
        assertEquals("error", lookup("donate.getmonero.org", 65_536, 5_000).getString("status"))
        assertEquals("error", lookup("donate.getmonero.org", port, 500).getString("status"))
        assertEquals("error", lookup("donate.getmonero.org", port, 60_001).getString("status"))
    }

    @Test
    fun closedPortGivesServfailFast() {
        val port = closedPort()
        val started = SystemClock.elapsedRealtime()
        val answer = lookup("donate.getmonero.org", port, 5_000)
        val ms = SystemClock.elapsedRealtime() - started
        assertEquals(answer.toString(), "ok", answer.getString("status"))
        assertEquals(answer.toString(), 2, answer.getInt("rcode"))
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test
    fun silentListenerTimesOut() {
        val server = ServerSocket(0, 16, loopback)
        val accepted: MutableList<Socket> = Collections.synchronizedList(mutableListOf())
        val acceptor = thread {
            try {
                while (true) accepted += server.accept()
            } catch (e: IOException) {
                // The listener closed.
            }
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val answer = lookup("donate.getmonero.org", server.localPort, 1_000)
            val ms = SystemClock.elapsedRealtime() - started
            assertEquals(answer.toString(), "timeout", answer.getString("status"))
            assertTrue("took $ms ms", ms < 2_000)
        } finally {
            server.close()
            synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
            acceptor.join(1_000)
        }
    }
}
