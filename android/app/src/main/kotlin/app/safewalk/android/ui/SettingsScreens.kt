package app.safewalk.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.safewalk.android.R
import app.safewalk.android.data.DataState
import app.safewalk.android.data.Units
import app.safewalk.core.SafetyLevel
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, vm: SafeWalkViewModel, padded: Boolean = true, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = if (padded) 24.dp else 0.dp, vertical = if (padded) 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (padded) 16.dp else 0.dp),
        ) { content() }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector, title: String, subtitle: String? = null, tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (tint == MaterialTheme.colorScheme.onSurface)
            MaterialTheme.colorScheme.onSurfaceVariant else tint)
        Spacer(Modifier.width(24.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                for ((value, label) in options) {
                    Row(Modifier.fillMaxWidth().clickable { onPick(value); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = value == selected, onClick = { onPick(value); onDismiss() })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SettingsScreen(s: UiState, vm: SafeWalkViewModel) {
    val c = SafeWalkTheme.colors
    var showDisclaimer by remember { mutableStateOf(false) }
    var pickLevel by remember { mutableStateOf(false) }
    var pickUnits by remember { mutableStateOf(false) }
    val unitOptions = listOf(Units.KM to stringResource(R.string.units_km), Units.MILES to stringResource(R.string.units_miles))
    Page(stringResource(R.string.settings), vm, padded = false) {
        SectionLabel(stringResource(R.string.section_routes), Modifier.padding(horizontal = 24.dp))
        SettingRow(Icons.Default.Route, stringResource(R.string.route_preference), levelLabel(s.level)) { pickLevel = true }
        SettingRow(Icons.Default.Straighten, stringResource(R.string.units),
            unitOptions.first { it.first == s.units }.second) { pickUnits = true }

        SectionLabel(stringResource(R.string.section_phone), Modifier.padding(horizontal = 24.dp))
        SettingRow(Icons.Default.DeleteOutline, stringResource(R.string.clear_recent), tint = c.emergency,
            enabled = s.recent.isNotEmpty(), onClick = vm::clearRecent)

        SectionLabel(stringResource(R.string.map_data), Modifier.padding(horizontal = 24.dp))
        Surface(color = c.blushSoft, shape = MaterialTheme.shapes.large,
            modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                val ready = s.data as? DataState.Ready
                val week = ready?.data?.risk?.weekStart
                if (ready != null && week != null) {
                    val updated = when {
                        ready.updatedAtMs == 0L -> stringResource(R.string.updated_never)
                        else -> {
                            val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - ready.updatedAtMs).toInt()
                            if (days == 0) stringResource(R.string.updated_today)
                            else pluralStringResource(R.plurals.updated_days_ago, days, days)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(if (s.appTooOld) c.amber else c.success, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.forecast_week, formatWeek(week)), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(updated, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 2.dp))
                }
                if (s.appTooOld) Text(stringResource(R.string.update_app), color = c.emergency,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = vm::checkForUpdateNow, enabled = !s.checkingUpdate,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = c.accent),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (s.checkingUpdate) R.string.checking else R.string.check_update),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        SectionLabel(stringResource(R.string.section_about), Modifier.padding(horizontal = 24.dp))
        SettingRow(Icons.Outlined.Info, stringResource(R.string.about), stringResource(R.string.about_sub)) { vm.open(Screen.ABOUT) }
        SettingRow(Icons.Outlined.Lock, stringResource(R.string.privacy), stringResource(R.string.privacy_sub)) { vm.open(Screen.PRIVACY) }
        SettingRow(Icons.Outlined.Description, stringResource(R.string.disclaimer), stringResource(R.string.disclaimer_sub)) {
            showDisclaimer = true
        }
        Spacer(Modifier.height(24.dp))
    }
    if (pickLevel) {
        ChoiceDialog(stringResource(R.string.route_preference), SafetyLevel.entries.map { it to levelLabel(it) }, s.level,
            vm::setLevel) { pickLevel = false }
    }
    if (pickUnits) ChoiceDialog(stringResource(R.string.units), unitOptions, s.units, vm::setUnits) { pickUnits = false }
    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            text = { Text(stringResource(R.string.disclaimer_body)) },
            confirmButton = { TextButton(onClick = { showDisclaimer = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
fun AboutScreen(s: UiState, vm: SafeWalkViewModel) {
    Page(stringResource(R.string.about), vm) {
        Text(stringResource(R.string.about_body_1), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.about_body_2), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.about_sources), style = MaterialTheme.typography.bodyMedium)
        val ready = s.data as? DataState.Ready
        if (ready != null) {
            val m = ready.data.manifest
            Text(stringResource(R.string.about_model, m.modelText("version") ?: m.modelText("name") ?: "?", m.risk.weekStart),
                style = MaterialTheme.typography.bodySmall)
        }
        val email = stringResource(R.string.feedback_email)
        if (email.isNotBlank()) Text(stringResource(R.string.about_contact, email), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun PrivacyScreen(vm: SafeWalkViewModel) {
    Page(stringResource(R.string.privacy), vm) {
        Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyLarge)
    }
}
