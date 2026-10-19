package app.safewalk.android.search

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import app.safewalk.android.data.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** Place search with the phone's built-in Geocoder, limited to the data's bounding box (app-spec 01, screen 3). */
class PlaceSearch(private val context: Context) {
    val available: Boolean get() = Geocoder.isPresent()

    /** bbox = [minLat, minLon, maxLat, maxLon] */
    suspend fun search(query: String, bbox: List<Double>, max: Int = 8): List<Place> {
        if (query.isBlank() || !available) return emptyList()
        val g = Geocoder(context, Locale.getDefault())
        val results: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                g.getFromLocationName(query, max, bbox[0], bbox[1], bbox[2], bbox[3], object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses) }
                    override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(emptyList()) }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { g.getFromLocationName(query, max, bbox[0], bbox[1], bbox[2], bbox[3]) }.getOrNull()
                    ?: emptyList()
            }
        }
        return results
            .filter { it.hasLatitude() && it.hasLongitude() }
            .filter { it.latitude in bbox[0]..bbox[2] && it.longitude in bbox[1]..bbox[3] }
            .map { Place(label(it), it.latitude, it.longitude) }
            .distinctBy { it.name }
    }

    private fun label(a: Address): String {
        val first = a.featureName?.takeIf { it.isNotBlank() && it != a.subThoroughfare }
        val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").takeIf { it.isNotBlank() }
        return listOfNotNull(first, street, a.locality).distinct().joinToString(", ").ifBlank {
            a.getAddressLine(0) ?: "%.5f, %.5f".format(a.latitude, a.longitude)
        }
    }
}
