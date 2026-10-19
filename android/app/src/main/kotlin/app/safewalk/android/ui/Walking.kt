package app.safewalk.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.safewalk.android.R
import app.safewalk.core.RouteDetails
import app.safewalk.core.Step
import app.safewalk.core.Turn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
fun WalkingOverlay(s: UiState, vm: SafeWalkViewModel) {
    val walk = s.walk ?: return
    val view = LocalView.current
    val c = SafeWalkTheme.colors
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize()) {
        val fix = walk.fix
        when {
            walk.arrived -> Unit
            walk.offRoutePrompt -> DarkBanner(
                title = stringResource(R.string.off_route_title),
                subtitle = fix?.let { stringResource(R.string.off_route_distance, formatDistance(it.offRouteM, s.units)) },
                badgeColor = c.amber,
            ) { Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp)) }
            else -> {
                val step = fix?.nextStep
                DarkBanner(
                    title = when {
                        step != null -> instruction(step)
                        fix == null && walk.route.steps.isNotEmpty() -> instruction(walk.route.steps.first())
                        else -> stringResource(R.string.arrive_destination)
                    },
                    subtitle = fix?.let { formatDistance(it.metresToNextStep, s.units) },
                    badgeColor = MaterialTheme.colorScheme.primary,
                ) { Text(arrow(step?.turn), color = CoralInk, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            }
        }

        BottomSheetCard(Modifier.align(Alignment.BottomCenter)) {
            when {
                walk.arrived -> Arrived(s, walk, vm)
                walk.offRoutePrompt -> {
                    SheetTitle(stringResource(R.string.off_route_heading), stringResource(R.string.off_route_ask))
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton(stringResource(R.string.yes), vm::rerouteFromHere, Modifier.weight(1f))
                        QuietButton(stringResource(R.string.not_now), vm::dismissOffRoute, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Call911Button(Modifier.fillMaxWidth())
                }
                else -> {
                    val left = fix?.remainingM ?: walk.route.lengthM
                    Text(
                        stringResource(R.string.left_summary, formatDistance(left, s.units),
                            (left / RouteDetails.WALK_SPEED_MS / 60).roundToInt()),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.route_fine_print), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Call911Button(Modifier.weight(1f))
                        QuietButton(stringResource(R.string.end), vm::endWalk, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Arrived(s: UiState, walk: WalkState, vm: SafeWalkViewModel) {
    val c = SafeWalkTheme.colors
    val arrivedAt = walk.arrivedAtMs ?: System.currentTimeMillis()
    val minutes = ((arrivedAt - walk.startedAtMs) / 60_000).toInt().coerceAtLeast(1)
    val clock = Instant.ofEpochMilli(arrivedAt).atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).background(c.success, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            SheetTitle(stringResource(R.string.arrived), s.end?.place?.name)
        }
    }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat(formatDistance(walk.route.lengthM, s.units), stringResource(R.string.arrived_walked), Modifier.weight(1f))
        Stat(formatMinutes(minutes), stringResource(R.string.arrived_on_foot), Modifier.weight(1f))
        Stat(clock, stringResource(R.string.arrived_at), Modifier.weight(1f))
    }
    Spacer(Modifier.height(20.dp))
    PrimaryButton(stringResource(R.string.back_to_map), vm::endWalk, Modifier.fillMaxWidth())
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Surface(color = SafeWalkTheme.colors.blushSoft, shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun arrow(t: Turn?) = when (t) {
    Turn.LEFT -> "↰"
    Turn.RIGHT -> "↱"
    Turn.U_TURN -> "↶"
    else -> "↑"
}

@Composable
private fun instruction(step: Step): String {
    val street = if (step.street == "path") stringResource(R.string.path) else titleCase(step.street)
    return when (step.turn) {
        Turn.START -> stringResource(R.string.head_along, street)
        Turn.LEFT -> stringResource(R.string.turn_left, street)
        Turn.RIGHT -> stringResource(R.string.turn_right, street)
        Turn.U_TURN -> stringResource(R.string.make_u_turn, street)
        Turn.STRAIGHT -> stringResource(R.string.continue_onto, street)
    }
}

/** City centerline names are upper case ("W ADAMS ST"); show them as "W Adams St". */
fun titleCase(s: String): String = s.lowercase().split(' ').joinToString(" ") { w ->
    if (w.length == 1 && w[0] in "nsew") w.uppercase() else w.replaceFirstChar { it.uppercase() }
}
