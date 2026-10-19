package app.safewalk.core

/** Thrown when a downloaded or bundled file fails a check; the caller keeps its previous data. */
class DataCheckException(message: String) : Exception(message)

/**
 * Verifies and decodes a manifest + compressed graph + compressed risk file, following step 3 of
 * "How an app updates" in app-spec/02-data-files.md. A risk file that fails is dropped (Direct-only routing);
 * a graph that fails throws.
 */
object DataLoader {
    fun checkBlob(name: String, blob: ByteArray, bytes: Int, sha256: String, rawBytes: Int): ByteArray {
        if (blob.size != bytes) throw DataCheckException("$name: ${blob.size} bytes, manifest says $bytes")
        if (SafeWalkFormat.sha256Hex(blob) != sha256) throw DataCheckException("$name: sha256 mismatch")
        val raw = try { SafeWalkFormat.inflate(blob, rawBytes) } catch (e: Exception) {
            throw DataCheckException("$name: cannot decompress (${e.message})")
        }
        if (raw.size != rawBytes) throw DataCheckException("$name: raw size ${raw.size}, manifest says $rawBytes")
        return raw
    }

    fun checkManifest(m: Manifest) {
        if (m.minAppFormat > SafeWalkFormat.FORMAT_VERSION) throw AppTooOldException()
        if (m.formatVersion != SafeWalkFormat.FORMAT_VERSION) throw DataCheckException("format ${m.formatVersion}")
        if (m.bbox.size != 4) throw DataCheckException("bbox")
    }

    fun decodeGraph(m: Manifest, blob: ByteArray): StreetGraph {
        val raw = checkBlob("graph", blob, m.graph.bytes, m.graph.sha256, m.graph.rawBytes)
        if (SafeWalkFormat.graphIdOf(raw) != m.graph.graphId) throw DataCheckException("graph_id mismatch")
        return try { SafeWalkFormat.decodeGraph(raw) } catch (e: IllegalArgumentException) {
            throw DataCheckException("graph: ${e.message}")
        }
    }

    fun decodeRisk(m: Manifest, graph: StreetGraph, blob: ByteArray): RiskTable {
        val raw = checkBlob("risk", blob, m.risk.bytes, m.risk.sha256, m.risk.rawBytes)
        val r = try { SafeWalkFormat.decodeRisk(raw) } catch (e: IllegalArgumentException) {
            throw DataCheckException("risk: ${e.message}")
        }
        if (r.graphId != m.graph.graphId) throw DataCheckException("risk file made for another graph")
        if (r.edgeCount != graph.edgeCount) throw DataCheckException("risk edge count != graph edge count")
        if (r.weekStart.toString() != m.risk.weekStart) throw DataCheckException("week_start mismatch")
        return r
    }

    /** Full load. Throws if the graph is unusable; returns risk = null if only the risk file is bad. */
    fun load(manifestJson: String, graphBlob: ByteArray, riskBlob: ByteArray?): SafeWalkData {
        val m = SafeWalkFormat.parseManifest(manifestJson)
        checkManifest(m)
        val g = decodeGraph(m, graphBlob)
        val r = riskBlob?.let { runCatching { decodeRisk(m, g, it) }.getOrNull() }
        return SafeWalkData(m, g, r)
    }
}

class AppTooOldException : Exception("Update the app to get new forecasts")
