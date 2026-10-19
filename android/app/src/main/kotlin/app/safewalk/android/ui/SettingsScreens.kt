package app.safewalk.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.safewalk.android.R
import app.safewalk.android.data.DataState
import app.safewalk.android.data.Units
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, vm: SafeWalkViewModel, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = { vm.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
fun SettingsScreen(s: UiState, vm: SafeWalkViewModel) {
    var showDisclaimer by remember { mutableStateOf(false) }
    Page(stringResource(R.string.settings), vm) {
        LevelPicker(s.level, vm::setLevel)
        HorizontalDivider()
        Text(stringResource(R.string.units), style = MaterialTheme.typography.labelLarge)
        for ((u, label) in listOf(Units.KM to R.string.units_km, Units.MILES to R.string.units_miles)) {
            Row(Modifier.fillMaxWidth().clickable { vm.setUnits(u) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.units == u, onClick = { vm.setUnits(u) })
                Text(stringResource(label))
            }
        }
        HorizontalDivider()
        OutlinedButton(onClick = vm::clearRecent, enabled = s.recent.isNotEmpty()) { Text(stringResource(R.string.clear_recent)) }
        HorizontalDivider()
        Text(stringResource(R.string.map_data), style = MaterialTheme.typography.labelLarge)
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
            Text(stringResource(R.string.data_status, formatWeek(week), updated))
        }
        if (s.appTooOld) Text(stringResource(R.string.update_app), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = vm::checkForUpdateNow, enabled = !s.checkingUpdate) {
            Text(stringResource(if (s.checkingUpdate) R.string.checking else R.string.check_update))
        }
        HorizontalDivider()
        TextButton(onClick = { vm.open(Screen.ABOUT) }) { Text(stringResource(R.string.about)) }
        TextButton(onClick = { vm.open(Screen.PRIVACY) }) { Text(stringResource(R.string.privacy)) }
        TextButton(onClick = { showDisclaimer = true }) { Text(stringResource(R.string.disclaimer)) }
    }
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
            Text(stringResource(R.string.about_model, m.model["version"] ?: m.model["name"] ?: "?", m.risk.weekStart),
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
