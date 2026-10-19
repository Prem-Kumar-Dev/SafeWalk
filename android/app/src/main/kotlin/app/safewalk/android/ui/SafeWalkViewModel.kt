package app.safewalk.android.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.safewalk.android.R
import app.safewalk.android.SafeWalkApp
import app.safewalk.android.data.DataState
import app.safewalk.android.data.Place
import app.safewalk.android.data.Units
import app.safewalk.core.LatLon
import app.safewalk.core.RouteDetails
import app.safewalk.core.RouteProgress
import app.safewalk.core.SafetyLevel
import app.safewalk.core.TimeSlot
import app.safewalk.core.TripPlanner
import app.safewalk.core.TripResult
import app.safewalk.core.WalkTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

enum class Screen { WELCOME, MAP, SEARCH, PREVIEW, WALKING, SETTINGS, ABOUT, PRIVACY }
enum class Field { START, END }
enum class RouteChoice { LOWER_RISK, DIRECT }

/** A trip endpoint. [mine] = follow the current GPS position. */
data class Endpoint(val place: Place, val mine: Boolean = false)

data class WalkState(
    val route: RouteDetails,
    val fix: RouteProgress.Fix? = null,
    val offRoutePrompt: Boolean = false,
    val arrived: Boolean = false,
)

data class UiState(
    val screen: Screen = Screen.MAP,
    val data: DataState = DataState.Loading,
    val appTooOld: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val myLocation: LatLon? = null,
    val myBearing: Float? = null,
    val droppedPin: LatLon? = null,
    val start: Endpoint? = null,
    val end: Endpoint? = null,
    val searchFor: Field = Field.END,
    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val recent: List<Place> = emptyList(),
    /** null = leave now */
    val departure: ZonedDateTime? = null,
    val level: SafetyLevel = SafetyLevel.BALANCED,
    val trip: TripResult? = null,
    val computing: Boolean = false,
    val choice: RouteChoice = RouteChoice.LOWER_RISK,
    val walk: WalkState? = null,
    val units: Units = Units.KM,
    val checkingUpdate: Boolean = false,
    /** bumped to ask the map to move; the map reads [cameraTarget] when it changes */
    val cameraRequest: Int = 0,
    val cameraTarget: CameraTarget? = null,
)

sealed interface CameraTarget {
    data class Point(val at: LatLon, val zoom: Double) : CameraTarget
    data class Fit(val points: List<LatLon>) : CameraTarget
}

@OptIn(FlowPreview::class)
class SafeWalkViewModel(app: Application) : AndroidViewModel(app) {
    private val deps = (app as SafeWalkApp)
    private val prefs = deps.prefs
    private val repo = deps.dataRepository
    private val locations = deps.locationSource
    private val search = deps.placeSearch

