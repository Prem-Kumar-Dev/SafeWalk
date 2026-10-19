package app.safewalk.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.safewalk.android.R
import app.safewalk.android.data.DataState
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun MapHome(s: UiState, vm: SafeWalkViewModel, requestLocation: () -> Unit) {
    val c = SafeWalkTheme.colors
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(shape = CircleShape, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).clickable(role = Role.Button) { vm.openSearch(Field.END) }
                            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(40.dp).background(c.blush, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = c.accent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.open(Screen.SETTINGS) }, modifier = Modifier.padding(end = 4.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            }
            Notices(s)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.End) {
            FloatingActionButton(
                onClick = { if (s.hasLocationPermission) vm.centreOnMe() else requestLocation() },
                containerColor = MaterialTheme.colorScheme.surface, contentColor = c.accent,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(16.dp).then(if (s.droppedPin == null) Modifier.navigationBarsPadding() else Modifier),
            ) { Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.centre_on_me)) }
            val pin = s.droppedPin
            if (pin != null) {
                BottomSheetCard {
                    val inside = vm.inCoverage(pin)
                    SheetTitle(stringResource(R.string.dropped_pin), if (inside) null else stringResource(R.string.outside_coverage))
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton(stringResource(R.string.walk_here), vm::walkHere, Modifier.weight(1f))
                        QuietButton(stringResource(R.string.start_here), vm::startHere, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Slim notices under the search bar: outside Chicago, stale forecast, app too old. */
@Composable
fun Notices(s: UiState) {
    val ready = s.data as? DataState.Ready
    val notes = buildList {
        if (s.appTooOld) add(stringResource(R.string.update_app))
        val here = s.myLocation
        if (here != null && ready != null && !ready.data.snapper.inBounds(here)) add(stringResource(R.string.outside_coverage))
        val week = ready?.data?.risk?.weekStart
        if (ready != null && week != null && ChronoUnit.DAYS.between(week, LocalDate.now(ready.data.zone)) >= 56) {
            add(stringResource(R.string.forecast_stale))
        }
    }
    for (n in notes) {
        Surface(shape = MaterialTheme.shapes.medium, color = SafeWalkTheme.colors.blush,
            contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) {
            Text(n, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}
