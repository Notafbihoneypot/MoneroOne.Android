package one.monero.moneroone.ui.screens.send

import one.monero.moneroone.R
import one.monero.moneroone.core.openalias.DnssecStatus
import one.monero.moneroone.core.openalias.OpenAliasName
import one.monero.moneroone.core.openalias.OpenAliasSummary
import one.monero.moneroone.core.openalias.PinState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The recipient card's text: where an OpenAlias name wraps, and what TalkBack reads as its value. */
class OpenAliasRecipientTextTest {

    @Test
    fun nameWrapPoints() {
        assertEquals("donate@​getmonero​.org", openAliasWrappable("donate@getmonero.org"))
        assertEquals("pay​.example​.com", openAliasWrappable("pay.example.com"))
        assertEquals("x", openAliasWrappable("x"))
    }

    /** The English strings of the resources that the value uses. */
    private val english = mapOf(
        R.string.openalias_dnssec_valid to "DNSSEC valid",
        R.string.openalias_dnssec_off to "DNSSEC off",
        R.string.openalias_pin_same to "Same address as your last payment",
        R.string.openalias_pin_changed to "Address changed since your last payment",
    )

    @Test
    fun spokenStatus() {
        val name = OpenAliasName(display = "donate@getmonero.org", query = "donate.getmonero.org")
        val valid = OpenAliasSummary(name, "4", DnssecStatus.VALID, PinState.None, recipientName = "Monero Development")
        assertEquals("DNSSEC valid", openAliasSpokenStatus(valid, english::getValue))

        val same = OpenAliasSummary(name, "4", DnssecStatus.OFF, PinState.Same(1_700_000_000L))
        assertEquals("DNSSEC off, Same address as your last payment", openAliasSpokenStatus(same, english::getValue))

        val changed = valid.copy(pin = PinState.Changed(1_700_000_000L))
        assertEquals("DNSSEC valid, Address changed since your last payment", openAliasSpokenStatus(changed, english::getValue))
    }
}
