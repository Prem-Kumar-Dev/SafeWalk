package app.safewalk.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Foreground-only location from the platform LocationManager (no Google Play services needed). */
class LocationSource(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providers(): List<String> {
        val all = lm.getProviders(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in all) {
            return listOf(LocationManager.FUSED_PROVIDER)
        }
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in all }
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission()) return null
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Location fixes while collected. [intervalMs] is shorter in walking mode. */
    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long): Flow<Location> = callbackFlow {
        if (!hasPermission()) { close(); return@callbackFlow }
        var best: Location? = null
        val listener = LocationListener { loc ->
            // ignore a coarse network fix that arrives right after a better GPS one
            val b = best
            if (b == null || loc.time - b.time > 10_000 || loc.accuracy <= b.accuracy * 1.5f) {
                best = loc
                trySend(loc)
            }
        }
        lastKnown()?.let { trySend(it) }
        for (p in providers()) lm.requestLocationUpdates(p, intervalMs, 0f, listener, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(listener) }
    }
}
