package one.monero.moneroone.core.openalias

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Spec section 8.1: the bridge over a real loopback socket with a fake DohTransport, and the Tor setup. */
class DnsBridgeTest {

    private val loopback: InetAddress = loopbackV4() ?: error("no IPv4 loopback")
    private val bridges = mutableListOf<DnsBridge>()
    private val clients = mutableListOf<Socket>()

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        bridges.forEach { it.stop() }
    }

    // ---------- Helpers ----------

    private class Call(val server: String, val query: ByteArray)

    /** A DohTransport that records each call and answers with [respond]. */
    private class FakeTransport(val respond: (server: String, query: ByteArray) -> DohReply) : DohTransport {
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        override fun post(server: String, query: ByteArray): DohReply {
            calls += Call(server, query.copyOf())
            return respond(server, query)
        }
    }

    /** A TXT query for [name], RD on. */
    private fun query(id: Int, name: String): ByteArray {
        val out = mutableListOf<Byte>()
        out += (id shr 8).toByte()
        out += id.toByte()
        out += 0x01.toByte() // RD
        out += 0x00.toByte()
        out += listOf<Byte>(0, 1, 0, 0, 0, 0, 0, 0) // QDCOUNT 1
        for (label in name.split('.')) {
            out += label.length.toByte()
            out += label.toByteArray().toList()
        }
        out += 0
        out += listOf<Byte>(0, 16, 0, 1) // TXT, IN
        return out.toByteArray()
    }

    /** The DoH server's reply: the query with QR on. */
    private fun goodReply(query: ByteArray): DohReply {
        val body = query.copyOf()
        body[2] = (body[2].toInt() or 0x80).toByte()
        return DohReply(200, "application/dns-message", body)
    }

    private fun startBridge(transport: DohTransport, servers: List<String> = listOf("s1", "s2")): Pair<DnsBridge, Int> {
        val bridge = DnsBridge(transport, servers)
        bridges += bridge
        val port = bridge.start()
        assertNotNull(port)
        return bridge to port!!
    }

    private fun connect(port: Int): Socket =
        Socket(loopback, port).also {
            it.soTimeout = 5_000
            clients += it
        }

    private fun Socket.send(message: ByteArray) {
        getOutputStream().write(DnsBridge.frame(message))
        getOutputStream().flush()
    }

    private fun Socket.receive(): ByteArray {
        val input = DataInputStream(getInputStream())
        val length = input.readUnsignedShort()
        val body = ByteArray(length)
        input.readFully(body)
        return body
    }

    /** True when the bridge closed the connection: end of stream, or a reset. */
    private fun Socket.isClosedByPeer(): Boolean = try {
        getInputStream().read() == -1
    } catch (e: SocketTimeoutException) {
        false
    } catch (e: IOException) {
        true
    }

    private fun id(message: ByteArray) = ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)

    private fun firstLabel(message: ByteArray): String {
        val length = message[12].toInt()
        return String(message, 13, length)
    }

    private fun assertServfail(query: ByteArray, reply: ByteArray) {
        assertEquals(id(query), id(reply))
        assertEquals(0x80 or (query[2].toInt() and 0x79), reply[2].toInt() and 0xFF)
        assertEquals(0x80 or (query[3].toInt() and 0x10) or 0x02, reply[3].toInt() and 0xFF)
        assertEquals(1, reply[5].toInt()) // QDCOUNT
        assertArrayEquals(ByteArray(6), reply.copyOfRange(6, 12)) // AN, NS, AR
        assertArrayEquals(query.copyOfRange(12, query.size), reply.copyOfRange(12, reply.size))
    }

    // ---------- Bridge ----------

    @Test
    fun oneQueryGoesOutWithIdZeroAndComesBackWithItsId() {
        val transport = FakeTransport { _, q -> goodReply(q) }
        val (bridge, port) = startBridge(transport)
        val client = connect(port)
        val q = query(0x1234, "donate.getmonero.org")
        client.send(q)
        val reply = client.receive()

        assertEquals(1, transport.calls.size)
        assertEquals(0, id(transport.calls[0].query))
        assertEquals("s1", transport.calls[0].server)
        assertEquals(0x1234, id(reply))
        assertTrue((reply[2].toInt() and 0x80) != 0)
        assertEquals(1, bridge.answeredCount)
        assertEquals(0, bridge.synthesizedCount)
    }

    @Test
    fun pipelinedQueriesAreAnsweredInAnyOrder() {
        val arrived = CountDownLatch(3)
        val release = mapOf("a" to CountDownLatch(1), "b" to CountDownLatch(1), "c" to CountDownLatch(1))
        val transport = FakeTransport { _, q ->
            arrived.countDown()
            release.getValue(firstLabel(q)).await(5, TimeUnit.SECONDS)
            goodReply(q)
        }
        val (_, port) = startBridge(transport)
        val client = connect(port)
        val ids = mapOf("a" to 101, "b" to 102, "c" to 103)
        for ((label, id) in ids) client.send(query(id, "$label.example.com"))
        assertTrue("all three in flight at once", arrived.await(5, TimeUnit.SECONDS))

        for (label in listOf("c", "b", "a")) {
            release.getValue(label).countDown()
            val reply = client.receive()
            assertEquals(label, firstLabel(reply))
            assertEquals(ids.getValue(label), id(reply))
        }
    }

    @Test
    fun failoverMakesTheAnsweringServerPreferred() {
        val transport = FakeTransport { server, q ->
            if (server == "s1") throw IOException("down") else goodReply(q)
        }
        val (bridge, port) = startBridge(transport)
        val client = connect(port)
        client.send(query(1, "a.example.com"))
        assertEquals(1, id(client.receive()))
        assertEquals(listOf("s1", "s2"), transport.calls.map { it.server })

        client.send(query(2, "b.example.com"))
        assertEquals(2, id(client.receive()))
        assertEquals(listOf("s1", "s2", "s2"), transport.calls.map { it.server })
        assertEquals(2, bridge.answeredCount)
        assertEquals(0, bridge.synthesizedCount)
    }

    @Test
    fun allServersFailingGivesServfailAndMarksTheBridgeDown() {
        val transport = FakeTransport { _, _ -> throw IOException("down") }
        val (bridge, port) = startBridge(transport)
        val client = connect(port)
        val q1 = query(7, "donate.getmonero.org")
        client.send(q1)
        assertServfail(q1, client.receive())
        assertEquals(1, bridge.synthesizedCount)
        assertEquals(2, transport.calls.size)

        val q2 = query(8, "getmonero.org")
        client.send(q2)
        assertServfail(q2, client.receive())
        assertEquals("no call once the bridge is down", 2, transport.calls.size)
    }

    @Test
    fun query65GetsServfailWithNoCall() {
        val transport = FakeTransport { _, q -> goodReply(q) }
        val (bridge, port) = startBridge(transport)
        val client = connect(port)
        for (i in 1..64) {
            client.send(query(i, "n$i.example.com"))
            assertEquals(i, id(client.receive()))
        }
        assertEquals(64, transport.calls.size)
        val q65 = query(65, "n65.example.com")
        client.send(q65)
        assertServfail(q65, client.receive())
        assertEquals(64, transport.calls.size)
        assertEquals(64, bridge.answeredCount)
        assertEquals(1, bridge.synthesizedCount)
    }

    @Test
    fun shortFrameClosesTheConnection() {
        val transport = FakeTransport { _, q -> goodReply(q) }
        val (_, port) = startBridge(transport)
        val client = connect(port)
        client.getOutputStream().write(byteArrayOf(0, 5)) // a frame length under 12
        client.getOutputStream().flush()
        assertTrue(client.isClosedByPeer())
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun badRepliesCountAsTransportFailures() {
        val bad = listOf(
            { q: ByteArray -> DohReply(500, "application/dns-message", goodReply(q).body) },
            { q: ByteArray -> DohReply(200, "text/html", goodReply(q).body) },
            { q: ByteArray -> DohReply(200, "application/dns-message", goodReply(q).body.copyOf(11)) },
            { q: ByteArray -> DohReply(200, "application/dns-message", q.copyOf()) }, // QR bit off
        )
        for (badReply in bad) {
            val transport = FakeTransport { server, q -> if (server == "s1") badReply(q) else goodReply(q) }
            val (bridge, port) = startBridge(transport)
            val client = connect(port)
            client.send(query(9, "a.example.com"))
            assertEquals(9, id(client.receive()))
            assertEquals(listOf("s1", "s2"), transport.calls.map { it.server })
            assertEquals(1, bridge.answeredCount)
        }
    }

    @Test
    fun contentTypeWithParametersIsGood() {
        val q = query(1, "a.example.com")
        assertTrue(DnsBridge.isGoodReply(DohReply(200, "application/dns-message; charset=binary", goodReply(q).body)))
    }

    @Test
    fun afterStopNewConnectionsFail() {
        val (bridge, port) = startBridge(FakeTransport { _, q -> goodReply(q) })
        bridge.stop()
        try {
            Socket(loopback, port).close()
            fail("connected after stop()")
        } catch (e: IOException) {
            // Expected.
        }
    }

    @Test
    fun stopClosesOpenConnections() {
        val (bridge, port) = startBridge(FakeTransport { _, q -> goodReply(q) })
        val client = connect(port)
        client.send(query(1, "a.example.com"))
        client.receive()
        bridge.stop()
        assertTrue(client.isClosedByPeer())
    }

    @Test
    fun servfailFlagsAndUnparsableQuestion() {
        val q = query(0xABCD, "a.example.com")
        q[2] = 0xFF.toByte()
        q[3] = 0xFF.toByte()
        val reply = DnsBridge.servfail(q)
        assertEquals(0xF9, reply[2].toInt() and 0xFF)
        assertEquals(0x92, reply[3].toInt() and 0xFF)
        assertServfail(q, reply)

        // A compression pointer in the question: QDCOUNT 0 and no question.
        val pointer = query(5, "a.example.com").copyOf(12) + byteArrayOf(0xC0.toByte(), 12, 0, 16, 0, 1)
        val noQuestion = DnsBridge.servfail(pointer)
        assertEquals(12, noQuestion.size)
        assertEquals(0, noQuestion[5].toInt())
        assertEquals(5, id(noQuestion))

        // A question cut short: QDCOUNT 0.
        val cut = query(6, "a.example.com").copyOf(20)
        assertEquals(12, DnsBridge.servfail(cut).size)
    }

    // ---------- Tor ----------

    @Test
    fun torClientHasSocksProxyAndDnsThatThrows() {
        val client: OkHttpClient = buildDohClient(OpenAliasConfig.DOH_TIMEOUT_TOR_MS, TorProxy("localhost", 9050))
        assertEquals(Proxy.Type.SOCKS, client.proxy?.type())
        try {
            client.dns.lookup("dns.quad9.net")
            fail("Dns resolved a name with Tor on")
        } catch (e: UnknownHostException) {
            // Expected.
        }
        assertNull(client.cache)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(OpenAliasConfig.DOH_TIMEOUT_TOR_MS.toInt(), client.callTimeoutMillis)
    }

    @Test
    fun directClientHasNoProxy() {
        val client = buildDohClient(OpenAliasConfig.DOH_TIMEOUT_DIRECT_MS, null)
        assertNull(client.proxy)
        assertEquals(OpenAliasConfig.DOH_TIMEOUT_DIRECT_MS.toInt(), client.callTimeoutMillis)
    }

    @Test
    fun socksConnectCarriesTheDohHostName() {
        val socks = ServerSocket(0, 1, loopback)
        var addressType = -1
        var host = ""
        var port = -1
        val server = thread {
            try {
                socks.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val output = s.getOutputStream()
                    input.readUnsignedByte() // version
                    val methods = input.readUnsignedByte()
                    input.readFully(ByteArray(methods))
                    output.write(byteArrayOf(5, 0)) // no authentication
                    output.flush()
                    input.readFully(ByteArray(3)) // version, CONNECT, reserved
                    addressType = input.readUnsignedByte()
                    if (addressType == 3) {
                        val name = ByteArray(input.readUnsignedByte())
                        input.readFully(name)
                        host = String(name)
                    }
                    port = input.readUnsignedShort()
                }
            } catch (e: IOException) {
                // The test checks what arrived.
            }
        }
        val transport = OkHttpDohTransport(
            buildDohClient(OpenAliasConfig.DOH_TIMEOUT_DIRECT_MS, TorProxy(loopback.hostAddress!!, socks.localPort))
        )
        try {
            transport.post("https://dns.quad9.net/dns-query", query(0, "donate.getmonero.org"))
            fail("the fake proxy never connects")
        } catch (e: IOException) {
            // Expected: the fake proxy closes after the request.
        }
        server.join(5_000)
        socks.close()
        assertEquals("address type 3: a domain name", 3, addressType)
        assertEquals("dns.quad9.net", host)
        assertEquals(443, port)
    }

    @Test
    fun proxyStringParsing() {
        assertEquals(TorProxy("localhost", 9050), OpenAliasTor.parseProxy("localhost:9050"))
        assertEquals(TorProxy(loopback.hostAddress!!, 9050), OpenAliasTor.parseProxy("${loopback.hostAddress}:9050"))
        for (bad in listOf(null, "", "garbage", ":9050", "localhost:", "localhost:0", "localhost:70000", "localhost:x")) {
            assertNull(bad, OpenAliasTor.parseProxy(bad))
        }
    }

    // ---------- resolve() ----------

    private val getmoneroXmr =
        "888tNkZrPN6JsEgekjMnABU4TBzc2Dt29EPAvkRxbANsAnjyPbb3iQ1YBRk1UXcdRsiKc9dhwMVgN5S9cQUiyoogDavup3H"
    private val name = OpenAliasName("donate@getmonero.org", "donate.getmonero.org")
    private val validCheck: (String) -> AddressCheck = { AddressCheck.VALID }

    @Test
    fun badProxyStringGivesTorErrorAndSendsNothing() = runBlocking {
        val kitCalls = AtomicInteger()
        val transportCalls = AtomicInteger()
        val resolver = OpenAliasResolver(
            kitLookup = { _, _, _ -> kitCalls.incrementAndGet(); "" },
            torState = { TorState.On(OpenAliasTor.parseProxy("not a proxy")) },
            transportFactory = { transportCalls.incrementAndGet(); FakeTransport { _, q -> goodReply(q) } },
        )
        assertEquals(OpenAliasOutcome.Failed(OpenAliasFailure.TOR), resolver.resolve(name, validCheck))
        assertEquals(0, kitCalls.get())
        assertEquals(0, transportCalls.get())
    }

    @Test
    fun resolveRunsTheKitThroughTheBridge() = runBlocking {
        val transport = FakeTransport { _, q -> goodReply(q) }
        var seenPort = -1
        var seenTimeout = -1
        val record = "oa1:xmr recipient_address=$getmoneroXmr; recipient_name=Monero Development;"
        val resolver = OpenAliasResolver(
            kitLookup = { queryName, port, timeout ->
                seenPort = port
                seenTimeout = timeout
                // Stand-in for libunbound: one query through the bridge.
                val socket = Socket(loopback, port)
                socket.soTimeout = 5_000
                socket.use {
                    it.send(query(42, queryName))
                    assertEquals(42, id(it.receive()))
                }
                val hex = record.toByteArray().let { b -> byteArrayOf(b.size.toByte()) + b }
                    .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                """{"status":"ok","rcode":0,"havedata":true,"nxdomain":false,"secure":true,"bogus":false,"why_bogus":"","records":["$hex"]}"""
            },
            torState = { TorState.Off },
            transportFactory = { transport },
        )
        val outcome = resolver.resolve(name, validCheck)
        assertEquals(
            OpenAliasOutcome.Resolved(OpenAliasResult(getmoneroXmr, "Monero Development", DnssecStatus.VALID)),
            outcome
        )
        assertEquals(OpenAliasConfig.KIT_TIMEOUT_DIRECT_MS, seenTimeout)
        assertEquals(1, transport.calls.size)
        // The bridge is stopped once the lookup ends.
        try {
            Socket(loopback, seenPort).close()
            fail("bridge still listening")
        } catch (e: IOException) {
            // Expected.
        }
    }

    @Test
    fun resolveWithTorUsesTheTorTimeoutsAndTheTorError() = runBlocking {
        var seenTimeout = -1
        var seenProxy: TorProxy? = null
        val resolver = OpenAliasResolver(
            kitLookup = { _, _, timeout -> seenTimeout = timeout; """{"status":"error","error":"x"}""" },
            torState = { TorState.On(TorProxy("localhost", 9050)) },
            transportFactory = { proxy -> seenProxy = proxy; FakeTransport { _, q -> goodReply(q) } },
        )
        assertEquals(OpenAliasOutcome.Failed(OpenAliasFailure.TOR), resolver.resolve(name, validCheck))
        assertEquals(OpenAliasConfig.KIT_TIMEOUT_TOR_MS, seenTimeout)
        assertEquals(TorProxy("localhost", 9050), seenProxy)
    }

    @Test
    fun kitFailureMapsToNetworkError() = runBlocking {
        val resolver = OpenAliasResolver(
            kitLookup = { _, _, _ -> throw UnsatisfiedLinkError("no kit") },
            torState = { TorState.Off },
            transportFactory = { FakeTransport { _, q -> goodReply(q) } },
        )
        assertEquals(OpenAliasOutcome.Failed(OpenAliasFailure.NETWORK), resolver.resolve(name, validCheck))
    }
}
