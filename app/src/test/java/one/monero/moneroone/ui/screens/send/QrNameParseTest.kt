package one.monero.moneroone.ui.screens.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec section 5.5: a scanned code can hold an OpenAlias name in place of the address. */
class QrNameParseTest {

    @Test
    fun plainName() {
        val data = parseMoneroUri("donate@getmonero.org")!!
        assertEquals("donate@getmonero.org", data.name)
        assertEquals("", data.address)
        assertNull(data.amount)
    }

    @Test
    fun nameInMoneroUriKeepsTheAmount() {
        val data = parseMoneroUri("monero:donate@getmonero.org?tx_amount=0.01")!!
        assertEquals("donate@getmonero.org", data.name)
        assertEquals("", data.address)
        assertEquals("0.01", data.amount)
    }

    @Test
    fun schemeInAnyCaseAndDomainName() {
        assertEquals("openalias.org", parseMoneroUri("MONERO:openalias.org")?.name)
        assertEquals("openalias.org", parseMoneroUri("  openalias.org \n")?.name)
    }

    @Test
    fun reservedNamesPassSoTheSendScreenRejectsThem() {
        assertEquals("joe@monero.one", parseMoneroUri("joe@monero.one")?.name)
        assertEquals("@joe", parseMoneroUri("monero:@joe")?.name)
    }

    @Test
    fun invalidNamesAndOtherTextGiveNothing() {
        assertNull(parseMoneroUri("monero:bad..name"))
        assertNull(parseMoneroUri("hello"))
        assertNull(parseMoneroUri("https://example.com/pay"))
    }
}
