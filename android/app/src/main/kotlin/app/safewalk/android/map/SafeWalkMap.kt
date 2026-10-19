package app.safewalk.android.map

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.safewalk.android.BuildConfig
import app.safewalk.android.ui.CameraTarget
import app.safewalk.core.LatLon
import app.safewalk.core.RiskBand
import app.safewalk.core.RouteDetails
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** What the map draws. Only the user's own routes are ever coloured by risk (app-spec 01, wording rules). */
data class MapContent(
    /** Drawn in brand colour, coloured per band. */
    val mainRoute: RouteDetails? = null,
    /** Drawn in grey underneath. */
    val otherRoute: RouteDetails? = null,
    val start: LatLon? = null,
    val end: LatLon? = null,
    val pin: LatLon? = null,
)

private const val SRC_OTHER = "sw-route-other"
private const val SRC_MAIN = "sw-route-main"
private const val SRC_MARKERS = "sw-markers"

@SuppressLint("MissingPermission")
@Composable
fun SafeWalkMap(
    content: MapContent,
    styleUrl: String?,
    showMyLocation: Boolean,
    followMe: Boolean,
    cameraRequest: Int,
    cameraTarget: CameraTarget?,
    bottomPaddingPx: Int,
    onLongPress: (LatLon) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val longPress by rememberUpdatedState(onLongPress)
    val tap by rememberUpdatedState(onTap)

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); mapView.onDestroy() }
    }

    // style.json from the data folder overrides the light style; OpenFreeMap's dark style at night
    val url = if (dark) BuildConfig.DARK_STYLE else (styleUrl ?: BuildConfig.DEFAULT_STYLE)
    LaunchedEffect(url) {
        mapView.getMapAsync { m ->
            val firstTime = map == null
            map = m
            m.uiSettings.apply {
                isCompassEnabled = true
                setCompassGravity(Gravity.TOP or Gravity.END)
                with(density) { setCompassMargins(0, 88.dp.roundToPx(), 16.dp.roundToPx(), 0) }
                isLogoEnabled = false
                isAttributionEnabled = true
                isRotateGesturesEnabled = true
            }
            if (firstTime) {
                m.addOnMapLongClickListener { p -> longPress(LatLon(p.latitude, p.longitude)); true }
                m.addOnMapClickListener { tap(); false }
            }
            style = null
            m.setStyle(Style.Builder().fromUri(url)) { s -> addRouteLayers(s, dark); style = s }
        }
    }

    // routes and markers
    LaunchedEffect(style, content) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_OTHER)?.setGeoJson(lineFeatures(content.otherRoute, single = true))
        s.getSourceAs<GeoJsonSource>(SRC_MAIN)?.setGeoJson(lineFeatures(content.mainRoute, single = false))
        val markers = buildList {
            content.start?.let { add(point(it, "start")) }
            content.end?.let { add(point(it, "end")) }
            content.pin?.let { add(point(it, "pin")) }
        }
        s.getSourceAs<GeoJsonSource>(SRC_MARKERS)?.setGeoJson(FeatureCollection.fromFeatures(markers))
    }

    // blue dot
    LaunchedEffect(style, showMyLocation, followMe) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val lc = m.locationComponent
        if (!showMyLocation) {
            if (lc.isLocationComponentActivated) lc.isLocationComponentEnabled = false
            return@LaunchedEffect
        }
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, s).useDefaultLocationEngine(true).build()
            )
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = if (followMe) RenderMode.GPS else RenderMode.COMPASS
        lc.cameraMode = if (followMe) CameraMode.TRACKING_GPS else CameraMode.NONE
    }

    // one-shot camera moves
    LaunchedEffect(map, cameraRequest) {
        val m = map ?: return@LaunchedEffect
        when (val t = cameraTarget) {
            is CameraTarget.Point -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(t.at.lat, t.at.lon), t.zoom), 600)
            is CameraTarget.Fit -> if (t.points.size >= 2) {
                val b = LatLngBounds.Builder().includes(t.points.map { LatLng(it.lat, it.lon) }).build()
                val pad = with(density) { 48.dp.roundToPx() }
                m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, pad, with(density) { 140.dp.roundToPx() }, pad,
                    bottomPaddingPx + pad), 600)
            }
            null -> Unit
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun addRouteLayers(s: Style, dark: Boolean) {
    s.addSource(GeoJsonSource(SRC_OTHER))
    s.addSource(GeoJsonSource(SRC_MAIN))
    s.addSource(GeoJsonSource(SRC_MARKERS))
    val casing = if (dark) "#0E1215" else "#FFFFFF"
    s.addLayer(LineLayer("sw-other", SRC_OTHER).withProperties(
        PropertyFactory.lineColor(if (dark) "#6B7680" else "#8A949E"),
        PropertyFactory.lineWidth(5f),
        PropertyFactory.lineOpacity(0.9f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ))
    s.addLayer(LineLayer("sw-main-casing", SRC_MAIN).withProperties(
        PropertyFactory.lineColor(casing),
        PropertyFactory.lineWidth(9f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ))
    val bandColor = Expression.match(
        Expression.get("band"), Expression.color(Color.parseColor(if (dark) "#5EA4FF" else "#1D67C9")),
        Expression.stop("ELEVATED", Expression.color(Color.parseColor("#E8A33B"))),
        Expression.stop("HIGH", Expression.color(Color.parseColor("#D9541E"))),
        Expression.stop("VERY_HIGH", Expression.color(Color.parseColor("#D9541E"))),
    )
    val isHigh = Expression.any(Expression.eq(Expression.get("band"), "HIGH"), Expression.eq(Expression.get("band"), "VERY_HIGH"))
    // solid for normal / elevated; higher-risk stretches dashed so colour is not the only signal
    s.addLayer(LineLayer("sw-main", SRC_MAIN).withFilter(Expression.not(isHigh)).withProperties(
        PropertyFactory.lineColor(bandColor),
        PropertyFactory.lineWidth(6f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ))
    s.addLayer(LineLayer("sw-main-high", SRC_MAIN).withFilter(isHigh).withProperties(
        PropertyFactory.lineColor(bandColor),
        PropertyFactory.lineWidth(6f),
        PropertyFactory.lineDasharray(arrayOf(1.4f, 0.9f)),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ))
    s.addLayer(CircleLayer("sw-markers", SRC_MARKERS).withProperties(
        PropertyFactory.circleRadius(Expression.match(Expression.get("kind"), Expression.literal(7f),
            Expression.stop("pin", 9f))),
        PropertyFactory.circleColor(Expression.match(Expression.get("kind"), Expression.color(Color.parseColor("#121820")),
            Expression.stop("start", Expression.color(Color.WHITE)),
            Expression.stop("pin", Expression.color(Color.parseColor("#1D67C9"))))),
        PropertyFactory.circleStrokeColor(Expression.match(Expression.get("kind"), Expression.color(Color.WHITE),
            Expression.stop("start", Expression.color(Color.parseColor("#121820"))))),
        PropertyFactory.circleStrokeWidth(3f),
    ))
}

private fun lineFeatures(r: RouteDetails?, single: Boolean): FeatureCollection {
    if (r == null) return FeatureCollection.fromFeatures(emptyList<Feature>())
    if (single) return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(line(r.polyline))))
    return FeatureCollection.fromFeatures(r.bandRuns.filter { it.second.size >= 2 }.map { (band, pts) ->
        Feature.fromGeometry(line(pts)).apply { addStringProperty("band", if (band == RiskBand.NORMAL) "NORMAL" else band.name) }
    })
}

private fun line(pts: List<LatLon>) = LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })

private fun point(p: LatLon, kind: String) =
    Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("kind", kind) }
