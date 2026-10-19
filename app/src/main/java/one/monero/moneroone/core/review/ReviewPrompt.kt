package one.monero.moneroone.core.review

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import one.monero.moneroone.core.wallet.SendState

/**
 * When the app asks for a rating: once per install, on the wallet home after
 * the first send that went out (iOS ReviewPromptGate). A tap on Settings >
 * Rate Monero One counts as done too, so a person who rated there is never
 * asked.
 *
 * Two flags in the app's preferences hold the state. `pending`: a send went
 * out and the prompt waits for the home. `done`: the prompt opened, or the
 * person opened the Play listing from Settings. After `done` nothing asks
 * again. Sends from before these flags existed set nothing, so they never
 * count.
 */
class ReviewPromptGate(private val prefs: SharedPreferences) {

    val isDone: Boolean get() = prefs.getBoolean(DONE_KEY, false)

    /** A send went out and the home has not opened the prompt yet. */
    val isPending: Boolean get() = !isDone && prefs.getBoolean(PENDING_KEY, false)

    /**
     * The send moved to [state]. Only [SendState.Success] (the transaction
     * went out) counts. An error, a step back or a closed Send does nothing.
     */
    fun sendStateChanged(state: SendState) {
        if (state !is SendState.Success || isDone) return
        prefs.edit().putBoolean(PENDING_KEY, true).apply()
    }

    /** Settings > Rate Monero One opened the Play listing. */
    fun ratedFromSettings() = markDone()

    /**
     * True once: a send is pending and the home is on screen, unlocked and
     * idle, so the caller opens the prompt now. It records `done` first, so
     * Rate, No Thanks, Back and a tap outside all leave it done, and no later
     * call returns true.
     */
    fun claim(homeIsIdle: Boolean): Boolean {
        if (!homeIsIdle || !isPending) return false
        markDone()
        return true
    }

    private fun markDone() {
        prefs.edit().putBoolean(DONE_KEY, true).remove(PENDING_KEY).apply()
    }

    companion object {
        const val PENDING_KEY = "review_prompt_pending"
        const val DONE_KEY = "review_prompt_done"

        fun create(context: Context): ReviewPromptGate =
            ReviewPromptGate(context.getSharedPreferences("monero_wallet", Context.MODE_PRIVATE))
    }
}

/**
 * Opens the Play listing in the Play Store app, or in the browser where Play
 * is missing (GrapheneOS). The id is the release app's, because debug builds
 * end in .debug.
 */
fun openPlayListing(context: Context) {
    val listing = Uri.parse("https://play.google.com/store/apps/details?id=one.monero.moneroone")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, listing).setPackage("com.android.vending"))
    } catch (_: ActivityNotFoundException) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, listing)) } catch (_: Exception) {}
    }
}
