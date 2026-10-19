package app.safewalk.core

import kotlin.math.hypot

/** Where the walker is along a route: for walking mode's distance left, next step, off-route and arrival. */
class RouteProgress(private val details: RouteDetails) {
    private val line = details.polyline
    private val cum = DoubleArray(line.size)
    private val stepStart: DoubleArray

    init {
        for (i in 1 until line.size) cum[i] = cum[i - 1] + metresBetween(line[i - 1], line[i])
        // step lengths come from edge lengths; rescale onto polyline metres so the two measures line up
        val k = if (details.lengthM > 0) cum.last() / details.lengthM else 1.0
        stepStart = DoubleArray(details.steps.size)
        for (i in 1 until details.steps.size) stepStart[i] = stepStart[i - 1] + details.steps[i - 1].lengthM * k
    }

    data class Fix(
        val offRouteM: Double,
        val alongM: Double,
        val remainingM: Double,
        val nextStep: Step?,
        val metresToNextStep: Double,
        val arrived: Boolean,
    )

    fun locate(p: LatLon): Fix {
        val kx = mPerDegLon(p.lat); val ky = SafeWalkFormat.M_PER_DEG_LAT
        var best = Double.MAX_VALUE; var along = 0.0
        for (i in 0 until line.size - 1) {
            val x0 = (line[i].lon - p.lon) * kx; val y0 = (line[i].lat - p.lat) * ky
            val dx = (line[i + 1].lon - line[i].lon) * kx; val dy = (line[i + 1].lat - line[i].lat) * ky
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(x0 * dx + y0 * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(x0 + t * dx, y0 + t * dy)
            if (d < best) { best = d; along = cum[i] + t * (cum[i + 1] - cum[i]) }
        }
        if (line.size == 1) { best = metresBetween(p, line[0]); along = 0.0 }
        val total = cum.last()
        val next = stepStart.indexOfFirst { it > along + 1 }
        val toEnd = metresBetween(p, line.last())
        return Fix(
            offRouteM = best,
            alongM = along,
            remainingM = (total - along).coerceAtLeast(0.0),
            nextStep = if (next >= 0) details.steps[next] else null,
            metresToNextStep = if (next >= 0) stepStart[next] - along else total - along,
            arrived = toEnd <= WalkTracker.ARRIVED_M,
        )
    }
}
