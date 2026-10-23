// Dates and clock times in the phone's language and its 12- or 24-hour clock.
package app.tingid.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private fun zoned(at: Long) = Instant.ofEpochSecond(at).atZone(ZoneId.systemDefault())

/** The clock time of `at` (seconds), like `09:24`. */
fun formatClock(at: Long): String = zoned(at).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))

/** A date and time, medium date and short time. */
fun formatTime(at: Long): String = zoned(at).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()))

/** A date for a payment's line, like `Fri 17 Oct 2026`. */
fun formatDay(at: Long): String = zoned(at).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault()))

/** The day of `at` (seconds) as a list heading: `Today`, `Yesterday`, `Mon 13 Oct`, or with the year when it is another. */
fun dayLabel(at: Long, now: Long = System.currentTimeMillis() / 1000): String {
    val day = zoned(at).toLocalDate()
    val today = zoned(now).toLocalDate()
    return when (ChronoUnit.DAYS.between(day, today)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", Locale.getDefault()))
    }
}

/** When something happened, short: the clock today, else the day and clock, or the day and year when it is another year. */
fun formatWhen(at: Long, now: Long = System.currentTimeMillis() / 1000): String {
    if (dayLabel(at, now) == "Today") return formatClock(at)
    val day: LocalDate = zoned(at).toLocalDate()
    val sameYear = day.year == zoned(now).toLocalDate().year
    return if (sameYear) "${day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))} ${formatClock(at)}"
    else day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
}
