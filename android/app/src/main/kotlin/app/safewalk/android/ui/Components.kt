package app.safewalk.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.safewalk.android.R

/** Coral pill with dark ink text: the one main action on a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                  leading: (@Composable () -> Unit)? = null) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

/** Warm grey pill for the second choice next to a [PrimaryButton]. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SafeWalkTheme.colors.sand, contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = null,
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
}

/** Opens the dialer with 911 filled in; the user taps once more to call. Red is kept for this button only. */
@Composable
fun Call911Button(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val red = SafeWalkTheme.colors.emergency
    OutlinedButton(
        onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))) },
        border = BorderStroke(1.5.dp, red),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = red),
        modifier = modifier.height(56.dp),
    ) {
        Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.call_911), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

/** White sheet anchored to the bottom of the map, with a grab handle. */
@Composable
fun BottomSheetCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 16.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
            Box(
                Modifier.padding(top = 10.dp, bottom = 14.dp).align(Alignment.CenterHorizontally)
                    .size(width = 32.dp, height = 5.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
            )
            content()
        }
    }
}

/** Dark banner across the top of the map: next turn while walking, off-route warning. */
@Composable
fun DarkBanner(
    title: String, subtitle: String?, badgeColor: Color, modifier: Modifier = Modifier,
    badge: @Composable () -> Unit,
) {
    val c = SafeWalkTheme.colors
    Surface(
        color = c.banner, contentColor = c.onBanner, shape = MaterialTheme.shapes.large, shadowElevation = 8.dp,
        modifier = modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(badgeColor, CircleShape), contentAlignment = Alignment.Center) { badge() }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.onBanner)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.onBannerMuted)
            }
        }
    }
}

/** Small coral-deep heading above a group of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = SafeWalkTheme.colors.accent,
        modifier = modifier.padding(top = 20.dp, bottom = 6.dp))
}

/** Pill chip: ink when selected, sand otherwise. */
@Composable
fun PillChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.onSurface else SafeWalkTheme.colors.sand,
        contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
    }
}

/** Two-line text block used in sheets: bold headline, grey line under it. */
@Composable
fun SheetTitle(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    if (subtitle != null) {
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
