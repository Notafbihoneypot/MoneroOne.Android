package one.monero.moneroone.ui.screens.chart

import android.text.format.DateFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Chart scrub labels name the day in the user's language as iOS's relative
 * DateFormatter writes it, with the device's own time patterns.
 */
@RunWith(AndroidJUnit4::class)
class ChartDateFormatsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = ms("2026-09-20 18:00")

    private fun formats(locale: Locale, use24Hour: Boolean) =
        ChartDateFormats(locale, utc, use24Hour) { DateFormat.getBestDateTimePattern(locale, it) }

    private fun ms(text: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = utc }.parse(text)!!.time

    private fun yesterday(tag: String, use24Hour: Boolean) =
        formats(Locale.forLanguageTag(tag), use24Hour)
            .scrubLabel(ChartTimeAxis.DAY, ms("2026-09-19 23:05"), now)
            .replace('\u202F', ' ')

    @Test
    fun yesterdayReadsAsOnIos() {
        assertEquals("Yesterday at 11:05 PM", yesterday("en-US", use24Hour = false))
        assertEquals("Yesterday at 23:05", yesterday("en-GB", use24Hour = true))
        assertEquals("Gestern, 23:05", yesterday("de-DE", use24Hour = true))
        assertEquals("hier à 23:05", yesterday("fr-FR", use24Hour = true))
        assertEquals("Wczoraj o 23:05", yesterday("pl-PL", use24Hour = true))
        assertEquals("Gisteren, 23:05", yesterday("nl-NL", use24Hour = true))
        assertEquals("昨日 23:05", yesterday("ja-JP", use24Hour = true))
        assertEquals("어제 오후 11:05", yesterday("ko-KR", use24Hour = false))
        assertEquals("昨天 下午11:05", yesterday("zh-Hant-TW", use24Hour = false))
    }

    @Test
    fun olderDaysUseTheSameJoiner() {
        val german = formats(Locale.GERMANY, use24Hour = true)
        val older = german.scrubLabel(ChartTimeAxis.DAY, ms("2026-09-18 15:05"), now)
        assertTrue(older, older.contains("2026") && older.endsWith(", 15:05") && !older.contains(" at "))
        assertEquals(older, german.dateTime(ms("2026-09-18 15:05")))
        val english = formats(Locale.US, use24Hour = false).dateTime(ms("2026-09-18 15:05")).replace('\u202F', ' ')
        assertEquals("Sep 18, 2026 at 3:05 PM", english)
    }
}
