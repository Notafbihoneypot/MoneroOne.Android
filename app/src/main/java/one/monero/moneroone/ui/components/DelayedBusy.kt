package one.monero.moneroone.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * A busy indicator that waits before it shows: a wait shorter than
 * [SHOW_AFTER_MS] puts nothing on screen, so a fast save does not flash a
 * spinner.
 */
internal object DelayedBusy {

    const val SHOW_AFTER_MS = 300L

    /**
     * True once [busy] has stayed true for [afterMs], false again as soon as
     * [busy] ends. Each new busy spell starts the wait over.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun shown(busy: Flow<Boolean>, afterMs: Long = SHOW_AFTER_MS): Flow<Boolean> =
        busy.distinctUntilChanged()
            .transformLatest { isBusy ->
                if (isBusy) delay(afterMs)
                emit(isBusy)
            }
            .distinctUntilChanged()
}
