package app.safewalk.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.Inflater

/**
 * SafeWalk data files, format version 1 (app-spec/reference/safewalk_format.py is the source of truth).
 * Both binary files are published raw-DEFLATE compressed; decode functions take the inflated bytes.
 */
object SafeWalkFormat {
    const val FORMAT_VERSION = 1
    const val N_SLOTS = 12
    const val NO_NAME = 0xFFFF
    const val SNAP_MAX_M = 150.0
    const val M_PER_DEG_LAT = 110_950.0

    private const val GRAPH_HDR_SIZE = 4 + 2 + 2 + 4 * 5 + 4 * 4   // "<4sHHIIIII4i"
    private const val RISK_HDR_SIZE = 4 + 2 + 2 + 4 + 16 + 4 + 2 + 2 // "<4sHHI16sIH2x"

    val json = Json { ignoreUnknownKeys = true }

    fun parseManifest(text: String): Manifest = json.decodeFromString(Manifest.serializer(), text)

    /** Raw DEFLATE (RFC 1951, no zlib header). */
    fun inflate(blob: ByteArray, expectedSize: Int = -1): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(blob)
            val out = ByteArrayOutputStream(if (expectedSize > 0) expectedSize else blob.size * 4)
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            require(inf.finished()) { "truncated data file" }
            return out.toByteArray()
        } finally {
            inf.end()
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun graphIdOf(rawGraph: ByteArray): String = sha256Hex(rawGraph).substring(0, 16)

    fun decodeGraph(raw: ByteArray): StreetGraph {
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "SWG1") { "not a SafeWalk graph file" }
        val ver = b.short.toInt() and 0xFFFF
        require(ver == FORMAT_VERSION) { "graph format $ver not supported" }
        b.short // flags
        val n = b.int
        val e = b.int
        val ns = b.int
        val nn = b.int
        val nb = b.int
        val bbox = IntArray(4) { b.int }
        check(b.position() == GRAPH_HDR_SIZE)

        val lat = IntArray(n)
        val lon = IntArray(n)
        for (i in 0 until n) { lat[i] = b.int; lon[i] = b.int }
        val eu = IntArray(e) { b.int }
        val ev = IntArray(e) { b.int }
        val length = FloatArray(e) { b.float }
        val shapeOff = IntArray(e + 1) { b.int }
        val sLat = IntArray(ns)
        val sLon = IntArray(ns)
        for (i in 0 until ns) { sLat[i] = b.int; sLon[i] = b.int }
        val nameIdx = IntArray(e) { b.short.toInt() and 0xFFFF }
        val roadClass = ByteArray(e).also { b.get(it) }
        b.position(b.position() + (-b.position()).mod(4))
        val names = if (nn > 0) String(raw, b.position(), nb, Charsets.UTF_8).split("\n") else emptyList()
        require(b.position() + nb == raw.size) { "graph file has trailing bytes" }
        return StreetGraph(lat, lon, eu, ev, length, shapeOff, sLat, sLon, nameIdx, roadClass, names,
            bbox.map { it / 1e6 })
    }

    fun decodeRisk(raw: ByteArray): RiskTable {
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "SWR1") { "not a SafeWalk risk file" }
        val ver = b.short.toInt() and 0xFFFF
        val ns = b.short.toInt() and 0xFFFF
        require(ver == FORMAT_VERSION && ns == N_SLOTS) { "risk format $ver/$ns not supported" }
        val e = b.int
        val gid = ByteArray(16).also { b.get(it) }
        val days = b.int
        val scale = b.short.toInt() and 0xFFFF
        b.short // padding
        check(b.position() == RISK_HDR_SIZE)
        val thr = Array(3) { IntArray(N_SLOTS) { b.short.toInt() and 0xFFFF } }
        val risk = CharArray(e * N_SLOTS) { b.short.toInt().toChar() }   // Char = unsigned 16-bit
        require(b.position() == raw.size) { "risk file size mismatch" }
        return RiskTable(
            graphId = String(gid, Charsets.US_ASCII),
            weekStart = LocalDate.ofEpochDay(days.toLong()),
            scale = scale,
            edgeCount = e,
            values = risk,
            elevated = thr[0], high = thr[1], veryHigh = thr[2],
        )
    }
}

