package one.monero.moneroone.ui.components

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real time on runBlocking's single thread: its timers fire in deadline order,
 * so the margins below (tens of ms against a 200 ms wait) give a fixed result.
 */
class DelayedBusyTest {

    private val afterMs = 200L

    @Test
    fun aShortWaitNeverShows() = runBlocking {
        val busy = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch { DelayedBusy.shown(busy, afterMs).collect { seen += it } }
        delay(20)

        busy.value = true
        delay(50)
        busy.value = false
        delay(afterMs * 2)

        job.cancel()
        assertEquals(listOf(false), seen)
    }

    @Test
    fun aLongWaitShowsAfterTheDelayAndHidesWhenItEnds() = runBlocking {
        val busy = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch { DelayedBusy.shown(busy, afterMs).collect { seen += it } }
        delay(20)

        busy.value = true
        delay(afterMs / 2)
        assertEquals("hidden before the delay", listOf(false), seen)

        delay(afterMs)
        assertEquals("shown after the delay", listOf(false, true), seen)

        busy.value = false
        delay(20)
        assertEquals("hidden when the wait ends", listOf(false, true, false), seen)
        job.cancel()
    }

    @Test
    fun eachWaitStartsTheDelayOver() = runBlocking {
        val busy = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch { DelayedBusy.shown(busy, afterMs).collect { seen += it } }
        delay(20)

        busy.value = true
        delay(afterMs * 3 / 4)
        busy.value = false
        delay(10)
        busy.value = true
        delay(afterMs * 3 / 4)
        // More than the delay since the first wait began, less since this one.
        assertEquals(listOf(false), seen)

        delay(afterMs)
        assertEquals(listOf(false, true), seen)
        job.cancel()
    }
}
