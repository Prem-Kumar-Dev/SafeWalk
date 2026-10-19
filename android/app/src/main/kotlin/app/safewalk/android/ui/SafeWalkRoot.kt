package app.safewalk.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.safewalk.android.R
import app.safewalk.android.data.DataState
import app.safewalk.android.map.MapContent
import app.safewalk.android.map.SafeWalkMap
import app.safewalk.core.LatLon
import app.safewalk.core.TripResult

@Composable
fun SafeWalkRoot(vm: SafeWalkViewModel, requestLocation: () -> Unit, exit: () -> Unit) {
    val s by vm.ui.collectAsStateWithLifecycle()
    BackHandler(enabled = s.screen != Screen.WELCOME) { if (!vm.back()) exit() }

    if (s.screen == Screen.WELCOME) {
        WelcomeScreen(onDone = { askLocation -> vm.finishWelcome(); if (askLocation) requestLocation() })
        return
    }

    var sheetHeight by remember { mutableIntStateOf(0) }
    val ready = s.data as? DataState.Ready
    Box(Modifier.fillMaxSize()) {
        SafeWalkMap(
            content = mapContent(s),
            styleUrl = ready?.styleUrl,
            showMyLocation = s.hasLocationPermission,
            followMe = s.screen == Screen.WALKING,
            cameraRequest = s.cameraRequest,
            cameraTarget = s.cameraTarget,
            bottomPaddingPx = sheetHeight,
            onLongPress = { if (s.screen == Screen.MAP) vm.longPress(it) },
            onTap = { if (s.screen == Screen.MAP) vm.clearPin() },
            modifier = Modifier.fillMaxSize(),
        )
        Attribution(Modifier.align(Alignment.BottomStart))

        when (s.screen) {
            Screen.MAP -> MapHome(s, vm, requestLocation)
            Screen.PREVIEW -> RoutePreview(s, vm, Modifier.align(Alignment.BottomCenter).onSizeChanged { sheetHeight = it.height })
            Screen.WALKING -> WalkingOverlay(s, vm)
            else -> Unit
        }

        when (s.data) {
            is DataState.Loading -> Status(stringResource(R.string.loading_data), progress = true)
            is DataState.Failed -> Status(stringResource(R.string.data_failed, (s.data as DataState.Failed).message))
            else -> Unit
        }
    }

    when (s.screen) {
        Screen.SEARCH -> SearchScreen(s, vm)
        Screen.SETTINGS -> SettingsScreen(s, vm)
        Screen.ABOUT -> AboutScreen(s, vm)
        Screen.PRIVACY -> PrivacyScreen(vm)
        else -> Unit
    }
}

private fun mapContent(s: UiState): MapContent {
    val start = s.start?.place?.let { LatLon(it.lat, it.lon) }
    val end = s.end?.place?.let { LatLon(it.lat, it.lon) }
    return when (s.screen) {
        Screen.PREVIEW -> {
            val t = s.trip as? TripResult.Routes
            val lower = s.choice == RouteChoice.LOWER_RISK && t?.safer != null
            MapContent(
                mainRoute = if (lower) t?.safer else t?.direct,
                otherRoute = if (lower) t?.direct else t?.safer,
                start = start, end = end,
            )
        }
        Screen.WALKING -> MapContent(mainRoute = s.walk?.route, end = end)
        Screen.MAP -> MapContent(pin = s.droppedPin)
        else -> MapContent()
    }
}

@Composable
private fun Attribution(modifier: Modifier) {
    Text(
        stringResource(R.string.map_attribution),
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = 4.dp, bottom = 2.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun Status(text: String, progress: Boolean = false) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp, shadowElevation = 4.dp) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (progress) CircularProgressIndicator(Modifier.padding(bottom = 12.dp))
                Text(text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.7f))
            }
        }
    }
}
