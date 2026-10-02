package one.monero.moneroone.ui.screens.send

import one.monero.moneroone.core.openalias.DnssecStatus
import one.monero.moneroone.core.openalias.OpenAliasFailure
import one.monero.moneroone.core.openalias.OpenAliasName
import one.monero.moneroone.core.openalias.OpenAliasResult
import one.monero.moneroone.core.openalias.OpenAliasSummary
import one.monero.moneroone.core.openalias.PinState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The recipient card's text: where an OpenAlias name wraps, what TalkBack reads as its value, and what it hears when a lookup ends. */
class OpenAliasRecipientTextTest {

    @Test
    fun nameWrapPoints() {
        assertEquals("donate@​getmonero​.org", openAliasWrappable("donate@getmonero.org"))
        assertEquals("pay​.example​.com", openAliasWrappable("pay.example.com"))
        assertEquals("x", openAliasWrappable("x"))
    }

    @Test
    fun spokenStatus() {
        val name = OpenAliasName(display = "donate@getmonero.org", query = "donate.getmonero.org")
        val valid = OpenAliasSummary(name, "4", DnssecStatus.VALID, PinState.None, recipientName = "Monero Development")
        assertEquals("DNSSEC valid", openAliasSpokenStatus(valid))

        val same = OpenAliasSummary(name, "4", DnssecStatus.OFF, PinState.Same(1_700_000_000L))
        assertEquals("DNSSEC off, Same address as your last payment", openAliasSpokenStatus(same))

        val changed = valid.copy(pin = PinState.Changed(1_700_000_000L))
        assertEquals("DNSSEC valid, Address changed since your last payment", openAliasSpokenStatus(changed))
    }

    @Test
    fun lookupEndWords() {
        val name = OpenAliasName(display = "donate@getmonero.org", query = "donate.getmonero.org")
        val resolved = LookupStatus.Resolved(name, OpenAliasResult("4", "Monero Development", DnssecStatus.VALID), PinState.None)
        assertEquals("Address found for donate@getmonero.org. DNSSEC valid", lookupAnnouncement(resolved))
        val unsigned = resolved.copy(result = OpenAliasResult("4", null, DnssecStatus.OFF))
        assertEquals("Address found for donate@getmonero.org. DNSSEC off", lookupAnnouncement(unsigned))
        assertEquals("The lookup took too long", lookupAnnouncement(LookupStatus.Failed(name, OpenAliasFailure.TIMEOUT)))
        assertNull(lookupAnnouncement(LookupStatus.Running(name)))
    }
}
