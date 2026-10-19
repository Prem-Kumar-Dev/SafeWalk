package app.safewalk.android.data

import android.content.Context
import app.safewalk.core.SafetyLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
data class Place(val name: String, val lat: Double, val lon: Double)

enum class Units { KM, MILES }

/** Everything the app remembers. On-device only; nothing here is ever uploaded. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("safewalk", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var level: SafetyLevel
        get() = runCatching { SafetyLevel.valueOf(sp.getString("level", null)!!) }.getOrDefault(SafetyLevel.BALANCED)
        set(v) = sp.edit().putString("level", v.name).apply()

    var units: Units
        get() = sp.getString("units", null)?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: defaultUnits()
        set(v) = sp.edit().putString("units", v.name).apply()

    var lastCheckMs: Long
        get() = sp.getLong("last_check", 0L)
        set(v) = sp.edit().putLong("last_check", v).apply()

    var manifestEtag: String?
        get() = sp.getString("manifest_etag", null)
        set(v) = sp.edit().putString("manifest_etag", v).apply()

    var recentPlaces: List<Place>
        get() = sp.getString("recent", null)?.let {
            runCatching { json.decodeFromString(ListSerializer(Place.serializer()), it) }.getOrNull()
        } ?: emptyList()
        set(v) = sp.edit().putString("recent", json.encodeToString(ListSerializer(Place.serializer()), v.take(20))).apply()

    fun addRecent(p: Place) {
        recentPlaces = listOf(p) + recentPlaces.filterNot { it.name == p.name }
    }

    private fun defaultUnits(): Units =
        if (Locale.getDefault().country in setOf("US", "GB", "LR", "MM")) Units.MILES else Units.KM
}
