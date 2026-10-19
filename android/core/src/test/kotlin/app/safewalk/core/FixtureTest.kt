package app.safewalk.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** Runs app-spec/fixtures/expected_routes.json against the Kotlin implementation. */
class FixtureTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()

    private val manifestText = String(res("manifest.json"))
    private val manifest = SafeWalkFormat.parseManifest(manifestText)
    private val data = DataLoader.load(manifestText, res(manifest.graph.path), res(manifest.risk.path))
    private val cases = Json.parseToJsonElement(String(res("expected_routes.json"))).jsonObject["cases"]!!.jsonArray

    @Test fun loadsFixture() {
        assertNotNull(data.risk)
        assertEquals(49, data.graph.nodeCount)
        assertEquals(84, data.graph.edgeCount)
        assertEquals("2026-10-19", data.risk!!.weekStart.toString())
        assertNull(data.graph.name(5))
    }

    @Test fun acceptsAnyModelFields() {
        // The live manifest carries numbers in "model" (e.g. "rounds": 829); they must not break loading.
        val text = manifestText.replace(Regex("\"model\"\\s*:\\s*\\{"), "\"model\": {\"rounds\": 829, \"seeds\": [1, 2], ")
        val m = SafeWalkFormat.parseManifest(text)
        assertEquals(829, m.model["rounds"]!!.jsonPrimitive.int)
        assertEquals(manifest.model["name"], m.model["name"])
    }

    @Test fun rejectsTamperedRisk() {
        val bad = res(manifest.risk.path).also { it[10] = (it[10] + 1).toByte() }
        val d = DataLoader.load(manifestText, res(manifest.graph.path), bad)
        assertNull(d.risk)
    }

    @Test fun slots() {
        for (c in cases) {
            val o = c.jsonObject
            val t = o["local_time"]?.jsonPrimitive?.content ?: continue
            val local = LocalDateTime.parse(t).atZone(data.zone)
            assertEquals(o["label"].toString(), o["slot"]!!.jsonPrimitive.int, TimeSlot.at(local, data.zone).index)
        }
    }

    @Test fun snapsAndRoutes() {
        for (c in cases) {
            val o = c.jsonObject
            val label = o["label"]!!.jsonPrimitive.content
            val start = o["start"]!!.jsonObject.let { LatLon(it["lat"]!!.jsonPrimitive.double, it["lon"]!!.jsonPrimitive.double) }
            val sa = data.snapper.snap(start)
            if (o["snap_start"] is JsonNull) { assertNull(label, sa); continue }
            checkSnap(label, o["snap_start"]!!.jsonObject, sa!!)
            val end = o["end"]!!.jsonObject.let { LatLon(it["lat"]!!.jsonPrimitive.double, it["lon"]!!.jsonPrimitive.double) }
            val sb = data.snapper.snap(end)!!
            checkSnap(label, o["snap_end"]!!.jsonObject, sb)
            val slot = o["slot"]!!.jsonPrimitive.int
            for ((name, exp) in o["routes"]!!.jsonObject) {
                val level = when (name) {
                    "direct" -> SafetyLevel.DIRECT; "balanced" -> SafetyLevel.BALANCED; else -> SafetyLevel.LOWEST_RISK
                }
                val r = data.router!!.route(sa, sb, slot, level.lambda)!!
                val e = exp.jsonObject
                val what = "$label / $name"
                assertEquals(what, e["length_m"]!!.jsonPrimitive.double, r.lengthM, 0.5)
                assertEquals(what, e["cost"]!!.jsonPrimitive.double, r.cost, 0.5)
                assertEquals(what, e["exposure"]!!.jsonPrimitive.double, r.exposure, 0.5)
                val edges = e["edges"]!!.jsonArray
                assertEquals(what, edges.size, r.pieces.size)
                edges.forEachIndexed { i, p ->
                    val a = p as JsonArray
                    assertEquals(what, a[0].jsonPrimitive.int, r.pieces[i].edge)
                    assertEquals(what, a[1].jsonPrimitive.double, r.pieces[i].from, 1e-4)
                    assertEquals(what, a[2].jsonPrimitive.double, r.pieces[i].to, 1e-4)
                }
            }
        }
    }

    private fun checkSnap(label: String, exp: JsonObject, got: SnapPoint) {
        assertEquals(label, exp["edge"]!!.jsonPrimitive.int, got.edge)
        assertEquals(label, exp["frac"]!!.jsonPrimitive.double, got.frac, 1e-4)
        assertEquals(label, exp["dist_m"]!!.jsonPrimitive.double, got.distM, 0.01)
    }

    @Test fun tripPlanner() {
        val c = cases[0].jsonObject
        val p = { k: String -> c[k]!!.jsonObject.let { LatLon(it["lat"]!!.jsonPrimitive.double, it["lon"]!!.jsonPrimitive.double) } }
        val res = TripPlanner(data).plan(p("start"), p("end"), TimeSlot.fromIndex(5), SafetyLevel.BALANCED)
        res as TripResult.Routes
        assertEquals(1072.6, res.direct.lengthM, 0.5)
        assertNotNull(res.safer)
        println("fixture trip: direct ${res.direct.lengthM.toInt()} m, safer ${res.safer!!.lengthM.toInt()} m, " +
            "risk -${res.riskReductionPct}%, steps ${res.safer!!.steps}")
        assertEquals(TripResult.OutsideArea,
            TripPlanner(data).plan(LatLon(41.80, -87.63), p("end"), TimeSlot.fromIndex(5), SafetyLevel.BALANCED))
    }
}
