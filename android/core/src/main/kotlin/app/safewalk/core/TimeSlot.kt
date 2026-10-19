package app.safewalk.core

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** One of 12 forecast slots: weekday or weekend x six 4-hour windows. index = weekend * 6 + hour / 4. */
data class TimeSlot(val weekend: Boolean, val window: Int) {
    init { require(window in 0 until 6) }

    val index get() = (if (weekend) 6 else 0) + window

    val windowLabel get() = WINDOW_LABELS[window]
    val label get() = (if (weekend) "Weekend" else "Weekday") + " " + windowLabel

    companion object {
        val WINDOW_LABELS = listOf("12–4 am", "4–8 am", "8 am–12 pm", "12–4 pm", "4–8 pm", "8 pm–12 am")

        fun fromIndex(i: Int) = TimeSlot(i >= 6, i % 6)

        /** The slot for a wall-clock time in the city's timezone. Weekend = Saturday or Sunday. */
        fun at(time: ZonedDateTime, zone: ZoneId): TimeSlot {
            val local = time.withZoneSameInstant(zone)
            val weekend = local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY
            return TimeSlot(weekend, local.hour / 4)
        }

        fun now(zone: ZoneId) = at(ZonedDateTime.now(zone), zone)
    }
}
