package app.safewalk.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A loaded, verified graph + risk pair, ready to route on. Build it off the main thread. */
class SafeWalkData(
    val manifest: Manifest,
    val graph: StreetGraph,
    /** null when no risk file matches the graph: route Direct only. */
    val risk: RiskTable?,
) {
    val snapper = Snapper(graph)
    val router: Router? = risk?.let { Router(graph, it) }
    private val zeroRisk by lazy {
        RiskTable(manifest.graph.graphId, java.time.LocalDate.MIN, 100, graph.edgeCount,
            CharArray(graph.edgeCount * SafeWalkFormat.N_SLOTS), IntArray(12) { Int.MAX_VALUE },
            IntArray(12) { Int.MAX_VALUE }, IntArray(12) { Int.MAX_VALUE })
    }
    /** Router used when no forecast is available: every edge risk 0, so all levels give the direct route. */
    val directRouter by lazy { Router(graph, zeroRisk) }
    val riskOrZero: RiskTable get() = risk ?: zeroRisk
    val zone: java.time.ZoneId = java.time.ZoneId.of(manifest.timezone)
}

enum class Turn { START, STRAIGHT, LEFT, RIGHT, U_TURN }

data class Step(val street: String, val lengthM: Double, val turn: Turn)

/** A route plus everything the route sheet shows about it. */
data class RouteDetails(
    val route: Route,
    val polyline: List<LatLon>,
    /** Route line split into runs of the same risk band, in walking order, for colouring (03 section 5). */
    val bandRuns: List<Pair<RiskBand, List<LatLon>>>,
    val higherRiskStretches: Int,
    val steps: List<Step>,
) {
    val lengthM get() = route.lengthM
    val walkingMinutes get() = (route.lengthM / WALK_SPEED_MS / 60).roundToInt()

    companion object { const val WALK_SPEED_MS = 1.3 }
}

sealed interface TripResult {
    data class Routes(
        val slot: TimeSlot,
        val level: SafetyLevel,
        val direct: RouteDetails,
        /** null when only one route is shown (level Direct, same route, or under 10% less risk). */
        val safer: RouteDetails?,
        /** true when a safer level was asked for but the direct route is already the lower-risk choice. */
        val directIsLowerRisk: Boolean,
        val riskReductionPct: Int,
        val extraMetres: Int,
        val extraMinutes: Int,
        val longWalk: Boolean,
        val forecastAvailable: Boolean,
    ) : TripResult

    data object OutsideArea : TripResult
    data object TooFarFromStreet : TripResult
    data object AlreadyThere : TripResult
    data object NoRoute : TripResult
}

/** Steps 2–4 of app-spec/03-routing.md: snap, route Direct and the chosen level, summarise. */
class TripPlanner(private val data: SafeWalkData) {

    fun plan(start: LatLon, end: LatLon, slot: TimeSlot, level: SafetyLevel): TripResult {
        val snapper = data.snapper
        if (!snapper.inBounds(start) || !snapper.inBounds(end)) return TripResult.OutsideArea
        val a = snapper.snap(start) ?: return TripResult.TooFarFromStreet
        val b = snapper.snap(end) ?: return TripResult.TooFarFromStreet
        val g = data.graph
        if (metresBetween(g.pointAt(a.edge, a.frac), g.pointAt(b.edge, b.frac)) < 20.0) return TripResult.AlreadyThere

        val router = data.router ?: data.directRouter
        val forecast = data.router != null
        val direct = router.route(a, b, slot, SafetyLevel.DIRECT) ?: return TripResult.NoRoute
        val directDetails = details(direct, slot)
        var safer: RouteDetails? = null
        var cut = 0
        var lowerAlready = false
        if (level != SafetyLevel.DIRECT && forecast) {
            val s = router.route(a, b, slot, level) ?: return TripResult.NoRoute
            val reduction = if (direct.exposure > 0) 1 - s.exposure / direct.exposure else 0.0
            val same = s.pieces.map { it.edge } == direct.pieces.map { it.edge }
            if (same || reduction < 0.10) lowerAlready = true
            else { safer = details(s, slot); cut = (reduction * 100).roundToInt() }
        }
        val extra = safer?.let { it.lengthM - direct.lengthM } ?: 0.0
        return TripResult.Routes(
            slot = slot, level = level, direct = directDetails, safer = safer,
            directIsLowerRisk = lowerAlready, riskReductionPct = cut,
            extraMetres = extra.roundToInt(),
            extraMinutes = (extra / RouteDetails.WALK_SPEED_MS / 60).roundToInt(),
            longWalk = (safer ?: directDetails).lengthM > 15_000,
            forecastAvailable = forecast,
        )
    }

