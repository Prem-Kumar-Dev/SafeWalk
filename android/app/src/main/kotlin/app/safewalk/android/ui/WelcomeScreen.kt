package app.safewalk.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.safewalk.android.R

/** First launch: three pages, the disclaimer, then the location reason (app-spec 01, screen 1). */
@Composable
fun WelcomeScreen(onDone: (askLocation: Boolean) -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (page) {
            0, 1, 2 -> IntroPage(page, next = { page++ }, skip = { page = 3 })
            3 -> DisclaimerPage { page++ }
            else -> LocationPage(onDone)
        }
    }
}

private val introTitles = listOf(R.string.welcome_1, R.string.welcome_2_title, R.string.welcome_3_title)
private val introBodies = listOf(R.string.welcome_1_body, R.string.welcome_2_body, R.string.welcome_3_body)

@Composable
private fun IntroPage(page: Int, next: () -> Unit, skip: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // coral panel with the illustration
        Column(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 24.dp, end = 12.dp, bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                LogoMark(Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge, color = Ink,
                    modifier = Modifier.weight(1f))
                if (page < 2) TextButton(onClick = skip) {
                    Text(stringResource(R.string.skip), color = Ink, style = MaterialTheme.typography.labelMedium)
                }
            }
            Box(Modifier.padding(top = 8.dp, end = 12.dp).fillMaxWidth().aspectRatio(1.15f)
                .background(IllustrationPaper, RoundedCornerShape(28.dp))
                .clearAndSetSemantics {}) {
                Illustration(page)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 24.dp).navigationBarsPadding()) {
            Spacer(Modifier.height(24.dp))
            PageDots(page)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(introTitles[page]), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(introBodies[page]), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(if (page == 2) R.string.continue_ else R.string.next), next,
                Modifier.fillMaxWidth().padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun PageDots(page: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            Box(Modifier.size(width = if (i == page) 20.dp else 6.dp, height = 6.dp).background(
                if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
    }
}

// The illustration sits on the coral panel, which is coral in both themes, so it keeps its light colours in dark mode.
private val IllustrationPaper = Color(0xFFFFF4F3)
private val IllustrationGrid = Color(0xFFFFE3E1)

/** Grid of blocks with a coral stepped route and the dotted straight line it avoids; page 2 adds times, page 3 a phone. */
@Composable
private fun BoxScope.Illustration(page: Int) {
    val coral = MaterialTheme.colorScheme.primary
    val grid = IllustrationGrid
    val grey = SafeWalkTheme.colors.routeOther
    val ink = Ink
    Canvas(Modifier.matchParentSize()) {
        val step = size.width / 6f
        var x = step / 2
        while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 3f); x += step }
        var y = step / 2
        while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 3f); y += step }
        if (page == 2) {
            val w = size.width * 0.36f; val h = size.height * 0.72f
            val left = (size.width - w) / 2; val top = (size.height - h) / 2
            drawRoundRect(Color.White, Offset(left, top), Size(w, h), CornerRadius(28f))
            drawRoundRect(coral, Offset(left, top), Size(w, h), CornerRadius(28f), style = Stroke(5f))
            drawRoundRect(grid, Offset(left + w * 0.35f, top + 12f), Size(w * 0.3f, 8f), CornerRadius(4f))
            stairs(Offset(left + w * 0.2f, top + h * 0.75f), Offset(left + w * 0.8f, top + h * 0.3f), coral, ink, 3, 8f)
        } else {
            val a = Offset(size.width * 0.18f, size.height * 0.82f)
            val b = Offset(size.width * 0.84f, size.height * 0.2f)
            drawLine(grey, a, b, 6f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 16f)))
            stairs(a, b, coral, ink, 3, 12f)
        }
    }
    if (page == 1) {
        TimeTag("44 min", dark = true, modifier = Modifier.align(Alignment.BottomStart).offset(x = 52.dp, y = (-56).dp))
        TimeTag("42 min", dark = false, modifier = Modifier.align(Alignment.TopEnd).offset(x = (-16).dp, y = 72.dp))
    }
    if (page == 2) {
        Box(Modifier.align(Alignment.BottomEnd).offset(x = (-64).dp, y = (-32).dp).size(40.dp)
            .background(Ink, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
        }
    }
}

/** Stepped route from [a] to [b] in [n] steps, start dot coral, end an ink square. */
private fun DrawScope.stairs(a: Offset, b: Offset, coral: Color, ink: Color, n: Int, width: Float) {
    val dx = (b.x - a.x) / n; val dy = (b.y - a.y) / n
    val p = Path().apply {
        moveTo(a.x, a.y)
        for (i in 0 until n) { relativeLineTo(0f, dy); relativeLineTo(dx, 0f) }
    }
    drawPath(p, coral, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(Color.White, width * 1.3f, a); drawCircle(coral, width * 0.9f, a)
    drawRoundRect(ink, Offset(b.x - width * 1.4f, b.y - width * 1.4f), Size(width * 2.8f, width * 2.8f), CornerRadius(width * 0.6f))
}

@Composable
private fun TimeTag(text: String, dark: Boolean, modifier: Modifier) {
    Surface(
        shape = CircleShape, color = if (dark) Ink else Color.White, contentColor = if (dark) Color.White else Ink,
        border = if (dark) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary), modifier = modifier,
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
}

/** Small stepped-route mark used next to the app name. */
@Composable
fun LogoMark(modifier: Modifier, color: Color = Ink) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val p = Path().apply { moveTo(w * 0.1f, h * 0.9f); lineTo(w * 0.1f, h * 0.55f); lineTo(w * 0.5f, h * 0.55f)
            lineTo(w * 0.5f, h * 0.2f); lineTo(w * 0.9f, h * 0.2f) }
        drawPath(p, color, style = Stroke(w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun DisclaimerPage(accept: () -> Unit) {
    val c = SafeWalkTheme.colors
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).navigationBarsPadding().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(32.dp))
            Box(Modifier.size(44.dp).background(c.blush, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = c.accent)
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.disclaimer_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.disclaimer_body), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (point in listOf(R.string.disclaimer_point_1, R.string.disclaimer_point_2, R.string.disclaimer_point_3)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(point), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        PrimaryButton(stringResource(R.string.disclaimer_accept), accept, Modifier.fillMaxWidth().padding(vertical = 16.dp))
    }
}

@Composable
private fun LocationPage(onDone: (askLocation: Boolean) -> Unit) {
    val coral = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).navigationBarsPadding().padding(horizontal = 24.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(240.dp)) {
                drawCircle(coral.copy(alpha = 0.10f), size.minDimension / 2)
                drawCircle(coral.copy(alpha = 0.18f), size.minDimension * 0.33f)
                drawCircle(coral.copy(alpha = 0.30f), size.minDimension * 0.2f)
                drawCircle(Color.White, size.minDimension * 0.07f)
                drawCircle(coral, size.minDimension * 0.05f)
            }
        }
        Text(stringResource(R.string.location_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.location_body), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.location_private), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(R.string.allow_location), { onDone(true) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        QuietButton(stringResource(R.string.not_now), { onDone(false) }, Modifier.fillMaxWidth().padding(bottom = 16.dp))
    }
}
