package app.safewalk.core

import kotlin.math.abs
import kotlin.math.hypot

/** How strongly the route avoids risky streets: cost = length x (1 + lambda x relative risk). */
enum class SafetyLevel(val lambda: Double, val label: String) {
    DIRECT(0.0, "Direct"),
    BALANCED(0.5, "Balanced"),
    LOWEST_RISK(1.0, "Lowest risk"),
}

/** One stretch of a route: edge from fraction [from] to fraction [to] (from > to means walking v -> u). */
data class RoutePiece(val edge: Int, val from: Double, val to: Double)

data class Route(
    val pieces: List<RoutePiece>,
    val lengthM: Double,
    val cost: Double,
    /** sum of metres x relative risk; divide by length for the average relative risk. */
    val exposure: Double,
) {
    val averageRisk get() = if (lengthM > 0) exposure / lengthM else 0.0
}

/**
 * A* over the street graph with virtual start and target nodes at the snapped points.
 * Mirrors route() in app-spec/reference/safewalk_format.py, including the order edges are relaxed in.
 */
class Router(private val g: StreetGraph, private val risk: RiskTable) {
    init { require(risk.edgeCount == g.edgeCount) { "risk table does not match the street graph" } }

    fun route(a: SnapPoint, b: SnapPoint, slot: TimeSlot, level: SafetyLevel): Route? =
        route(a, b, slot.index, level.lambda)

    fun route(a: SnapPoint, b: SnapPoint, slot: Int, lambda: Double): Route? {
        val n = g.nodeCount
        val s = n
        val t = n + 1
        fun w(e: Int) = g.length[e].toDouble() * (1 + lambda * risk.relative(e, slot))

        val ea = a.edge; val fa = a.frac; val eb = b.edge; val fb = b.frac
        val ebU = g.eu[eb]; val ebV = g.ev[eb]

        val target = g.pointAt(eb, fb)
        val kx = mPerDegLon(target.lat)
        val tLat = target.lat; val tLon = target.lon
        fun h(x: Int): Double = if (x >= n) 0.0 else
            0.999 * hypot((g.lon[x] / 1e6 - tLon) * kx, (g.lat[x] / 1e6 - tLat) * SafeWalkFormat.M_PER_DEG_LAT)

        val dist = DoubleArray(n + 2) { Double.POSITIVE_INFINITY }
        val prevNode = IntArray(n + 2) { -1 }
        val prevEdge = IntArray(n + 2) { -1 }
        val prevFrom = DoubleArray(n + 2)
        val prevTo = DoubleArray(n + 2)
        val done = BooleanArray(n + 2)
        val heap = Heap()

        fun relax(x: Int, d: Double, y: Int, c: Double, e: Int, f: Double, to: Double) {
            val nd = d + c
            if (nd < dist[y] - 1e-9) {
                dist[y] = nd; prevNode[y] = x; prevEdge[y] = e; prevFrom[y] = f; prevTo[y] = to
                heap.push(nd + h(y), nd, y)
            }
        }

        dist[s] = 0.0
        heap.push(h(s), 0.0, s)
        while (heap.size > 0) {
            val d = heap.topD; val x = heap.topX
            heap.pop()
            if (done[x]) continue
            done[x] = true
            if (x == t) break
            if (x == s) {
                relax(s, d, g.eu[ea], w(ea) * fa, ea, fa, 0.0)
                relax(s, d, g.ev[ea], w(ea) * (1 - fa), ea, fa, 1.0)
                if (ea == eb) relax(s, d, t, w(ea) * abs(fb - fa), ea, fa, fb)
                continue
            }
            if (x == ebU) relax(x, d, t, w(eb) * fb, eb, 0.0, fb)
            if (x == ebV) relax(x, d, t, w(eb) * (1 - fb), eb, 1.0, fb)
            for (k in g.adjStart[x] until g.adjStart[x + 1]) {
                val e = g.adjEdge[k]
                if (g.eu[e] == x) relax(x, d, g.ev[e], w(e), e, 0.0, 1.0)
                if (g.ev[e] == x) relax(x, d, g.eu[e], w(e), e, 1.0, 0.0)
            }
        }
        if (dist[t].isInfinite()) return null

        val pieces = ArrayList<RoutePiece>()
        var x = t
        while (x != s) {
            pieces += RoutePiece(prevEdge[x], prevFrom[x], prevTo[x])
            x = prevNode[x]
        }
        pieces.reverse()
        var length = 0.0; var expo = 0.0
        for (p in pieces) {
            val l = g.length[p.edge].toDouble() * abs(p.to - p.from)
            length += l; expo += l * risk.relative(p.edge, slot)
        }
        return Route(pieces, length, dist[t], expo)
    }

    /** Binary min-heap of (f, d, node), ordered like Python tuples so ties resolve the same way. */
    private class Heap {
        private var f = DoubleArray(1024); private var dd = DoubleArray(1024); private var xs = IntArray(1024)
        var size = 0; private set
        val topD get() = dd[0]
        val topX get() = xs[0]

        private fun less(i: Int, j: Int) =
            f[i] < f[j] || (f[i] == f[j] && (dd[i] < dd[j] || (dd[i] == dd[j] && xs[i] < xs[j])))

        private fun swap(i: Int, j: Int) {
            val a = f[i]; f[i] = f[j]; f[j] = a
            val b = dd[i]; dd[i] = dd[j]; dd[j] = b
            val c = xs[i]; xs[i] = xs[j]; xs[j] = c
        }

        fun push(fv: Double, dv: Double, x: Int) {
            if (size == f.size) { f = f.copyOf(size * 2); dd = dd.copyOf(size * 2); xs = xs.copyOf(size * 2) }
            f[size] = fv; dd[size] = dv; xs[size] = x
            var i = size++
            while (i > 0) { val p = (i - 1) / 2; if (!less(i, p)) break; swap(i, p); i = p }
        }

        fun pop() {
            size--
            if (size == 0) return
            f[0] = f[size]; dd[0] = dd[size]; xs[0] = xs[size]
            var i = 0
            while (true) {
                val l = 2 * i + 1; val r = l + 1; var m = i
                if (l < size && less(l, m)) m = l
                if (r < size && less(r, m)) m = r
                if (m == i) break
                swap(i, m); i = m
            }
        }
    }
}
