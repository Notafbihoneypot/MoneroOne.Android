package one.monero.moneroone.ui.screens.send

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.monero.moneroone.core.openalias.DnssecStatus
import one.monero.moneroone.core.openalias.OpenAliasFailure
import one.monero.moneroone.core.openalias.OpenAliasName
import one.monero.moneroone.core.openalias.OpenAliasOutcome
import one.monero.moneroone.core.openalias.OpenAliasResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address step's classification timing: typed text is classified after
 * the typing delay, Paste, Scan and Return at once, and no row shows while
 * the user types.
 */
class OpenAliasLookupModelTest {

    private val validAddress = "4" + "A".repeat(94)

    /** Typing delays that end only when the test says so. */
    private class Pauses {
        private val open = mutableListOf<CompletableDeferred<Unit>>()
        val count: Int get() = open.size
        var started = 0
            private set

        suspend fun pause() {
            val gate = CompletableDeferred<Unit>()
            started++
            open += gate
            try {
                gate.await()
            } finally {
                open -= gate
            }
        }

        fun end() = open.toList().forEach { it.complete(Unit) }
    }

    private val pauses = Pauses()
    private val lookups = mutableListOf<OpenAliasName>()
    private var outcome: OpenAliasOutcome =
        OpenAliasOutcome.Resolved(OpenAliasResult(validAddress, null, DnssecStatus.VALID))
    private var lookupGate: CompletableDeferred<Unit>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val model = OpenAliasLookupModel(
        scope = scope,
        lookup = { name ->
            lookups += name
            lookupGate?.await()
            outcome
        },
        pins = null,
        isValidAddress = { it == validAddress },
        typingPause = { pauses.pause() },
    )

    @After
    fun tearDown() = scope.cancel()

    private fun type(text: String) {
        for (end in 1..text.length) model.onInputChanged(text.take(end), immediate = false)
    }

    @Test
    fun typedTextShowsNoRowUntilThePause() {
        type("donate")
        assertEquals(LookupStatus.Idle, model.status)
        pauses.end()
        assertEquals(LookupStatus.Address("donate", valid = false), model.status)
    }

    @Test
    fun typedValidAddressIsCheckedAfterThePause() {
        model.onInputChanged(validAddress, immediate = false)
        assertEquals(LookupStatus.Idle, model.status)
        pauses.end()
        assertEquals(LookupStatus.Address(validAddress, valid = true), model.status)
    }

    @Test
    fun pastedTextIsCheckedAtOnce() {
        model.onInputChanged(validAddress, immediate = true)
        assertEquals(LookupStatus.Address(validAddress, valid = true), model.status)
        model.onInputChanged("not an address", immediate = true)
        assertEquals(LookupStatus.Address("not an address", valid = false), model.status)
        assertEquals(0, pauses.count)
    }

    @Test
    fun typingAfterACheckedAddressHidesTheRowUntilThePause() {
        model.onInputChanged("donate", immediate = true)
        assertEquals(LookupStatus.Address("donate", valid = false), model.status)
        type("donate@getmonero.org")
        assertEquals(LookupStatus.Idle, model.status)
        assertTrue(lookups.isEmpty())
        pauses.end()
        assertEquals(listOf("donate.getmonero.org"), lookups.map { it.query })
        assertTrue(model.status is LookupStatus.Resolved)
    }

    @Test
    fun theSameTextKeepsTheRow() {
        model.onInputChanged("donate", immediate = true)
        model.onInputChanged("donate ", immediate = false)
        assertEquals(LookupStatus.Address("donate", valid = false), model.status)
        assertEquals(0, pauses.count)
    }

    @Test
    fun aTrailingDotStartsTheDelayAgain() {
        type("donate@getmonero")
        val before = pauses.started
        model.onInputChanged("donate@getmonero.", immediate = false)
        assertEquals(before + 1, pauses.started)
        assertEquals(1, pauses.count)
        assertTrue(lookups.isEmpty())
        pauses.end()
        assertEquals(1, lookups.size)
        assertEquals("donate@getmonero", lookups[0].display)
    }

    @Test
    fun reservedAndInvalidNamesWaitForThePauseWhenTyped() {
        type("@joe")
        assertEquals(LookupStatus.Idle, model.status)
        pauses.end()
        assertEquals(LookupStatus.Rejected(reserved = true), model.status)

        model.onInputChanged("a@@b.org", immediate = false)
        assertEquals(LookupStatus.Idle, model.status)
        pauses.end()
        assertEquals(LookupStatus.Rejected(reserved = false), model.status)

        model.onInputChanged("joe@monero.one", immediate = true)
        assertEquals(LookupStatus.Rejected(reserved = true), model.status)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun returnStartsATypedLookupAtOnce() {
        type("donate@getmonero.org")
        assertTrue(lookups.isEmpty())
        model.onInputChanged("donate@getmonero.org", immediate = true)
        assertEquals(1, lookups.size)
        assertEquals(0, pauses.count)
    }

    @Test
    fun clearingCancelsALookupInProgress() {
        lookupGate = CompletableDeferred()
        model.onInputChanged("donate@getmonero.org", immediate = true)
        assertTrue(model.status is LookupStatus.Running)
        model.onInputChanged("", immediate = true)
        assertEquals(LookupStatus.Idle, model.status)
        lookupGate?.complete(Unit)
        assertEquals(LookupStatus.Idle, model.status)
    }

    @Test
    fun returnAfterAFailureLooksUpAgain() {
        outcome = OpenAliasOutcome.Failed(OpenAliasFailure.NETWORK)
        model.onInputChanged("donate@getmonero.org", immediate = true)
        assertTrue(model.status is LookupStatus.Failed)
        outcome = OpenAliasOutcome.Resolved(OpenAliasResult(validAddress, null, DnssecStatus.VALID))
        model.onInputChanged("donate@getmonero.org", immediate = true)
        assertEquals(2, lookups.size)
        assertTrue(model.status is LookupStatus.Resolved)
    }
}
