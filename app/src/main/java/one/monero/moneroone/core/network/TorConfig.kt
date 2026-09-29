package one.monero.moneroone.core.network

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

const val DEFAULT_TOR_PROXY = "127.0.0.1:9050"

data class ProxyEndpoint(val host: String, val port: Int) {
    val address: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
    fun socketAddress() = InetSocketAddress(InetAddress.getByName(host), port)
}

/** Numeric proxy endpoints keep configuration and native wallet routing identical, without DNS. */
fun parseProxyEndpoint(input: String): ProxyEndpoint? {
    val raw = input.trim().removePrefix("socks5://").removePrefix("socks://")
    if (raw.any { it.isWhitespace() } || raw.any { it in "/@?#%" }) return null
    val host: String
    val portText: String
    if (raw.startsWith('[')) {
        val end = raw.indexOf(']')
        if (end < 2 || raw.getOrNull(end + 1) != ':') return null
        host = raw.substring(1, end)
        if (':' !in host || host.any { it !in "0123456789abcdefABCDEF:." }) return null
        if (runCatching { InetAddress.getByName(host) }.isFailure) return null
        portText = raw.substring(end + 2)
    } else {
        if (raw.count { it == ':' } != 1) return null
        val name = raw.substringBefore(':')
        host = if (name.equals("localhost", true)) "127.0.0.1" else name
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { p -> p.isEmpty() || p.any { it !in '0'..'9' } ||
                p.length > 3 || p.length > 1 && p.startsWith('0') || p.toIntOrNull() !in 0..255 }) return null
        portText = raw.substringAfter(':')
    }
    if (portText.isEmpty() || portText.any { it !in '0'..'9' }) return null
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return ProxyEndpoint(host, port)
}

data class TorConfig(val enabled: Boolean = false, val address: String = DEFAULT_TOR_PROXY) {
    fun endpoint(): ProxyEndpoint? = if (!enabled) null else parseProxyEndpoint(address)
        ?: throw IOException("Invalid Tor proxy address")
    val walletProxy: String get() = endpoint()?.address.orEmpty()
    fun javaProxy(): Proxy = endpoint()?.let { Proxy(Proxy.Type.SOCKS, it.socketAddress()) } ?: Proxy.NO_PROXY
}

fun isOnionNode(uri: String): Boolean = uri.substringAfterLast('@').substringBefore('/').substringBeforeLast(':')
    .endsWith(".onion", ignoreCase = true)
