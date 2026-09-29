package one.monero.moneroone.core.network

import java.io.IOException
import java.net.Proxy
import org.junit.Assert.*
import org.junit.Test

class TorConfigTest {
    @Test fun normalizesLocalProxyAndOptionalScheme() {
        assertEquals(ProxyEndpoint("127.0.0.1", 9050), parseProxyEndpoint(" socks5://localhost:9050 "))
        assertEquals(ProxyEndpoint("127.0.0.1", 9150), parseProxyEndpoint("127.0.0.1:9150"))
    }
    @Test fun acceptsBracketedIpv6WithoutResolvingAHostname() {
        assertEquals("[::1]:9050", parseProxyEndpoint("[::1]:9050")?.address)
        assertNull(parseProxyEndpoint("[::::]:9050"))
    }
    @Test fun rejectsNamesCredentialsHttpPathsAndAmbiguousIpLiterals() {
        listOf("example.org:9050", "u:p@127.0.0.1:9050", "http://127.0.0.1:9050", "127.0.0.1:9050/",
            "127.0.0.1:9050?x", "256.0.0.1:9050", "127.00.0.1:9050", "127.1:9050", "::1:9050", "[fe80::1%wlan0]:9050")
            .forEach { assertNull(it, parseProxyEndpoint(it)) }
    }
    @Test fun requiresAValidPort() {
        listOf("127.0.0.1", "127.0.0.1:", "127.0.0.1:0", "127.0.0.1:65536", "127.0.0.1:-1", "127.0.0.1:9 050")
            .forEach { assertNull(it, parseProxyEndpoint(it)) }
    }
    @Test fun enabledInvalidConfigurationNeverBecomesDirect() {
        assertThrows(IOException::class.java) { TorConfig(true, "bad").javaProxy() }
        assertThrows(IOException::class.java) { TorConfig(true, "").walletProxy }
        assertEquals(Proxy.Type.SOCKS, TorConfig(true).javaProxy().type())
        assertEquals(DEFAULT_TOR_PROXY, TorConfig(true).walletProxy)
    }
    @Test fun disabledProxyRetainsAddressButConnectsDirectly() {
        assertEquals(Proxy.NO_PROXY, TorConfig(false, "127.0.0.1:9150").javaProxy())
        assertEquals("", TorConfig(false).walletProxy)
    }
    @Test fun identifiesOnionNodesWithoutDns() {
        assertTrue(isOnionNode("user:password@test.onion:18081"))
        assertTrue(isOnionNode("TEST.ONION:18081"))
        assertFalse(isOnionNode("not.onion.example:18081"))
    }
}
