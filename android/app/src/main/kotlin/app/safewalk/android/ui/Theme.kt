package app.safewalk.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF1D67C9)
val BrandDark = Color(0xFF5EA4FF)
val Emergency = Color(0xFFC62828)

private val Light = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    secondary = Color(0xFF4A5562), background = Color(0xFFF3F5F7), surface = Color.White,
    surfaceVariant = Color(0xFFEEF1F4), onSurface = Color(0xFF121820), onSurfaceVariant = Color(0xFF4A5562),
)
private val Dark = darkColorScheme(
    primary = BrandDark, onPrimary = Color(0xFF0E1215),
    secondary = Color(0xFFAAB3BC), background = Color(0xFF0E1215), surface = Color(0xFF151A1F),
    surfaceVariant = Color(0xFF1F262C), onSurface = Color(0xFFE7EBEE), onSurfaceVariant = Color(0xFFAAB3BC),
)

@Composable
fun SafeWalkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
