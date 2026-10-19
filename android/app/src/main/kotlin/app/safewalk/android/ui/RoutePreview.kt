package app.safewalk.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.safewalk.android.R
import app.safewalk.android.data.DataState
import app.safewalk.core.RouteDetails
import app.safewalk.core.SafetyLevel
import app.safewalk.core.TripResult
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

@Composable
fun RoutePreview(s: UiState, vm: SafeWalkViewModel, sheetModifier: Modifier) {
    // top: endpoints
    Surface(
        tonalElevation = 3.dp, shadowElevation = 6.dp, shape = MaterialTheme.shapes.large,
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(12.dp).fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Column(Modifier.weight(1f)) {
                EndpointField(stringResource(R.string.from), s.start?.place?.name) { vm.openSearch(Field.START) }
                EndpointField(stringResource(R.string.to), s.end?.place?.name) { vm.openSearch(Field.END) }
            }
            IconButton(onClick = vm::swap) { Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.swap)) }
        }
    }

    // bottom sheet
    Surface(
        tonalElevation = 2.dp, shadowElevation = 12.dp,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0)),
        modifier = sheetModifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LeavingRow(s, vm)
            LevelPicker(s.level, vm::setLevel)
            if (s.computing) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (val t = s.trip) {
                is TripResult.Routes -> RouteCards(s, t, vm)
                TripResult.OutsideArea -> Message(stringResource(R.string.outside_coverage))
                TripResult.TooFarFromStreet -> Message(stringResource(R.string.too_far_from_street))
                TripResult.AlreadyThere -> Message(stringResource(R.string.already_there))
                TripResult.NoRoute -> Message(stringResource(R.string.no_route))
                null -> if (!s.computing) Message(stringResource(R.string.computing))
            }
        }
    }
}

@Composable
private fun EndpointField(label: String, value: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
        Text(value ?: "…", maxLines = 1, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeavingRow(s: UiState, vm: SafeWalkViewModel) {
    var picking by remember { mutableStateOf(false) }
    val zone = (s.data as? DataState.Ready)?.data?.zone
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.leaving), style = MaterialTheme.typography.labelLarge)
        AssistChip(onClick = { vm.setDeparture(null) }, label = { Text(stringResource(R.string.leaving_now)) },
            border = if (s.departure == null) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null)
        val label = s.departure?.let { it.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) }
            ?: stringResource(R.string.pick_time)
        AssistChip(onClick = { picking = true }, label = { Text(label) },
            border = if (s.departure != null) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null)
    }
    if (picking && zone != null) {
        val now = ZonedDateTime.now(zone)
        val state = rememberTimePickerState(now.hour, now.minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    var t = now.withHour(state.hour).withMinute(state.minute).withSecond(0).withNano(0)
                    if (t.isBefore(now.minusMinutes(1))) t = t.plusDays(1)     // a past time means tomorrow
                    vm.setDeparture(t); picking = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(stringResource(R.string.pick_time)) },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
fun LevelPicker(level: SafetyLevel, onPick: (SafetyLevel) -> Unit) {
    Column {
        Text(stringResource(R.string.route_preference), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        val levels = SafetyLevel.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            levels.forEachIndexed { i, l ->
                SegmentedButton(
                    selected = l == level,
                    onClick = { onPick(l) },
                    shape = SegmentedButtonDefaults.itemShape(i, levels.size),
                ) { Text(levelLabel(l), maxLines = 1) }
            }
        }
    }
}

@Composable
fun levelLabel(l: SafetyLevel) = stringResource(
    when (l) {
        SafetyLevel.DIRECT -> R.string.level_direct
        SafetyLevel.BALANCED -> R.string.level_balanced
        SafetyLevel.LOWEST_RISK -> R.string.level_lowest_risk
    }
)

@Composable
private fun RouteCards(s: UiState, t: TripResult.Routes, vm: SafeWalkViewModel) {
    val safer = t.safer
    if (safer != null) {
        RouteCard(
            title = stringResource(R.string.route_lower_risk_title), r = s, d = safer,
            lines = listOf(
                stringResource(R.string.route_risk_cut, t.riskReductionPct) + " · " +
                    stringResource(R.string.route_extra_time, t.extraMinutes),
                stretches(safer),
            ),
            emphasiseExtra = safer.lengthM > t.direct.lengthM * 1.25,
            selected = s.choice == RouteChoice.LOWER_RISK,
        ) { vm.choose(RouteChoice.LOWER_RISK) }
    }
    RouteCard(
        title = stringResource(R.string.route_direct_title), r = s, d = t.direct,
        lines = listOf(stretches(t.direct)),
        emphasiseExtra = false,
        selected = safer == null || s.choice == RouteChoice.DIRECT,
    ) { vm.choose(RouteChoice.DIRECT) }
    if (t.directIsLowerRisk) Message(stringResource(R.string.route_direct_is_best))
    if (!t.forecastAvailable) Message(stringResource(R.string.no_forecast))
    if (t.longWalk) Message(stringResource(R.string.long_walk))

    Column {
        Text(stringResource(R.string.route_fine_print), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val ready = s.data as? DataState.Ready
        val week = ready?.data?.risk?.weekStart
        if (ready != null && week != null && ChronoUnit.DAYS.between(week, LocalDate.now(ready.data.zone)) >= 14) {
            Text(stringResource(R.string.forecast_age, formatWeek(week)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Button(onClick = vm::startWalking, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.start_walking))
    }
}

@Composable
private fun stretches(d: RouteDetails) =
    if (d.higherRiskStretches == 0) stringResource(R.string.route_none_stretches)
    else pluralStringResource(R.plurals.route_stretches, d.higherRiskStretches, d.higherRiskStretches)

@Composable
private fun RouteCard(
    title: String, r: UiState, d: RouteDetails, lines: List<String>, emphasiseExtra: Boolean,
    selected: Boolean, onClick: () -> Unit,
) {
    val head = stringResource(R.string.route_summary, formatDistance(d.lengthM, r.units), formatMinutes(d.walkingMinutes))
    val all = "$title · $head. " + lines.joinToString(". ")
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = all; this.selected = selected },
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("$title · $head", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            lines.forEachIndexed { i, l ->
                Text(l, style = if (i == 0 && emphasiseExtra) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
