package app.safewalk.core

import app.safewalk.core.RouteExplanation.Kind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplanationTest {
    private fun block(edge: Int, street: String?, metres: Double = 100.0) = HigherRiskBlock(edge, street, metres)
    private fun route(lengthM: Double, vararg edges: Int) =
        Route(edges.map { RoutePiece(it, 0.0, 1.0) }, lengthM, lengthM, 0.0)

    @Test fun sentences() {
        assertEquals("Avoids 3 higher-risk blocks on S State St, 4% longer.",
            RouteExplanation(Kind.LOWER_RISK, 3, "S State St", true, 40, 4).text)
        assertEquals("Avoids 5 higher-risk blocks, mostly on S State St, 12% longer.",
            RouteExplanation(Kind.LOWER_RISK, 5, "S State St", false, 40, 12).text)
        assertEquals("Avoids 1 higher-risk block, about the same distance.",
            RouteExplanation(Kind.LOWER_RISK, 1, null, false, 15, 0).text)
        assertEquals("Takes lower-risk streets for 23% lower predicted risk, 6% longer.",
            RouteExplanation(Kind.LOWER_RISK, 0, null, false, 23, 6).text)
        assertEquals("The direct route is already the lower-risk choice at this time.",
            RouteExplanation(Kind.DIRECT_IS_LOWER_RISK).text)
        assertEquals("Shortest route; passes 2 higher-risk blocks on W Madison St.",
            RouteExplanation(Kind.DIRECT, 2, "W Madison St", true).text)
        assertEquals("Shortest route, with no higher-risk blocks at this time.", RouteExplanation(Kind.DIRECT).text)
        assertEquals("Shortest route. No risk forecast is loaded, so streets aren't compared.",
            RouteExplanation(Kind.NO_FORECAST).text)
    }

    @Test fun countsOnlyBlocksTheSaferRouteSkips() {
        val direct = route(1000.0, 1, 2, 3, 4)
        val safer = route(1040.0, 1, 5, 6, 4)
        val blocks = listOf(block(1, "S State St"), block(2, "S State St"), block(3, "S State St"), block(4, "E 63rd St"))
        val e = RouteExplanation.lowerRisk(direct, safer, blocks, 40)
        assertEquals(RouteExplanation(Kind.LOWER_RISK, 2, "S State St", true, 40, 4), e)
    }

    @Test fun mainStreetNeedsMoreThanHalf() {
        assertEquals(null to false, RouteExplanation.mainStreet(listOf(block(1, "A St"), block(2, "B St"))))
        assertEquals("A St" to false,
            RouteExplanation.mainStreet(listOf(block(1, "A St"), block(2, "A St"), block(3, null))))
        assertEquals("A St" to true, RouteExplanation.mainStreet(listOf(block(1, "A St"), block(2, "A St"))))
        assertEquals(null to false, RouteExplanation.mainStreet(emptyList()))
    }

    @Test fun fixtureTrips() {
        fun res(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()
        val mText = String(res("manifest.json"))
        val m = SafeWalkFormat.parseManifest(mText)
        val data = DataLoader.load(mText, res(m.graph.path), res(m.risk.path))
        val c = Json.parseToJsonElement(String(res("expected_routes.json"))).jsonObject["cases"]!!.jsonArray[0].jsonObject
        val p = { k: String -> c[k]!!.jsonObject.let { LatLon(it["lat"]!!.jsonPrimitive.double, it["lon"]!!.jsonPrimitive.double) } }
        val planner = TripPlanner(data)

        val safer = planner.plan(p("start"), p("end"), TimeSlot.fromIndex(5), SafetyLevel.BALANCED) as TripResult.Routes
        val e = safer.explanation
        // Same sentence the iOS RouteExplanationTests expects for this trip.
        assertEquals("Avoids 1 higher-risk block on W Adams St, 6% longer.", e.text)
        assertEquals(Kind.LOWER_RISK, e.kind)
        assertEquals(((safer.safer!!.lengthM / safer.direct.lengthM - 1) * 100).toInt().toDouble(), e.longerPct.toDouble(), 1.0)
        val saferEdges = safer.safer!!.route.pieces.map { it.edge }.toSet()
        assertEquals(safer.direct.higherRiskBlocks.count { it.edge !in saferEdges }, e.blocks)
        assertEquals(safer.direct.higherRiskStretches, safer.direct.higherRiskBlocks.size)
        assertTrue(e.text.startsWith("Avoids ") || e.text.startsWith("Takes lower-risk streets"))

        val direct = planner.plan(p("start"), p("end"), TimeSlot.fromIndex(5), SafetyLevel.DIRECT) as TripResult.Routes
        assertEquals("Shortest route; passes 1 higher-risk block on W Adams St.", direct.explanation.text)
        assertEquals(Kind.DIRECT, direct.explanation.kind)
        assertEquals(direct.direct.higherRiskStretches, direct.explanation.blocks)

        val noForecast = TripPlanner(SafeWalkData(data.manifest, data.graph, null))
            .plan(p("start"), p("end"), TimeSlot.fromIndex(5), SafetyLevel.BALANCED) as TripResult.Routes
        assertEquals(Kind.NO_FORECAST, noForecast.explanation.kind)
    }
}
