package one.monero.moneroone.core.network

import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** A local SOCKS4a/5 peer. It never forwards traffic to an external network. */
internal class LocalSocksProxy(private val mode: Mode = Mode.HTTP) : Closeable {
    enum class Mode { HTTP, REJECT, HOLD, CLOSE }
    data class Destination(val host: String, val port: Int)
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()
    val address = "127.0.0.1:${server.localPort}"
    val destinations = LinkedBlockingQueue<Destination>()
    private val accept = thread(isDaemon = true, name = "test-socks") {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            sockets += socket
            thread(isDaemon = true) { runCatching { socket.use { serve(it) } }; sockets -= socket }
        }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 5000
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val version = input.readUnsignedByte()
        val host: String
        val port: Int
        if (version == 5) {
            repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
            output.write(byteArrayOf(5, 0)); output.flush()
            if (input.read() != 5) return // A reachability-only check ends after the greeting.
            check(input.readUnsignedByte() == 1)
            input.readUnsignedByte()
            host = when (input.readUnsignedByte()) {
                3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 255).toString() }
                4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress!!
                else -> error("Invalid SOCKS address type")
            }
            port = input.readUnsignedShort()
            destinations += Destination(host, port)
            output.write(byteArrayOf(5, if (mode == Mode.REJECT) 1 else 0, 0, 1, 127, 0, 0, 1, 0, 0))
        } else {
            check(version == 4)
            check(input.readUnsignedByte() == 1)
            port = input.readUnsignedShort()
            val ip = ByteArray(4).also { input.readFully(it) }
            readCString(input)
            host = if (ip[0] == 0.toByte() && ip[1] == 0.toByte() && ip[2] == 0.toByte() && ip[3] != 0.toByte())
                readCString(input) else ip.joinToString(".") { (it.toInt() and 255).toString() }
            destinations += Destination(host, port)
            output.write(byteArrayOf(0, if (mode == Mode.REJECT) 91 else 90, 0, 0, 0, 0, 0, 0))
        }
        output.flush()
        if (mode == Mode.REJECT) return
        if (mode == Mode.HOLD) { while (input.read() != -1) { }; return }
        var header = ""
        while (!header.endsWith("\r\n\r\n") && header.length < 16384) {
            val byte = input.read()
            if (byte < 0) return
            header += byte.toChar()
        }
        if (mode == Mode.CLOSE) return // As a TLS-only RPC port does with plain HTTP.
        val body = "{\"status\":\"OK\"}"
        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
        output.flush()
    }
    private fun readCString(input: DataInputStream): String = buildString {
        while (length < 512) { val b = input.readUnsignedByte(); if (b == 0) break; append(b.toChar()) }
    }
    override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; accept.join(1000) }
}
