package one.monero.moneroone.ui.screens.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ChartTimeAxisTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val us = Locale.US

    // What getBestDateTimePattern gives for en-US.
    private val patterns = mapOf(
        "ha" to "h a",
        "HH" to "HH",
        "EEE" to "EEE",
        "MMMd" to "MMM d",
        "MMM" to "MMM",
        "y" to "y",
        "hmm" to "h:mm a",
        "Hmm" to "HH:mm",
        "EEEMMMdhmm" to "EEE, MMM d, h:mm a",
        "MMMdhmm" to "MMM d, h:mm a",
        "yMMMd" to "MMM d, y",
        "yMMMdhmm" to "MMM d, y, h:mm a",
        "yMMMdHmm" to "MMM d, y, HH:mm"
    )

    private fun formats(use24Hour: Boolean = false, days: RelativeDayText = IosRelativeDayText(us)) =
        ChartDateFormats(us, utc, use24Hour, days) { patterns.getValue(it) }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm", us).apply { timeZone = utc }

    private fun ms(text: String): Long = stamp().parse(text)!!.time

    private fun ticks(axis: ChartTimeAxis, start: String, end: String): List<String> =
        axis.ticks(ms(start), ms(end), utc, us).map { stamp().format(Date(it)) }

    @Test
    fun `24h ticks land on hours 0, 5, 10, 15 and 20`() {
        assertEquals(
            listOf("2026-09-20 10:00", "2026-09-20 15:00", "2026-09-20 20:00", "2026-09-21 00:00", "2026-09-21 05:00"),
            ticks(ChartTimeAxis.DAY, "2026-09-20 07:30", "2026-09-21 07:30")
        )
    }

    @Test
    fun `24h ticks keep their hours on a DST day`() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        val local = SimpleDateFormat("yyyy-MM-dd HH:mm", us).apply { timeZone = newYork }
        // Clocks went forward at 2 AM on March 8, 2026.
        val ticks = ChartTimeAxis.DAY.ticks(
            local.parse("2026-03-08 00:00")!!.time, local.parse("2026-03-08 23:00")!!.time, newYork, us
        )
        assertEquals(
            listOf("00:00", "05:00", "10:00", "15:00", "20:00"),
            ticks.map { local.format(Date(it)).substringAfter(' ') }
        )
    }

    @Test
    fun `week ticks are midnights, one on the start included`() {
        assertEquals(
            (15..21).map { String.format(Locale.US, "2026-09-%02d 00:00", it) },
            ticks(ChartTimeAxis.WEEK, "2026-09-14 12:00", "2026-09-21 12:00")
        )
        assertEquals("2026-09-14 00:00", ticks(ChartTimeAxis.WEEK, "2026-09-14 00:00", "2026-09-21 00:00").first())
    }

    @Test
    fun `month ticks are week starts`() {
        assertEquals(
            listOf("2026-08-23 00:00", "2026-08-30 00:00", "2026-09-06 00:00", "2026-09-13 00:00", "2026-09-20 00:00"),
            ticks(ChartTimeAxis.MONTH, "2026-08-22 12:00", "2026-09-21 12:00")
        )
    }

    @Test
    fun `year ticks are every other first of the month`() {
        assertEquals(
            listOf(
                "2025-10-01 00:00", "2025-12-01 00:00", "2026-02-01 00:00",
                "2026-04-01 00:00", "2026-06-01 00:00", "2026-08-01 00:00"
            ),
            ticks(ChartTimeAxis.YEAR, "2025-09-21 12:00", "2026-09-21 12:00")
        )
    }

    @Test
    fun `all ticks are every other new year, every year for a short span`() {
        assertEquals(
            listOf(2015, 2017, 2019, 2021, 2023, 2025).map { "$it-01-01 00:00" },
            ticks(ChartTimeAxis.ALL, "2014-05-21 00:00", "2026-09-21 00:00")
        )
        assertEquals(
            listOf(2024, 2025, 2026).map { "$it-01-01 00:00" },
            ticks(ChartTimeAxis.ALL, "2023-06-01 00:00", "2026-09-21 00:00")
        )
    }

    @Test
    fun `no ticks for a span that ends before it starts`() {
        assertTrue(ChartTimeAxis.DAY.ticks(ms("2026-09-21 00:00"), ms("2026-09-20 00:00"), utc, us).isEmpty())
    }

    @Test
    fun `fitting picks the axis for the span`() {
        val day = 24 * 60 * 60 * 1000L
        assertEquals(ChartTimeAxis.DAY, ChartTimeAxis.fitting(day))
        assertEquals(ChartTimeAxis.WEEK, ChartTimeAxis.fitting(5 * day))
        assertEquals(ChartTimeAxis.MONTH, ChartTimeAxis.fitting(30 * day))
        assertEquals(ChartTimeAxis.YEAR, ChartTimeAxis.fitting(200 * day))
        assertEquals(ChartTimeAxis.ALL, ChartTimeAxis.fitting(500 * day))
    }

    @Test
    fun `tick labels per axis`() {
        val t = ms("2026-09-20 15:00")
        val f = formats()
        assertEquals("3 PM", f.tickLabel(ChartTimeAxis.DAY, t))
        assertEquals("15", formats(use24Hour = true).tickLabel(ChartTimeAxis.DAY, t))
        assertEquals("Sun", f.tickLabel(ChartTimeAxis.WEEK, t))
        assertEquals("Sep 20", f.tickLabel(ChartTimeAxis.MONTH, t))
        assertEquals("Sep", f.tickLabel(ChartTimeAxis.YEAR, t))
        assertEquals("2026", f.tickLabel(ChartTimeAxis.ALL, t))
    }

    @Test
    fun `scrub labels name the day where a time alone would be unclear`() {
        val f = formats()
        val now = ms("2026-09-20 18:00")
        val t = ms("2026-09-20 15:05")
        assertEquals("3:05 PM", f.scrubLabel(ChartTimeAxis.DAY, t, now))
        assertEquals("Yesterday at 11:05 PM", f.scrubLabel(ChartTimeAxis.DAY, ms("2026-09-19 23:05"), now))
        assertEquals("Sep 18, 2026 at 3:05 PM", f.scrubLabel(ChartTimeAxis.DAY, ms("2026-09-18 15:05"), now))
        assertEquals("Sun, Sep 20, 3:05 PM", f.scrubLabel(ChartTimeAxis.WEEK, t, now))
        assertEquals("Sep 20, 3:05 PM", f.scrubLabel(ChartTimeAxis.MONTH, t, now))
        assertEquals("Sep 20, 2026", f.scrubLabel(ChartTimeAxis.YEAR, t, now))
        assertEquals("Sep 20, 2026", f.scrubLabel(ChartTimeAxis.ALL, t, now))
        assertEquals("Sep 20, 2026 at 3:05 PM", f.dateTime(t))
    }

    @Test
    fun `yesterday reads as iOS writes it in every app language`() {
        // iOS 27 DateFormatter, doesRelativeDateFormatting, medium date, short time.
        val ios = listOf(
            Triple("en", "3:05 PM", "Yesterday at 3:05 PM"), Triple("de", "15:05", "Gestern, 15:05"),
            Triple("es", "15:05", "ayer, 15:05"), Triple("fr", "15:05", "hier à 15:05"),
            Triple("it", "15:05", "ieri, 15:05"), Triple("ja", "15:05", "昨日 15:05"),
            Triple("ko", "오후 3:05", "어제 오후 3:05"), Triple("nl", "15:05", "Gisteren, 15:05"),
            Triple("pl", "15:05", "Wczoraj o 15:05"), Triple("pt-BR", "15:05", "Ontem, 15:05"),
            Triple("ro", "15:05", "ieri, 15:05"), Triple("ru", "15:05", "Вчера, 15:05"),
            Triple("tr", "15:05", "Dün 15:05"), Triple("uk", "15:05", "Учора, 15:05"),
            Triple("zh-Hans", "15:05", "昨天 15:05"), Triple("zh-Hant", "下午3:05", "昨天 下午3:05")
        )
        for ((tag, time, expected) in ios) {
            val days = IosRelativeDayText(Locale.forLanguageTag(tag))
            assertEquals(tag, expected, days.join(days.yesterday, time))
        }
    }

    @Test
    fun `scrub labels take the day word and joiner from the language`() {
        val f = formats(use24Hour = true, days = IosRelativeDayText(Locale.GERMANY))
        val now = ms("2026-09-20 18:00")
        assertEquals("Gestern, 23:05", f.scrubLabel(ChartTimeAxis.DAY, ms("2026-09-19 23:05"), now))
        assertEquals("Sep 18, 2026, 15:05", f.scrubLabel(ChartTimeAxis.DAY, ms("2026-09-18 15:05"), now))
    }

    @Test
    fun `ranges map to the price api and keep their spans`() {
        assertEquals(listOf("1D", "7D", "1M", "1Y", "All"), TimeRange.entries.map { it.apiRange })
        assertEquals(24 * 60 * 60 * 1000L, TimeRange.DAY.spanMs)
        assertNull(TimeRange.ALL.spanMs)
        assertEquals(5 * 60_000L, TimeRange.DAY.cacheTtlMs)
        assertEquals(TimeRange.entries.map { it.name }, TimeRange.entries.map { it.axis.name })
    }

    @Test
    fun `a moment picked on balance history names its date, year and time`() {
        assertEquals("Sep 3, 2026, 12:01 AM", formats().abbreviatedDateTime(ms("2026-09-03 00:01")))
        assertEquals("Sep 3, 2026, 00:01", formats(use24Hour = true).abbreviatedDateTime(ms("2026-09-03 00:01")))
    }

    @Test
    fun `range buttons have spoken names`() {
        assertEquals(
            listOf("24 hours", "1 week", "1 month", "1 year", "All time"),
            ChartTimeAxis.entries.map { it.spokenName }
        )
    }
}
