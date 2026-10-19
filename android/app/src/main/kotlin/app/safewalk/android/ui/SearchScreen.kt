package app.safewalk.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.safewalk.android.R
import app.safewalk.android.data.Place

@Composable
fun SearchScreen(s: UiState, vm: SafeWalkViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = { vm.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                TextField(
                    value = s.query,
                    onValueChange = vm::onQuery,
                    placeholder = {
                        Text(stringResource(if (s.searchFor == Field.START) R.string.choose_start else R.string.search_hint))
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SafeWalkTheme.colors.blushSoft, unfocusedContainerColor = SafeWalkTheme.colors.sand,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = SafeWalkTheme.colors.accent,
                    ),
                    modifier = Modifier.weight(1f).padding(end = 12.dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                        .focusRequester(focus),
                )
            }
            if (s.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), trackColor = SafeWalkTheme.colors.blush)
            else HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                if (s.myLocation != null && s.query.isBlank()) {
                    item {
                        Row1(Icons.Default.MyLocation, stringResource(R.string.your_location)) { vm.chooseMyLocation() }
                    }
                }
                if (!vm.searchAvailable) item { Hint(stringResource(R.string.search_unavailable)) }
                if (s.query.isBlank()) {
                    if (s.recent.isNotEmpty()) item { Hint(stringResource(R.string.recent)) }
                    items(s.recent, key = { "r" + it.name }) { p -> PlaceRow(Icons.Default.History, p, vm) }
                } else {
                    items(s.results, key = { "q" + it.name }) { p -> PlaceRow(Icons.Default.Place, p, vm) }
                    if (s.results.isEmpty() && !s.searching && s.query.trim().length >= 3) {
                        item { Hint(stringResource(R.string.no_results)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(icon: ImageVector, p: Place, vm: SafeWalkViewModel) =
    Row1(icon, p.name, quiet = icon == Icons.Default.History) { vm.choosePlace(p) }

@Composable
private fun Row1(icon: ImageVector, text: String, quiet: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(if (quiet) SafeWalkTheme.colors.sand else SafeWalkTheme.colors.blush, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else SafeWalkTheme.colors.accent)
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = SafeWalkTheme.colors.accent,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}
