package app.safewalk.android.ui

import app.safewalk.android.data.Units
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

fun formatDistance(m: Double, units: Units): String = when (units) {
    Units.KM -> if (m < 950) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(Locale.getDefault(), m / 1000)
    Units.MILES -> {
        val mi = m / 1609.344
        if (mi < 0.1) "${(m * 3.28084 / 50).roundToInt() * 50} ft" else "%.1f mi".format(Locale.getDefault(), mi)
    }
}

fun formatMinutes(min: Int): String = if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"

private val weekFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
fun formatWeek(d: LocalDate): String = d.format(weekFmt)