@Serializable
data class Manifest(
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("min_app_format") val minAppFormat: Int = 1,
    val city: String,
    @SerialName("display_name") val displayName: String,
    val timezone: String,
    val bbox: List<Double>,
    @SerialName("generated_at") val generatedAt: String,
    val graph: GraphEntry,
    val risk: RiskEntry,
    // Free-form (the pipeline adds numbers such as "rounds"), so keep it as raw JSON.
    val model: JsonObject = JsonObject(emptyMap()),
) {
    /** A text field from `model`, for the About screen. */
    fun modelText(key: String): String? = (model[key] as? JsonPrimitive)?.contentOrNull

    @Serializable
    data class GraphEntry(
        val path: String,
        @SerialName("graph_id") val graphId: String,
        val bytes: Int,
        @SerialName("raw_bytes") val rawBytes: Int,
        val sha256: String,
    )

    @Serializable
    data class RiskEntry(
        val path: String,
        @SerialName("week_start") val weekStart: String,
        @SerialName("valid_until") val validUntil: String,
        val bytes: Int,
        @SerialName("raw_bytes") val rawBytes: Int,
        val sha256: String,
    )
}

/** Walking street network. Coordinates are degrees x 1e6; shape points are each edge's interior, oriented u -> v. */
class StreetGraph(
    val lat: IntArray,
    val lon: IntArray,
    val eu: IntArray,
    val ev: IntArray,
    val length: FloatArray,
    val shapeOff: IntArray,
    val shapeLat: IntArray,
    val shapeLon: IntArray,
    val nameIdx: IntArray,
    val roadClass: ByteArray,
    val names: List<String>,
    /** [minLat, minLon, maxLat, maxLon] */
    val bbox: List<Double>,
) {
    val nodeCount get() = lat.size
    val edgeCount get() = eu.size

    fun name(edge: Int): String? = nameIdx[edge].takeIf { it != SafeWalkFormat.NO_NAME }?.let { names[it] }

    /** Compressed-sparse-row adjacency: for node x, edges adjEdge[adjStart[x] until adjStart[x + 1]]. */
    val adjStart: IntArray
    val adjEdge: IntArray

    init {
        val deg = IntArray(nodeCount + 1)
        for (e in 0 until edgeCount) { deg[eu[e] + 1]++; deg[ev[e] + 1]++ }
        for (i in 0 until nodeCount) deg[i + 1] += deg[i]
        adjStart = deg.copyOf()
        adjEdge = IntArray(2 * edgeCount)
        val fill = deg.copyOf()
        for (e in 0 until edgeCount) { adjEdge[fill[eu[e]]++] = e; adjEdge[fill[ev[e]]++] = e }
    }

    /** Polyline of edge e from u to v, as (lat, lon) degree pairs. */
    fun edgePoints(e: Int): List<LatLon> {
        val out = ArrayList<LatLon>(shapeOff[e + 1] - shapeOff[e] + 2)
        out += LatLon(lat[eu[e]] / 1e6, lon[eu[e]] / 1e6)
        for (k in shapeOff[e] until shapeOff[e + 1]) out += LatLon(shapeLat[k] / 1e6, shapeLon[k] / 1e6)
        out += LatLon(lat[ev[e]] / 1e6, lon[ev[e]] / 1e6)
        return out
    }
}

/** Relative risk per edge and time slot: stored / scale = multiple of the city average for that slot. */
class RiskTable(
    val graphId: String,
    val weekStart: LocalDate,
    val scale: Int,
    val edgeCount: Int,
    val values: CharArray,
    val elevated: IntArray,
    val high: IntArray,
    val veryHigh: IntArray,
) {
    fun stored(edge: Int, slot: Int): Int = values[edge * SafeWalkFormat.N_SLOTS + slot].code
    fun relative(edge: Int, slot: Int): Double = stored(edge, slot).toDouble() / scale

    fun band(edge: Int, slot: Int): RiskBand {
        val q = stored(edge, slot)
        return when {
            q >= veryHigh[slot] -> RiskBand.VERY_HIGH
            q >= high[slot] -> RiskBand.HIGH
            q >= elevated[slot] -> RiskBand.ELEVATED
            else -> RiskBand.NORMAL
        }
    }
}

enum class RiskBand { NORMAL, ELEVATED, HIGH, VERY_HIGH }

data class LatLon(val lat: Double, val lon: Double)
