package app.safewalk.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
        shadowElevation = 8.dp, shape = MaterialTheme.shapes.large,
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                EndpointField(stringResource(R.string.from), s.start?.place?.name, end = false) { vm.openSearch(Field.START) }
                Spacer(Modifier.height(8.dp))
                EndpointField(stringResource(R.string.to), s.end?.place?.name, end = true) { vm.openSearch(Field.END) }
            }
            Surface(onClick = vm::swap, shape = CircleShape, color = SafeWalkTheme.colors.blush,
                contentColor = SafeWalkTheme.colors.accent, modifier = Modifier.padding(horizontal = 8.dp).size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.swap), modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    // bottom sheet
    BottomSheetCard(sheetModifier) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LeavingRow(s, vm)
            LevelPicker(s.level, vm::setLevel)
            if (s.computing) LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = SafeWalkTheme.colors.blush)
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
private fun EndpointField(label: String, value: String?, end: Boolean, onClick: () -> Unit) {
    val coral = MaterialTheme.colorScheme.primary
    Surface(onClick = onClick, color = SafeWalkTheme.colors.sand, shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).background(if (end) MaterialTheme.colorScheme.onSurface else coral, if (end) RoundedCornerShape(2.dp) else CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Text(value ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LeavingRow(s: UiState, vm: SafeWalkViewModel) {
    var picking by remember { mutableStateOf(false) }
    val zone = (s.data as? DataState.Ready)?.data?.zone
    // wraps onto a second line at large font sizes instead of squashing the chips
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.leaving), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
        PillChip(stringResource(R.string.leaving_now), selected = s.departure == null) { vm.setDeparture(null) }
        val label = s.departure?.let { it.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) }
            ?: stringResource(R.string.pick_time)
        PillChip(label, selected = s.departure != null) { picking = true }
        Text(stringResource(R.string.route_preference), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
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

/** Sand track with the chosen level on a raised white segment. */
@Composable
fun LevelPicker(level: SafetyLevel, onPick: (SafetyLevel) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SafeWalkTheme.colors.sand, MaterialTheme.shapes.small).padding(3.dp)
            .selectableGroup(),
    ) {
        for (l in SafetyLevel.entries) {
            val on = l == level
            Surface(
                selected = on, onClick = { onPick(l) }, shape = RoundedCornerShape(10.dp),
                color = if (on) MaterialTheme.colorScheme.surface else Color.Transparent,
                shadowElevation = if (on) 2.dp else 0.dp,
                modifier = Modifier.weight(1f).heightIn(min = 38.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                    Text(levelLabel(l), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
    Explanation(t.explanation.text)
    if (safer != null) {
        RouteCard(
            title = stringResource(R.string.route_lower_risk_title), r = s, d = safer, lowerRisk = true,
            meta = stringResource(R.string.route_extra_time, t.extraMinutes),
            tag = stringResource(R.string.route_risk_cut, t.riskReductionPct),
            emphasiseExtra = safer.lengthM > t.direct.lengthM * 1.25,
            selected = s.choice == RouteChoice.LOWER_RISK,
        ) { vm.choose(RouteChoice.LOWER_RISK) }
    }
    RouteCard(
        title = stringResource(R.string.route_direct_title), r = s, d = t.direct, lowerRisk = false,
        meta = stringResource(R.string.route_fastest),
        tag = stringResource(R.string.route_shortest),
        emphasiseExtra = false,
        selected = safer == null || s.choice == RouteChoice.DIRECT,
    ) { vm.choose(RouteChoice.DIRECT) }
    if (!t.forecastAvailable) Message(stringResource(R.string.no_forecast))
    if (t.longWalk) Message(stringResource(R.string.long_walk))

    Row {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 1.dp).size(14.dp))
        Spacer(Modifier.width(6.dp))
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
    }
    PrimaryButton(stringResource(R.string.start_walking), vm::startWalking, Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null)
    }
}

@Composable
private fun stretches(d: RouteDetails) =
    if (d.higherRiskStretches == 0) stringResource(R.string.route_none_stretches)
    else pluralStringResource(R.plurals.route_stretches, d.higherRiskStretches, d.higherRiskStretches)

/** Route option: accent bar on the left (coral for lower-risk, grey for direct), minutes top right, a tag pill. */
@Composable
private fun RouteCard(
    title: String, r: UiState, d: RouteDetails, lowerRisk: Boolean, meta: String, tag: String,
    emphasiseExtra: Boolean, selected: Boolean, onClick: () -> Unit,
) {
    val c = SafeWalkTheme.colors
    val coral = MaterialTheme.colorScheme.primary
    val bar = if (lowerRisk) coral else c.routeOther
    val metaLine = stringResource(R.string.route_meta, formatDistance(d.lengthM, r.units), meta)
    val stretchLine = stretches(d)
    val all = "$title · ${formatMinutes(d.walkingMinutes)}. $metaLine. $tag. $stretchLine"
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) c.blushSoft else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) coral else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = all; this.selected = selected },
    ) {
        Row(Modifier.height(IntrinsicSize.Min).padding(14.dp)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(bar, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(formatMinutes(d.walkingMinutes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (lowerRisk) c.accent else MaterialTheme.colorScheme.onSurface)
                }
                Text(metaLine, style = if (emphasiseExtra) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = CircleShape, color = if (lowerRisk) c.blush else c.sand,
                    contentColor = if (lowerRisk) c.accent else MaterialTheme.colorScheme.onSurfaceVariant) {
                    Text(tag, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.higherRiskStretches > 0) {
                        Box(Modifier.size(8.dp).background(c.amber, CircleShape))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stretchLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Explanation(text: String) {
    Surface(color = SafeWalkTheme.colors.blush, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = SafeWalkTheme.colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}
