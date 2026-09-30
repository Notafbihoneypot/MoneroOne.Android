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
 * Chart scrub labels name the day in the user's language, with the
 * system's day words and separators, as iOS's relative DateFormatter does.
 */
@RunWith(AndroidJUnit4::class)
class ChartDateFormatsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = ms("2026-09-20 18:00")

    private fun formats(locale: Locale, use24Hour: Boolean) =
        ChartDateFormats(locale, utc, use24Hour) { DateFormat.getBestDateTimePattern(locale, it) }

    private fun ms(text: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = utc }.parse(text)!!.time

    private fun yesterday(locale: Locale, use24Hour: Boolean) =
        formats(locale, use24Hour).scrubLabel(ChartTimeAxis.DAY, ms("2026-09-19 23:05"), now)

    @Test
    fun yesterdayIsInTheUsersLanguage() {
        val english = yesterday(Locale.US, use24Hour = false)
        assertTrue(english, english.startsWith("Yesterday") && english.contains("11:05"))
        val german = yesterday(Locale.GERMANY, use24Hour = true)
        assertTrue(german, german.startsWith("Gestern") && german.endsWith("23:05"))
        val japanese = yesterday(Locale.JAPAN, use24Hour = true)
        assertTrue(japanese, japanese.startsWith("昨日") && japanese.endsWith("23:05"))
    }

    @Test
    fun olderDaysHaveNoEnglishJoiner() {
        val german = formats(Locale.GERMANY, use24Hour = true)
        val older = german.scrubLabel(ChartTimeAxis.DAY, ms("2026-09-18 15:05"), now)
        assertTrue(older, older.contains("2026") && older.endsWith("15:05") && !older.contains(" at "))
        assertEquals(older, german.dateTime(ms("2026-09-18 15:05")))
    }
}
