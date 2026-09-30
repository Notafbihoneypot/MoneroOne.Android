package one.monero.moneroone.ui.screens.chart

import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * How a range labels time. Ticks land on calendar landmarks (midnight, the
 * 1st, January 1st, a quarter of the day), not at "N hours before now", so
 * the axis reads the same way every time it is opened. Matches iOS
 * ChartTimeAxis.
 */
enum class ChartTimeAxis {
    DAY, WEEK, MONTH, YEAR, ALL;

    /** Tick times from [startMs] to [endMs], on this range's landmarks. */
    fun ticks(
        startMs: Long,
        endMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault()
    ): List<Long> {
        if (endMs < startMs) return emptyList()
        val calendar = Calendar.getInstance(timeZone, locale)
        if (this == DAY) return dayTicks(startMs, endMs, calendar)

        val (field, amount) = step(endMs - startMs)
        val ticks = ArrayList<Long>()
        calendar.timeInMillis = firstTick(startMs, calendar)
        while (calendar.timeInMillis <= endMs && ticks.size < MAX_TICKS) {
            ticks.add(calendar.timeInMillis)
            calendar.add(field, amount)
        }
        return ticks
    }

    /** A range button as TalkBack says it: "1 week". English; pass it through tr. */
    val spokenName: String
        get() = when (this) {
            DAY -> "24 hours"
            WEEK -> "1 week"
            MONTH -> "1 month"
            YEAR -> "1 year"
            ALL -> "All time"
        }

    /** The time a chart on this range covers, as TalkBack says it: "past week". */
    val spokenSpan: String
        get() = when (this) {
            DAY -> "past 24 hours"
            WEEK -> "past week"
            MONTH -> "past month"
            YEAR -> "past year"
            ALL -> "all time"
        }

