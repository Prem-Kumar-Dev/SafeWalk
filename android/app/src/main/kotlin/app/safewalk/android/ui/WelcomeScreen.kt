package app.safewalk.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.safewalk.android.R

/** First launch: three pages, the disclaimer, then the location reason (app-spec 01, screen 1). */
@Composable
fun WelcomeScreen(onDone: (askLocation: Boolean) -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (page) {
                0, 1, 2 -> {
                    if (page == 0) {
                        Box(Modifier.size(240.dp).background(SafeWalkTheme.colors.blush, CircleShape),
                            contentAlignment = Alignment.Center) { TwoRoutes() }
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(stringResource(listOf(R.string.welcome_1, R.string.welcome_2, R.string.welcome_3)[page]),
                        style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(40.dp))
                    PrimaryButton(stringResource(R.string.next), { page++ }, Modifier.fillMaxWidth())
                }
                3 -> {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                        Text(stringResource(R.string.disclaimer_body), style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(20.dp))
                    }
                    Spacer(Modifier.height(32.dp))
                    PrimaryButton(stringResource(R.string.disclaimer_accept), { page++ }, Modifier.fillMaxWidth())
                }
                else -> {
                    Text(stringResource(R.string.location_reason), style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(32.dp))
                    PrimaryButton(stringResource(R.string.allow_location), { onDone(true) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    QuietButton(stringResource(R.string.not_now), { onDone(false) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun TwoRoutes() {
    val brand = MaterialTheme.colorScheme.primary
    val grey = SafeWalkTheme.colors.routeOther
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(170.dp)) {
        val w = size.width; val h = size.height
        val direct = Path().apply { moveTo(w * 0.15f, h * 0.85f); lineTo(w * 0.85f, h * 0.15f) }
        val lower = Path().apply {
            moveTo(w * 0.15f, h * 0.85f); lineTo(w * 0.15f, h * 0.45f); lineTo(w * 0.45f, h * 0.45f)
            lineTo(w * 0.45f, h * 0.15f); lineTo(w * 0.85f, h * 0.15f)
        }
        drawPath(direct, grey, style = Stroke(10f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 20f))))
        drawPath(lower, brand, style = Stroke(16f, cap = StrokeCap.Round))
        drawCircle(Color.White, 18f, Offset(w * 0.15f, h * 0.85f)); drawCircle(ink, 18f, Offset(w * 0.15f, h * 0.85f), style = Stroke(6f))
        drawCircle(ink, 20f, Offset(w * 0.85f, h * 0.15f)); drawCircle(brand, 9f, Offset(w * 0.85f, h * 0.15f))
    }
}
