package app.safewalk.android

import android.app.Application
import app.safewalk.android.data.DataRepository
import app.safewalk.android.data.Prefs
import app.safewalk.android.location.LocationSource
import app.safewalk.android.search.PlaceSearch
import org.maplibre.android.MapLibre

/** Holds the app-wide singletons (small enough that a DI framework isn't worth it). */
class SafeWalkApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var dataRepository: DataRepository; private set
    lateinit var locationSource: LocationSource; private set
    lateinit var placeSearch: PlaceSearch; private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        prefs = Prefs(this)
        dataRepository = DataRepository(this, prefs)
        locationSource = LocationSource(this)
        placeSearch = PlaceSearch(this)
    }
}