    fun details(route: Route, slot: TimeSlot): RouteDetails {
        val g = data.graph
        val risk = data.riskOrZero
        val pts = ArrayList<LatLon>()
        val runs = ArrayList<Pair<RiskBand, MutableList<LatLon>>>()
        for (p in route.pieces) {
            val piece = g.piecePoints(p.edge, p.from, p.to)
            for (q in piece) if (pts.lastOrNull() != q) pts += q
            val band = if (data.risk == null) RiskBand.NORMAL else risk.band(p.edge, slot.index)
            val last = runs.lastOrNull()
            if (last != null && last.first == band) piece.forEach { if (last.second.last() != it) last.second += it }
            else runs += band to piece.toMutableList()
        }
        val stretches = route.pieces.count {
            data.risk != null && g.length[it.edge] * abs(it.to - it.from) >= 25.0 &&
                risk.stored(it.edge, slot.index) >= risk.high[slot.index]
        }
        return RouteDetails(route, pts, runs, stretches, steps(route))
    }

    private fun steps(route: Route): List<Step> {
        val g = data.graph
        val out = ArrayList<Step>()
        var name: String? = null
        var len = 0.0
        var turn = Turn.START
        var prevBearing = Double.NaN
        for (p in route.pieces) {
            val pts = g.piecePoints(p.edge, p.from, p.to)
            val n = g.name(p.edge) ?: "path"
            val l = g.length[p.edge] * abs(p.to - p.from)
            if (l < 0.01) continue
            val inBearing = bearing(pts.first(), pts[1])
            if (name != null && n != name) {
                out += Step(name, len, turn)
                turn = turnFrom(prevBearing, inBearing)
                len = 0.0
            }
            name = n; len += l
            prevBearing = bearing(pts[pts.size - 2], pts.last())
        }
        if (name != null) out += Step(name, len, turn)
        return out
    }

    private fun bearing(a: LatLon, b: LatLon): Double {
        val y = sin(Math.toRadians(b.lon - a.lon)) * cos(Math.toRadians(b.lat))
        val x = (b.lat - a.lat) * Math.PI / 180
        return Math.toDegrees(atan2(y, x))
    }

    private fun turnFrom(before: Double, after: Double): Turn {
        if (before.isNaN()) return Turn.STRAIGHT
        var d = after - before
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return when {
            abs(d) < 30 -> Turn.STRAIGHT
            abs(d) > 150 -> Turn.U_TURN
            d > 0 -> Turn.RIGHT
            else -> Turn.LEFT
        }
    }
}

/** Off-route and arrival checks for walking mode (03-routing.md section 6). */
object WalkTracker {
    const val OFF_ROUTE_M = 40.0
    const val OFF_ROUTE_SECONDS = 15
    const val ARRIVED_M = 25.0

    fun distanceToPolyline(p: LatLon, line: List<LatLon>): Double {
        val kx = mPerDegLon(p.lat); val ky = SafeWalkFormat.M_PER_DEG_LAT
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val x0 = (line[i].lon - p.lon) * kx; val y0 = (line[i].lat - p.lat) * ky
            val dx = (line[i + 1].lon - line[i].lon) * kx; val dy = (line[i + 1].lat - line[i].lat) * ky
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(x0 * dx + y0 * dy) / l2).coerceIn(0.0, 1.0)
            best = minOf(best, kotlin.math.hypot(x0 + t * dx, y0 + t * dy))
        }
        return best
    }
}
