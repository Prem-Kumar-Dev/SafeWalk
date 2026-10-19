package app.safewalk.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

internal fun mPerDegLon(lat: Double) = 111_320.0 * cos(Math.toRadians(lat))

/** Straight-line metres between two points, using the local flat projection the reference uses. */
fun metresBetween(a: LatLon, b: LatLon): Double =
    hypot((b.lon - a.lon) * mPerDegLon(a.lat), (b.lat - a.lat) * SafeWalkFormat.M_PER_DEG_LAT)

/** A point on the street network: a fraction of edge's polyline length, measured from its u end. */
data class SnapPoint(val edge: Int, val frac: Double, val distM: Double)

private fun segLengths(pts: List<LatLon>): DoubleArray {
    val kx = mPerDegLon(pts[0].lat)
    return DoubleArray(pts.size - 1) { i ->
        hypot((pts[i + 1].lon - pts[i].lon) * kx, (pts[i + 1].lat - pts[i].lat) * SafeWalkFormat.M_PER_DEG_LAT)
    }
}

/** Point a fraction of the way along edge e, from u. Mirrors point_at() in the reference. */
fun StreetGraph.pointAt(e: Int, frac: Double): LatLon {
    val pts = edgePoints(e)
    val seg = segLengths(pts)
    var goal = frac * seg.sum()
    for (i in seg.indices) {
        val l = seg[i]
        if (goal <= l || i == seg.lastIndex) {
            val t = if (l == 0.0) 0.0 else min(goal / l, 1.0)
            val a = pts[i]; val b = pts[i + 1]
            return LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
        }
        goal -= l
    }
    return pts.last()
}

/** The part of edge e between two fractions, in walking order (from may be greater than to). */
fun StreetGraph.piecePoints(e: Int, from: Double, to: Double): List<LatLon> {
    val lo = min(from, to); val hi = maxOf(from, to)
    val pts = edgePoints(e)
    val seg = segLengths(pts)
    val total = seg.sum()
    val out = ArrayList<LatLon>()
    out += pointAt(e, lo)
    var run = 0.0
    for (i in 1 until pts.size - 1) {
        run += seg[i - 1]
        val f = if (total == 0.0) 0.0 else run / total
        if (f > lo && f < hi) out += pts[i]
    }
    out += pointAt(e, hi)
    if (from > to) out.reverse()
    return out
}