    private val _ui = MutableStateFlow(
        UiState(
            screen = if (prefs.onboarded) Screen.MAP else Screen.WELCOME,
            level = prefs.level,
            units = prefs.units,
            recent = prefs.recentPlaces,
            hasLocationPermission = locations.hasPermission(),
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var locationJob: Job? = null
    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var offRouteSince = 0L
    private var progress: RouteProgress? = null

    init {
        viewModelScope.launch { repo.state.collect { s -> _ui.update { it.copy(data = s) }; if (s is DataState.Ready) replan() } }
        viewModelScope.launch { repo.appTooOld.collect { v -> _ui.update { it.copy(appTooOld = v) } } }
        viewModelScope.launch { repo.load(); repo.checkForUpdate() }
        if (locations.hasPermission()) startLocation(walking = false)
        moveCamera(CameraTarget.Point(LOOP, 13.0))
    }

    private val data get() = (_ui.value.data as? DataState.Ready)?.data

    // ---------------------------------------------------------------- app lifecycle
    fun onForeground() { viewModelScope.launch { repo.checkForUpdate() } }

    fun onPermissionResult(granted: Boolean) {
        _ui.update { it.copy(hasLocationPermission = granted) }
        if (granted) startLocation(walking = _ui.value.screen == Screen.WALKING)
    }

    fun finishWelcome() {
        prefs.onboarded = true
        _ui.update { it.copy(screen = Screen.MAP) }
    }

    private fun startLocation(walking: Boolean) {
        locationJob?.cancel()
        var first = true
        locationJob = viewModelScope.launch {
            locations.updates(if (walking) 1_000L else 5_000L).collect { loc ->
                onLocation(loc)
                if (first) {
                    first = false
                    val here = LatLon(loc.latitude, loc.longitude)
                    if (_ui.value.screen == Screen.MAP && inCoverage(here)) moveCamera(CameraTarget.Point(here, 15.0))
                }
            }
        }
    }

    private fun onLocation(loc: Location) {
        val here = LatLon(loc.latitude, loc.longitude)
        _ui.update { it.copy(myLocation = here, myBearing = if (loc.hasBearing()) loc.bearing else null) }
        val w = _ui.value.walk ?: return
        val p = progress ?: return
        val fix = p.locate(here)
        val now = System.currentTimeMillis()
        if (fix.offRouteM > WalkTracker.OFF_ROUTE_M) {
            if (offRouteSince == 0L) offRouteSince = now
        } else offRouteSince = 0L
        val prompt = w.offRoutePrompt || (offRouteSince != 0L && now - offRouteSince >= WalkTracker.OFF_ROUTE_SECONDS * 1000)
        _ui.update { it.copy(walk = w.copy(fix = fix, offRoutePrompt = prompt && !fix.arrived, arrived = fix.arrived)) }
    }

    val searchAvailable: Boolean get() = search.available

    fun inCoverage(p: LatLon): Boolean = data?.snapper?.inBounds(p) ?: true

    // ---------------------------------------------------------------- navigation
    fun open(screen: Screen) = _ui.update { it.copy(screen = screen) }

    /** Returns false when the system should handle back (leave the app). */
    fun back(): Boolean {
        val s = _ui.value
        val to = when (s.screen) {
            Screen.WELCOME, Screen.MAP -> return if (s.droppedPin != null) { _ui.update { it.copy(droppedPin = null) }; true } else false
            Screen.SEARCH -> if (s.start != null && s.end != null) Screen.PREVIEW else Screen.MAP
            Screen.PREVIEW -> { _ui.update { it.copy(trip = null, start = null, end = null) }; Screen.MAP }
            Screen.WALKING -> { endWalk(); return true }
            Screen.SETTINGS -> Screen.MAP
            Screen.ABOUT, Screen.PRIVACY -> Screen.SETTINGS
        }
        _ui.update { it.copy(screen = to) }
        return true
    }

    // ---------------------------------------------------------------- map
    fun moveCamera(t: CameraTarget) = _ui.update { it.copy(cameraTarget = t, cameraRequest = it.cameraRequest + 1) }

    fun centreOnMe() { _ui.value.myLocation?.let { moveCamera(CameraTarget.Point(it, 16.0)) } }

    fun longPress(at: LatLon) = _ui.update { it.copy(droppedPin = at) }
    fun clearPin() = _ui.update { it.copy(droppedPin = null) }

    fun walkHere() {
        val pin = _ui.value.droppedPin ?: return
        setEnd(Endpoint(Place(pinLabel(pin), pin.lat, pin.lon)))
    }

    fun startHere() {
        val pin = _ui.value.droppedPin ?: return
        _ui.update { it.copy(start = Endpoint(Place(pinLabel(pin), pin.lat, pin.lon)), droppedPin = null) }
        openSearch(Field.END)
    }

    private fun pinLabel(p: LatLon) = str(R.string.dropped_pin)
    private fun str(id: Int) = getApplication<Application>().getString(id)

    // ---------------------------------------------------------------- search
    fun openSearch(field: Field) =
        _ui.update { it.copy(screen = Screen.SEARCH, searchFor = field, query = "", results = emptyList()) }

    fun onQuery(q: String) {
        _ui.update { it.copy(query = q) }
        searchJob?.cancel()
        val bbox = data?.manifest?.bbox ?: return
        if (q.trim().length < 3) { _ui.update { it.copy(results = emptyList(), searching = false) }; return }
        searchJob = viewModelScope.launch {
            delay(350)
            _ui.update { it.copy(searching = true) }
            val r = runCatching { search.search(q.trim(), bbox) }.getOrDefault(emptyList())
            _ui.update { it.copy(results = r, searching = false) }
        }
    }

    fun choosePlace(p: Place) {
        prefs.addRecent(p)
        _ui.update { it.copy(recent = prefs.recentPlaces) }
        if (_ui.value.searchFor == Field.START) {
            _ui.update { it.copy(start = Endpoint(p)) }
            if (_ui.value.end == null) openSearch(Field.END) else showPreview()
        } else setEnd(Endpoint(p))
    }

    fun chooseMyLocation() {
        val here = _ui.value.myLocation ?: return
        val e = Endpoint(Place(str(R.string.your_location), here.lat, here.lon), mine = true)
        if (_ui.value.searchFor == Field.START) {
            _ui.update { it.copy(start = e) }
            if (_ui.value.end == null) openSearch(Field.END) else showPreview()
        } else setEnd(e)
    }

    private fun setEnd(e: Endpoint) {
        val s = _ui.value
        val start = s.start ?: s.myLocation?.let { Endpoint(Place(str(R.string.your_location), it.lat, it.lon), mine = true) }
        _ui.update { it.copy(end = e, start = start, droppedPin = null) }
        if (start == null) openSearch(Field.START) else showPreview()
    }

    fun clearRecent() {
        prefs.recentPlaces = emptyList()
        _ui.update { it.copy(recent = emptyList()) }
    }

    // ---------------------------------------------------------------- route preview
    private fun showPreview() {
        _ui.update { it.copy(screen = Screen.PREVIEW, choice = RouteChoice.LOWER_RISK) }
        replan(fit = true)
    }

    fun swap() {
        _ui.update { it.copy(start = it.end, end = it.start) }
        replan()
    }

    fun setLevel(l: SafetyLevel) {
        prefs.level = l
        _ui.update { it.copy(level = l) }
        replan()
    }

    fun setDeparture(t: ZonedDateTime?) {
        _ui.update { it.copy(departure = t) }
        replan()
    }

    fun choose(c: RouteChoice) = _ui.update { it.copy(choice = c) }

    fun slotFor(s: UiState = _ui.value): TimeSlot? {
        val d = data ?: return null
        return TimeSlot.at(s.departure ?: ZonedDateTime.now(d.zone), d.zone)
    }

    private fun replan(fit: Boolean = false) {
        val s = _ui.value
        val d = data ?: return
        val a = s.start?.place ?: return
        val b = s.end?.place ?: return
        if (s.screen != Screen.PREVIEW) return
        val slot = slotFor(s) ?: return
        routeJob?.cancel()
        _ui.update { it.copy(computing = true) }
        routeJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                TripPlanner(d).plan(LatLon(a.lat, a.lon), LatLon(b.lat, b.lon), slot, s.level)
            }
            _ui.update { it.copy(trip = result, computing = false,
                choice = if (result is TripResult.Routes && result.safer != null) it.choice else RouteChoice.DIRECT) }
            if (fit && result is TripResult.Routes) {
                moveCamera(CameraTarget.Fit((result.safer?.polyline ?: emptyList()) + result.direct.polyline))
            } else if (fit) moveCamera(CameraTarget.Fit(listOf(LatLon(a.lat, a.lon), LatLon(b.lat, b.lon))))
        }
    }

