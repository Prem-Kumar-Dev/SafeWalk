package app.safewalk.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.safewalk.android.R

// Coral lead, warm neutrals. Text on coral is dark ink: white on #FF8A8A fails contrast.
val Coral = Color(0xFFFF8A8A)
val CoralInk = Color(0xFF3B1517)
val Ink = Color(0xFF231C1E)

/** Colours Material's scheme has no slot for. Read them through [SafeWalkTheme.colors]. */
@Immutable
data class SafeWalkColors(
    val accent: Color,          // coral-deep, for text and icons on light surfaces
    val blush: Color,           // icon wells, selected chips
    val blushSoft: Color,       // selected route card, stat tiles
    val sand: Color,            // quiet buttons
    val banner: Color,          // next-turn and off-route banner
    val onBanner: Color,
    val onBannerMuted: Color,
    val emergency: Color,       // Call 911 only
    val amber: Color,           // elevated-risk stretches, warnings
    val riskHigh: Color,        // high-risk stretches
    val success: Color,         // arrived
    val routeOther: Color,      // the route not chosen
)

private val LightExtra = SafeWalkColors(
    // #B5403D, not the lighter #E25C5C: it is used for small text, which needs 4.5:1 on white and on blush
    accent = Color(0xFFB5403D), blush = Color(0xFFFFE3E1), blushSoft = Color(0xFFFFF4F3),
    sand = Color(0xFFF2EBEA), banner = Ink, onBanner = Color.White, onBannerMuted = Color(0xFFD9CFD1),
    emergency = Color(0xFFD7263D), amber = Color(0xFFF2A93B), riskHigh = Color(0xFFC2410C),
    success = Color(0xFF2F9E62), routeOther = Color(0xFFA39C9E),
)
private val DarkExtra = SafeWalkColors(
    accent = Color(0xFFFF9E9E), blush = Color(0xFF45282A), blushSoft = Color(0xFF2E2224),
    sand = Color(0xFF342B2C), banner = Color(0xFF3A2F31), onBanner = Color.White, onBannerMuted = Color(0xFFD9CFD1),
    emergency = Color(0xFFFF5C6C), amber = Color(0xFFF2A93B), riskHigh = Color(0xFFF07A4A),
    success = Color(0xFF4CC283), routeOther = Color(0xFF8C8385),
)

private val Light = lightColorScheme(
    primary = Coral, onPrimary = CoralInk,
    primaryContainer = Color(0xFFFFE3E1), onPrimaryContainer = CoralInk,
    secondary = Color(0xFF6E6366), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3E1), onSecondaryContainer = Ink,
    background = Color(0xFFFAF6F5), onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Color(0xFFF2EBEA), onSurfaceVariant = Color(0xFF6E6366),
    surfaceTint = Coral,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFDFAF9),
    surfaceContainer = Color(0xFFF8F3F2), surfaceContainerHigh = Color(0xFFF4EEED),
    surfaceContainerHighest = Color(0xFFEFE8E7),
    outline = Color(0xFFA0979A), outlineVariant = Color(0xFFEDE4E3),
    error = Color(0xFFD7263D), onError = Color.White,
)
private val Dark = darkColorScheme(
    primary = Coral, onPrimary = CoralInk,
    primaryContainer = Color(0xFF45282A), onPrimaryContainer = Color(0xFFFFE3E1),
    secondary = Color(0xFFB8ADAF), onSecondary = Ink,
    secondaryContainer = Color(0xFF45282A), onSecondaryContainer = Color(0xFFF3ECEA),
    background = Color(0xFF171213), onBackground = Color(0xFFF3ECEA),
    surface = Color(0xFF211A1B), onSurface = Color(0xFFF3ECEA),
    surfaceVariant = Color(0xFF342B2C), onSurfaceVariant = Color(0xFFB8ADAF),
    surfaceTint = Coral,
    surfaceContainerLowest = Color(0xFF120E0F), surfaceContainerLow = Color(0xFF1C1617),
    surfaceContainer = Color(0xFF211A1B), surfaceContainerHigh = Color(0xFF2B2324),
    surfaceContainerHighest = Color(0xFF342B2C),
    outline = Color(0xFF8C8385), outlineVariant = Color(0xFF3A3133),
    error = Color(0xFFFF5C6C), onError = Ink,
)

val Jakarta = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

private fun TextStyle.j(weight: FontWeight? = null, size: Int? = null) = copy(
    fontFamily = Jakarta,
    fontWeight = weight ?: fontWeight,
    fontSize = size?.sp ?: fontSize,
)

private val Base = Typography()
private val SafeWalkType = Typography(
    displayLarge = Base.displayLarge.j(FontWeight.Bold), displayMedium = Base.displayMedium.j(FontWeight.Bold),
    displaySmall = Base.displaySmall.j(FontWeight.Bold),
    headlineLarge = Base.headlineLarge.j(FontWeight.Bold), headlineMedium = Base.headlineMedium.j(FontWeight.Bold),
    headlineSmall = Base.headlineSmall.j(FontWeight.Bold),
    titleLarge = Base.titleLarge.j(FontWeight.Bold, 22), titleMedium = Base.titleMedium.j(FontWeight.SemiBold, 17),
    titleSmall = Base.titleSmall.j(FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.j(size = 15), bodyMedium = Base.bodyMedium.j(), bodySmall = Base.bodySmall.j(FontWeight.Medium),
    labelLarge = Base.labelLarge.j(FontWeight.SemiBold, 15), labelMedium = Base.labelMedium.j(FontWeight.SemiBold, 13),
    labelSmall = Base.labelSmall.j(FontWeight.Medium),
)

private val SafeWalkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

private val LocalSafeWalkColors = staticCompositionLocalOf { LightExtra }

object SafeWalkTheme {
    val colors: SafeWalkColors
        @Composable @ReadOnlyComposable get() = LocalSafeWalkColors.current
}

@Composable
fun SafeWalkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalSafeWalkColors provides if (dark) DarkExtra else LightExtra) {
        val scheme = if (dark) Dark else Light
        MaterialTheme(colorScheme = scheme, typography = SafeWalkType, shapes = SafeWalkShapes) {
            // Text outside a Surface falls back to LocalContentColor, which is black unless set here.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
