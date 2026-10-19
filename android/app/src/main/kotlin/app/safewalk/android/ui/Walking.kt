package app.safewalk.android.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.safewalk.android.R
import app.safewalk.core.RouteDetails
import app.safewalk.core.Step
import app.safewalk.core.Turn
import kotlin.math.roundToInt

@Composable
fun WalkingOverlay(s: UiState, vm: SafeWalkViewModel) {
    val walk = s.walk ?: return
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize()) {
        // next step
        Surface(
            color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.large, shadowElevation = 6.dp,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(12.dp).fillMaxWidth(),
        ) {
            val fix = walk.fix
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val step = fix?.nextStep
                Text(arrow(step?.turn), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        when {
                            walk.arrived -> stringResource(R.string.arrived)
                            step != null -> instruction(step)
                            fix == null && walk.route.steps.isNotEmpty() -> instruction(walk.route.steps.first())
                            else -> stringResource(R.string.arrive_destination)
                        },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    if (fix != null && !walk.arrived) Text(formatDistance(fix.metresToNextStep, s.units))
                }
            }
        }

        // bottom bar
        Surface(
            tonalElevation = 2.dp, shadowElevation = 12.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val left = walk.fix?.remainingM ?: walk.route.lengthM
                Text(
                    stringResource(R.string.left_summary, formatDistance(left, s.units),
                        (left / RouteDetails.WALK_SPEED_MS / 60).roundToInt()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.route_fine_print), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // opens the dialer with 911 filled in; the user taps once more to call
                    Button(
                        onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))) },
                        colors = ButtonDefaults.buttonColors(containerColor = Emergency, contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.call_911))
                    }
                    OutlinedButton(onClick = vm::endWalk, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(stringResource(R.string.end))
                    }
                }
            }
        }
    }

    if (walk.offRoutePrompt) {
        AlertDialog(
            onDismissRequest = vm::dismissOffRoute,
            text = { Text(stringResource(R.string.off_route_prompt)) },
            confirmButton = { TextButton(onClick = vm::rerouteFromHere) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = vm::dismissOffRoute) { Text(stringResource(R.string.not_now)) } },
        )
    }
    if (walk.arrived) {
        AlertDialog(
            onDismissRequest = vm::endWalk,
            text = { Text(stringResource(R.string.arrived), style = MaterialTheme.typography.titleLarge) },
            confirmButton = { TextButton(onClick = vm::endWalk) { Text(stringResource(R.string.ok)) } },
        )
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