    // ---------------------------------------------------------------- walking
    fun startWalking() {
        val t = _ui.value.trip as? TripResult.Routes ?: return
        val r = if (_ui.value.choice == RouteChoice.LOWER_RISK && t.safer != null) t.safer!! else t.direct
        progress = RouteProgress(r)
        offRouteSince = 0L
        _ui.update { it.copy(screen = Screen.WALKING, walk = WalkState(r)) }
        if (locations.hasPermission()) startLocation(walking = true)
        _ui.value.myLocation?.let { moveCamera(CameraTarget.Point(it, 17.0)) }
    }

    fun dismissOffRoute() {
        offRouteSince = 0L
        _ui.update { s -> s.copy(walk = s.walk?.copy(offRoutePrompt = false)) }
    }

    /** "Find a new route from here": same level, slot for the current time, start = current position. */
    fun rerouteFromHere() {
        val here = _ui.value.myLocation ?: return
        val end = _ui.value.end ?: return
        val d = data ?: return
        val level = _ui.value.level
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                TripPlanner(d).plan(here, LatLon(end.place.lat, end.place.lon), TimeSlot.now(d.zone), level)
            }
            if (r is TripResult.Routes) {
                val route = if (level != SafetyLevel.DIRECT && r.safer != null) r.safer!! else r.direct
                progress = RouteProgress(route)
                offRouteSince = 0L
                _ui.update { it.copy(walk = WalkState(route), trip = r,
                    start = Endpoint(Place(str(R.string.your_location), here.lat, here.lon), mine = true)) }
            } else dismissOffRoute()
        }
    }

    fun endWalk() {
        progress = null
        val arrived = _ui.value.walk?.arrived == true
        _ui.update {
            if (arrived) it.copy(screen = Screen.MAP, walk = null, trip = null, start = null, end = null)
            else it.copy(screen = Screen.PREVIEW, walk = null)
        }
        if (locations.hasPermission()) startLocation(walking = false)
    }

    // ---------------------------------------------------------------- settings
    fun setUnits(u: Units) { prefs.units = u; _ui.update { it.copy(units = u) } }

    fun checkForUpdateNow() {
        _ui.update { it.copy(checkingUpdate = true) }
        viewModelScope.launch {
            runCatching { repo.checkForUpdate(force = true) }
            _ui.update { it.copy(checkingUpdate = false) }
        }
    }

    companion object {
        val LOOP = LatLon(41.8819, -87.6278)
    }
}