    private fun dayTicks(startMs: Long, endMs: Long, calendar: Calendar): List<Long> {
        val ticks = ArrayList<Long>()
        calendar.timeInMillis = startMs
        calendar.toStartOfDay()
        while (calendar.timeInMillis <= endMs && ticks.size < MAX_TICKS) {
            val day = calendar.timeInMillis
            // Set on the clock, not added as hours, so a DST day keeps its 5 AM.
            for (hour in DAY_TICK_HOURS) {
                calendar.timeInMillis = day
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                val tick = calendar.timeInMillis
                if (tick in startMs..endMs) ticks.add(tick)
            }
            calendar.timeInMillis = day
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        return ticks
    }

    private fun step(spanMs: Long): Pair<Int, Int> = when (this) {
        DAY -> Calendar.HOUR_OF_DAY to 5 // unused: dayTicks restarts at midnight
        WEEK -> Calendar.DAY_OF_MONTH to 1
        MONTH -> Calendar.DAY_OF_MONTH to 7
        YEAR -> Calendar.MONTH to 2
        // Every other year across the price history since 2014; every year
        // for a shorter span, so it still gets a few labels.
        ALL -> Calendar.YEAR to if (spanMs / (365.0 * DAY_MS) < 6) 1 else 2
    }

    /** The first landmark at or after [ms]. */
    private fun firstTick(ms: Long, calendar: Calendar): Long {
        if (this == DAY) return ms
        calendar.timeInMillis = ms
        calendar.toStartOfDay()
        when (this) {
            // Week starts, so the labels are the same weekday all the way across.
            MONTH -> while (calendar.get(Calendar.DAY_OF_WEEK) != calendar.firstDayOfWeek) {
                calendar.add(Calendar.DAY_OF_MONTH, -1)
            }
            YEAR -> calendar.set(Calendar.DAY_OF_MONTH, 1)
            ALL -> {
                calendar.set(Calendar.MONTH, Calendar.JANUARY)
                calendar.set(Calendar.DAY_OF_MONTH, 1)
            }
            else -> Unit
        }
        if (calendar.timeInMillis == ms) return ms
        when (this) {
            MONTH -> calendar.add(Calendar.DAY_OF_MONTH, 7)
            YEAR -> calendar.add(Calendar.MONTH, 1)
            ALL -> calendar.add(Calendar.YEAR, 1)
            else -> calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        return calendar.timeInMillis
    }

    companion object {
        const val MAX_TICKS = 64

        /**
         * 24h tick hours. Five-hour steps on purpose: a 12-hour clock repeats
         * its numerals for any step that divides 12. Five gives 12 AM, 5 AM,
         * 10 AM, 3 PM, 8 PM: every numeral in a day distinct.
         */
        val DAY_TICK_HOURS = listOf(0, 5, 10, 15, 20)

        private const val HOUR_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * HOUR_MS

        /** The axis whose ticks suit a span this long. The portfolio's "All" runs from weeks to years. */
        fun fitting(spanMs: Long): ChartTimeAxis = when {
            spanMs < 2 * DAY_MS -> DAY
            spanMs < 10 * DAY_MS -> WEEK
            spanMs < 60 * DAY_MS -> MONTH
            // Month names repeat past a year, so longer spans label years.
            spanMs < 400 * DAY_MS -> YEAR
            else -> ALL
        }
    }
}

/**
 * The system's word for yesterday, and how it joins a day to a time:
 * "Yesterday, 3:05 PM", "Gestern, 15:05", "昨日 15:05". Android's
 * DateUtils.getRelativeDateTimeString builds its text the same way; iOS
 * gets it from a relative DateFormatter.
 */
interface RelativeDayText {
    /** "Yesterday", capitalized to start a label. */
    val yesterday: String

    /** [day] and [time] in the locale's order and separator. */
    fun join(day: String, time: String): String
}

/** [RelativeDayText] from ICU, made on first use, so JVM tests can build [ChartDateFormats]. */
class IcuRelativeDayText(private val locale: Locale) : RelativeDayText {
    private val formatter by lazy {
        RelativeDateTimeFormatter.getInstance(
            ULocale.forLocale(locale),
            null,
            RelativeDateTimeFormatter.Style.LONG,
            DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE
        )
    }

    override val yesterday: String
        get() = formatter.format(RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.AbsoluteUnit.DAY)

    override fun join(day: String, time: String): String = formatter.combineDateAndTime(day, time)
}

/**
 * Chart dates in the user's locale and clock. [bestPattern] turns a
 * skeleton ("MMMd") into the locale's pattern; on a device that is
 * android.text.format.DateFormat.getBestDateTimePattern. Keeps one
 * formatter per skeleton, so use it from the main thread only.
 */
class ChartDateFormats(
    val locale: Locale,
    val timeZone: TimeZone,
    private val use24Hour: Boolean,
    private val relativeDays: RelativeDayText = IcuRelativeDayText(locale),
    private val bestPattern: (String) -> String
) {
    private val formats = HashMap<String, SimpleDateFormat>()
    private val calendar: Calendar = Calendar.getInstance(timeZone, locale)

    private val hourSkeleton get() = if (use24Hour) "HH" else "ha"
    private val timeSkeleton get() = if (use24Hour) "Hmm" else "hmm"

    /** Axis label for a tick: "5 AM", "Wed", "Sep 15", "Nov", "2022". */
    fun tickLabel(axis: ChartTimeAxis, ms: Long): String = when (axis) {
        ChartTimeAxis.DAY -> format(hourSkeleton, ms)
        ChartTimeAxis.WEEK -> format("EEE", ms)
        ChartTimeAxis.MONTH -> format("MMMd", ms)
        ChartTimeAxis.YEAR -> format("MMM", ms)
        ChartTimeAxis.ALL -> format("y", ms)
    }

    /**
     * Label for the sample under the finger. It names the day whenever a bare
     * time would be ambiguous, in the user's language, as iOS's relative
     * DateFormatter does (medium date, short time).
     */
    fun scrubLabel(axis: ChartTimeAxis, ms: Long, nowMs: Long = System.currentTimeMillis()): String = when (axis) {
        ChartTimeAxis.DAY -> {
            val day = startOfDay(ms)
            when (day) {
                startOfDay(nowMs) -> time(ms)
                startOfDay(nowMs, dayOffset = -1) -> relativeDays.join(relativeDays.yesterday, time(ms))
                else -> dateTime(ms)
            }
        }
        ChartTimeAxis.WEEK -> format("EEEMMMd$timeSkeleton", ms)
        ChartTimeAxis.MONTH -> format("MMMd$timeSkeleton", ms)
        ChartTimeAxis.YEAR, ChartTimeAxis.ALL -> format("yMMMd", ms)
    }

    /** "Sep 20, 2026, 3:05 PM": the medium date and short time, joined as the locale joins them. */
    fun dateTime(ms: Long): String = relativeDays.join(format("yMMMd", ms), time(ms))

    /**
     * "Sep 20, 2026, 3:05 PM" in the user's language and clock: iOS
     * `formatted(date: .abbreviated, time: .shortened)`.
     */
    fun abbreviatedDateTime(ms: Long): String = format("yMMMd$timeSkeleton", ms)

    private fun time(ms: Long): String = format(timeSkeleton, ms)

    private fun format(skeleton: String, ms: Long): String =
        formats.getOrPut(skeleton) {
            SimpleDateFormat(bestPattern(skeleton), locale).also { it.timeZone = timeZone }
        }.format(Date(ms))

    private fun startOfDay(ms: Long, dayOffset: Int = 0): Long {
        calendar.timeInMillis = ms
        calendar.toStartOfDay()
        if (dayOffset != 0) calendar.add(Calendar.DAY_OF_MONTH, dayOffset)
        return calendar.timeInMillis
    }
}

private fun Calendar.toStartOfDay() {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}
