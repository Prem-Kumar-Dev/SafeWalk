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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { vm.openSearch(Field.END) }, shape = MaterialTheme.shapes.medium, shadowElevation = 6.dp,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = c.accent)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(
                    onClick = { vm.open(Screen.SETTINGS) }, shape = MaterialTheme.shapes.medium, shadowElevation = 6.dp,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.settings))
                    }
                }
            }
            Notices(s)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.End) {
            val pin = s.droppedPin
            val showRecent = pin == null && s.recent.isNotEmpty()
            Surface(
                onClick = { if (s.hasLocationPermission) vm.centreOnMe() else requestLocation() },
                shape = CircleShape, shadowElevation = 6.dp, contentColor = c.accent,
                modifier = Modifier.padding(16.dp).then(if (pin == null && !showRecent) Modifier.navigationBarsPadding() else Modifier)
                    .size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.centre_on_me))
                }
            }
            if (pin != null) {
                BottomSheetCard {
                    val inside = vm.inCoverage(pin)
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            SheetTitle(stringResource(R.string.dropped_pin), if (inside) null else stringResource(R.string.outside_coverage))
                        }
                        Surface(onClick = vm::clearPin, shape = CircleShape, color = c.sand, modifier = Modifier.size(32.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton(stringResource(R.string.walk_here), vm::walkHere, Modifier.weight(1f))
                        QuietButton(stringResource(R.string.start_here), vm::startHere, Modifier.weight(1f))
                    }
                }
            } else if (showRecent) {
                BottomSheetCard {
                    Text(stringResource(R.string.recent), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    for (p in s.recent.take(3)) {
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { vm.walkTo(p) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(36.dp).background(c.sand, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.History, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Slim notices under the search bar: outside Chicago, stale forecast, app too old, map offline. */
@Composable
fun Notices(s: UiState) {
    val ready = s.data as? DataState.Ready
    val notes = buildList {
        if (s.appTooOld) add(stringResource(R.string.update_app))
        if (s.basemapOffline) add(stringResource(R.string.map_offline))
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
