package app.safewalk.core

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the nearest point on any street within [SafeWalkFormat.SNAP_MAX_M]. Uses a bucket grid, but scores
 * candidates exactly as the reference brute force does (same tie rule, edges in ascending order).
 */
class Snapper(private val g: StreetGraph, cellM: Double = 200.0) {
    companion object { const val BBOX_MARGIN_M = 200.0 }

    private val lat0 = g.bbox[0]
    private val lon0 = g.bbox[1]
    private val dLat = cellM / SafeWalkFormat.M_PER_DEG_LAT
    private val dLon = cellM / mPerDegLon((g.bbox[0] + g.bbox[2]) / 2)
    private val nx = ((g.bbox[3] - lon0) / dLon).toInt() + 1
    private val ny = ((g.bbox[2] - lat0) / dLat).toInt() + 1
    private val cellStart: IntArray
    private val cellEdges: IntArray

    init {
        val lists = HashMap<Int, MutableList<Int>>()
        for (e in 0 until g.edgeCount) {
            var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
            for (p in g.edgePoints(e)) { a = min(a, p.lat); b = min(b, p.lon); c = max(c, p.lat); d = max(d, p.lon) }
            for (cy in cy(a)..cy(c)) for (cx in cx(b)..cx(d)) lists.getOrPut(cy * nx + cx) { ArrayList(4) }.add(e)
        }
        cellStart = IntArray(nx * ny + 1)
        for ((k, v) in lists) cellStart[k + 1] = v.size
        for (i in 0 until nx * ny) cellStart[i + 1] += cellStart[i]
        cellEdges = IntArray(cellStart.last())
        for ((k, v) in lists) v.forEachIndexed { i, e -> cellEdges[cellStart[k] + i] = e }
    }

    private fun cx(lon: Double) = floor((lon - lon0) / dLon).toInt().coerceIn(0, nx - 1)
    private fun cy(lat: Double) = floor((lat - lat0) / dLat).toInt().coerceIn(0, ny - 1)

    fun inBounds(p: LatLon, marginM: Double = BBOX_MARGIN_M): Boolean {
        val ml = marginM / SafeWalkFormat.M_PER_DEG_LAT
        val mo = marginM / mPerDegLon(p.lat)
        return p.lat >= g.bbox[0] - ml && p.lat <= g.bbox[2] + ml && p.lon >= g.bbox[1] - mo && p.lon <= g.bbox[3] + mo
    }

    fun snap(p: LatLon): SnapPoint? {
        if (!inBounds(p)) return null
        val r = SafeWalkFormat.SNAP_MAX_M
        val ml = r / SafeWalkFormat.M_PER_DEG_LAT
        val mo = r / mPerDegLon(p.lat)
        val cand = sortedSetOf<Int>()
        for (y in cy(p.lat - ml)..cy(p.lat + ml)) for (x in cx(p.lon - mo)..cx(p.lon + mo)) {
            val k = y * nx + x
            for (i in cellStart[k] until cellStart[k + 1]) cand += cellEdges[i]
        }
        val kx = mPerDegLon(p.lat)
        val ky = SafeWalkFormat.M_PER_DEG_LAT
        var best: SnapPoint? = null
        for (e in cand) {
            val pts = g.edgePoints(e)
            val xs = DoubleArray(pts.size) { (pts[it].lon - p.lon) * kx }
            val ys = DoubleArray(pts.size) { (pts[it].lat - p.lat) * ky }
            val seg = DoubleArray(pts.size - 1) { hypot(xs[it + 1] - xs[it], ys[it + 1] - ys[it]) }
            val tot = seg.sum().takeIf { it != 0.0 } ?: 1.0
            var run = 0.0
            for (i in seg.indices) {
                val x0 = xs[i]; val y0 = ys[i]; val dx = xs[i + 1] - x0; val dy = ys[i + 1] - y0; val l = seg[i]
                val t = if (l == 0.0) 0.0 else (-(x0 * dx + y0 * dy) / (l * l)).coerceIn(0.0, 1.0)
                val d = hypot(x0 + t * dx, y0 + t * dy)
                if (best == null || d < best.distM - 1e-9) best = SnapPoint(e, (run + t * l) / tot, d)
                run += l
            }
        }
        return best?.takeIf { it.distM <= r }
    }
}
