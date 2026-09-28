package one.monero.moneroone.core.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import one.monero.moneroone.R

/** The iOS send-complete chime. It mixes with other audio and respects silent/vibrate. */
object SoundFeedback {
    private var pool: SoundPool? = null
    @Volatile private var loaded = false
    private var sound = 0

    fun initialize(context: Context) {
        if (pool != null) return
        runCatching {
            val player = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            ).build()
            pool = player
            player.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
            sound = player.load(context.applicationContext, R.raw.send_complete, 1)
        }
    }

    fun sendComplete(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (loaded && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching { pool?.play(sound, 1f, 1f, 1, 0, 1f) }
        }
    }
}
