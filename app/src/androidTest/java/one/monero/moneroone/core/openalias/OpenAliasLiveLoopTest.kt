package one.monero.moneroone.core.openalias

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live network: many real lookups of one name, to count the outcomes.
 * Runs only with `-e openaliasLive true`; `-e openaliasCount N` sets the count.
 */
@RunWith(AndroidJUnit4::class)
class OpenAliasLiveLoopTest {

    @Test
    fun lookUpManyTimes() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("openaliasLive") == "true")
        val count = args.getString("openaliasCount")?.toIntOrNull() ?: 40
        val pauseMs = args.getString("openaliasPauseMs")?.toLongOrNull() ?: 500L
        val name = OpenAliasName("donate@getmonero.org", "donate.getmonero.org")
        val resolver = OpenAliasResolver()
        val tally = linkedMapOf<String, Int>()
        runBlocking {
            for (i in 1..count) {
                val started = System.nanoTime()
                val outcome = resolver.resolve(name) { AddressCheck.VALID }
                val ms = (System.nanoTime() - started) / 1_000_000
                val label = when (outcome) {
                    is OpenAliasOutcome.Resolved -> "resolved ${outcome.result.dnssec}"
                    is OpenAliasOutcome.Failed -> "failed ${outcome.failure}"
                }
                tally[label] = (tally[label] ?: 0) + 1
                Log.i(TAG, "loop $i/$count: $label in $ms ms")
                Thread.sleep(pauseMs)
            }
        }
        Log.i(TAG, "loop tally over $count: $tally")
    }

    private companion object {
        const val TAG = "OpenAliasLoop"
    }
}
