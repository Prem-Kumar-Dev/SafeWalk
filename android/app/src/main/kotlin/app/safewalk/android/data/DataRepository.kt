package app.safewalk.android.data

import android.content.Context
import android.util.Log
import app.safewalk.android.BuildConfig
import app.safewalk.core.AppTooOldException
import app.safewalk.core.DataCheckException
import app.safewalk.core.DataLoader
import app.safewalk.core.Manifest
import app.safewalk.core.SafeWalkData
import app.safewalk.core.SafeWalkFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a forecast check found; Settings shows it after "Check for update". */
enum class UpdateResult { UPDATED, UP_TO_DATE, FAILED, APP_TOO_OLD }

sealed interface DataState {
    data object Loading : DataState
    data class Ready(val data: SafeWalkData, val styleUrl: String?, val updatedAtMs: Long) : DataState
    data class Failed(val message: String) : DataState
}

/**
 * Owns the graph + risk pair the app routes on. Loads the cached pair (or the copy bundled in assets), and
 * checks GitHub Pages for a newer manifest at most every 6 hours, following "How an app updates" in
 * app-spec/02-data-files.md: verify everything, then swap graph and risk in together.
 */
class DataRepository(private val context: Context, private val prefs: Prefs) {
    private val dir = File(context.filesDir, "data")
    private val current = File(dir, "current")
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // a slow connection gives up on the whole call and keeps the saved forecast
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val lock = Mutex()

    private val _state = MutableStateFlow<DataState>(DataState.Loading)
    val state: StateFlow<DataState> = _state.asStateFlow()

    private val _appTooOld = MutableStateFlow(false)
    val appTooOld: StateFlow<Boolean> = _appTooOld.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            val cached = runCatching { loadFolder(current) }.onFailure {
                if (current.exists()) Log.w(TAG, "cached data unusable, using bundled copy", it)
            }.getOrNull()
            val result = cached ?: runCatching { loadAssets() }.getOrElse {
                _state.value = DataState.Failed(it.message ?: "data missing"); return@withLock
            }
            _state.value = result
        }
    }

    /** Checks for a newer forecast. Set [force] from Settings → "Check for update". */
    suspend fun checkForUpdate(force: Boolean = false): UpdateResult = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - prefs.lastCheckMs < CHECK_EVERY_MS) return@withContext UpdateResult.UP_TO_DATE
        lock.withLock {
            for (base in listOf(BuildConfig.DATA_BASE, BuildConfig.DATA_MIRROR)) {
                try {
                    val changed = updateFrom(base)
                    prefs.lastCheckMs = System.currentTimeMillis()
                    return@withLock if (changed) UpdateResult.UPDATED else UpdateResult.UP_TO_DATE
                } catch (e: AppTooOldException) {
                    _appTooOld.value = true
                    prefs.lastCheckMs = System.currentTimeMillis()
                    return@withLock UpdateResult.APP_TOO_OLD
                } catch (e: Exception) {
                    Log.w(TAG, "update from $base failed", e)
                }
            }
            UpdateResult.FAILED
        }
    }

    private fun updateFrom(base: String): Boolean {
        val req = Request.Builder().url(base + "manifest.json").apply {
            prefs.manifestEtag?.let { header("If-None-Match", it) }
        }.build()
        val (text, etag) = http.newCall(req).execute().use { resp ->
            if (resp.code == 304) return false
            if (!resp.isSuccessful) throw IOException("manifest HTTP ${resp.code}")
            resp.body!!.string() to resp.header("ETag")
        }
        val m = SafeWalkFormat.parseManifest(text)
        DataLoader.checkManifest(m)
        val ready = state.value as? DataState.Ready
        val have = ready?.data?.manifest
        if (have != null && have.graph.graphId == m.graph.graphId && have.risk.sha256 == m.risk.sha256 &&
            ready?.data?.risk != null) {
            prefs.manifestEtag = etag
            return false
        }

        // Reuse the graph we already hold when its id has not changed; it never changes content under one id.
        val graphBlob = if (have?.graph?.graphId == m.graph.graphId) graphBlobOf(have) else download(base + m.graph.path)
        val graph = DataLoader.decodeGraph(m, graphBlob)
        val riskBlob = download(base + m.risk.path)
        val risk = DataLoader.decodeRisk(m, graph, riskBlob)       // throws: keep the old pair
        val style = runCatching { download(base + "style.json") }.getOrNull()

        val tmp = File(dir, "incoming").apply { deleteRecursively(); mkdirs() }
        File(tmp, "manifest.json").writeText(text)
        File(tmp, m.graph.path).writeBytes(graphBlob)
        File(tmp, m.risk.path).writeBytes(riskBlob)
        style?.let { File(tmp, "style.json").writeBytes(it) }
        val old = File(dir, "old").apply { deleteRecursively() }
        if (current.exists() && !current.renameTo(old)) throw IOException("cannot move old data")
        if (!tmp.renameTo(current)) { old.renameTo(current); throw IOException("cannot install new data") }
        old.deleteRecursively()
        prefs.manifestEtag = etag

        _state.value = DataState.Ready(SafeWalkData(m, graph, risk), styleUrlOf(style), System.currentTimeMillis())
        return true
    }

    private fun graphBlobOf(m: Manifest): ByteArray {
        val f = File(current, m.graph.path)
        return if (f.exists()) f.readBytes() else context.assets.open("data/" + m.graph.path).use { it.readBytes() }
    }

    private fun download(url: String): ByteArray =
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            resp.body!!.bytes()
        }

    private fun loadFolder(folder: File): DataState.Ready {
        val text = File(folder, "manifest.json").readText()
        val m = SafeWalkFormat.parseManifest(text)
        val graph = File(folder, m.graph.path).readBytes()
        val risk = File(folder, m.risk.path).takeIf { it.exists() }?.readBytes()
        val data = DataLoader.load(text, graph, risk)
        val style = File(folder, "style.json").takeIf { it.exists() }?.readBytes()
        return DataState.Ready(data, styleUrlOf(style), File(folder, "manifest.json").lastModified())
    }

    private fun loadAssets(): DataState.Ready {
        val a = context.assets
        val text = a.open("data/manifest.json").use { String(it.readBytes()) }
        val m = SafeWalkFormat.parseManifest(text)
        val graph = a.open("data/" + m.graph.path).use { it.readBytes() }
        val risk = runCatching { a.open("data/" + m.risk.path).use { it.readBytes() } }.getOrNull()
        val style = runCatching { a.open("data/style.json").use { it.readBytes() } }.getOrNull()
        return try {
            DataState.Ready(DataLoader.load(text, graph, risk), styleUrlOf(style), 0L)
        } catch (e: DataCheckException) {
            throw IllegalStateException("bundled data is broken: ${e.message}", e)
        }
    }

    private fun styleUrlOf(bytes: ByteArray?): String? = bytes?.let {
        runCatching {
            SafeWalkFormat.json.parseToJsonElement(String(it)).jsonObject["map_style_url"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    companion object {
        private const val TAG = "SafeWalkData"
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    }
}
