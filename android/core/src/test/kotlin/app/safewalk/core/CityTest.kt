package app.safewalk.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Full-city check against routes from the reference router (tools/city_golden.py writes city_golden.json
 * next to manifest.json). Runs only with -PsafewalkData=<folder>.
 */
class CityTest {
    private val folder = System.getProperty("safewalk.data")?.let(::File)

    @Test fun matchesReferenceOnChicago() {
        assumeTrue(folder != null && File(folder, "city_golden.json").exists())
        val dir = folder!!
        val mText = File(dir, "manifest.json").readText()
        val m = SafeWalkFormat.parseManifest(mText)
        val t0 = System.nanoTime()
        val data = DataLoader.load(mText, File(dir, m.graph.path).readBytes(), File(dir, m.risk.path).readBytes())
        println("load + index: ${(System.nanoTime() - t0) / 1_000_000} ms, ${data.graph.nodeCount} nodes")
        val cases = Json.parseToJsonElement(File(dir, "city_golden.json").readText()).jsonObject["cases"]!!.jsonArray
        for ((ci, c) in cases.withIndex()) {
            val o = c.jsonObject
            fun pt(k: String) = o[k]!!.jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
            val sa = data.snapper.snap(pt("start"))!!
            val sb = data.snapper.snap(pt("end"))!!
            val es = o["snap_start"]!!.jsonArray
            assertEquals("case $ci snap", es[0].jsonPrimitive.int, sa.edge)
            assertEquals("case $ci snap", es[1].jsonPrimitive.double, sa.frac, 1e-4)
            for ((name, exp) in o["routes"]!!.jsonObject) {
                val lam = mapOf("direct" to 0.0, "balanced" to 0.5, "lowest_risk" to 1.0)[name]!!
                val t1 = System.nanoTime()
                val r = data.router!!.route(sa, sb, o["slot"]!!.jsonPrimitive.int, lam)!!
                val ms = (System.nanoTime() - t1) / 1e6
                val e = exp.jsonObject
                assertEquals("case $ci $name", e["length_m"]!!.jsonPrimitive.double, r.lengthM, 0.5)
                assertEquals("case $ci $name", e["cost"]!!.jsonPrimitive.double, r.cost, 0.5)
                val edges = e["edges"]!!.jsonArray.map { (it as JsonArray)[0].jsonPrimitive.int }
                assertEquals("case $ci $name", edges, r.pieces.map { it.edge })
                println("case $ci $name: ${r.lengthM.toInt()} m, %.1f ms".format(ms))
            }
        }
    }
}
