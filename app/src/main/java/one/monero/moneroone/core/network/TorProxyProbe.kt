package one.monero.moneroone.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket

object TorProxyProbe {
    /** Tests the SOCKS service, not whether its Tor circuit has finished bootstrapping. */
    suspend fun available(address: String): Boolean = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(address) ?: return@withContext false
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = 3000
                socket.connect(endpoint.socketAddress(), 3000)
                socket.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
                val input = socket.getInputStream()
                input.read() == 5 && input.read() == 0
            }
        }.getOrDefault(false)
    }
}
