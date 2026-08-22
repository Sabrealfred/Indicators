package com.neopal.pet.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.Mood
import com.neopal.pet.domain.Species
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.ui.theme.NeoColors
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Cold-boot screen: a logo that draws itself, a bouncing mascot, then a "press to start"
 * prompt. Mirrors the two-beat rhythm a console gives you before the home menu.
 */
@Composable
fun BootScreen(
    loading: Boolean,
    hasSave: Boolean,
    onContinue: (Boolean) -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "boot")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "boot-clock",
    )
    val glow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "boot-glow",
    )

    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        delay(1_400)
        ready = !loading
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(NeoColors.SurfaceDark, NeoColors.ChassisBlack),
                    center = Offset(0.5f, 0.4f),
                    radius = 1400f,
                ),
            )
            // Edge to edge: keep the content out of the status and gesture bars. The
            // background is applied first on purpose, so it still bleeds under them.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .clickable(enabled = ready, onClickLabel = "Start") { onContinue(hasSave) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Mascot bouncing above the wordmark.
            Canvas(modifier = Modifier.size(180.dp)) {
                val bob = sin(time) * 0.05f
                drawCreature(
                    center = Offset(size.width / 2f, size.height * 0.55f),
                    unit = size.minDimension,
                    spec = CreatureSpec(
                        species = Species.AQUA,
                        stage = LifeStage.CHILD,
                        branch = EvolutionBranch.BALANCED,
                        mood = Mood.HAPPY,
                    ),
                    frame = CreatureFrame(
                        bobY = bob,
                        squash = 1f + bob * 0.6f,
                        mouthOpen = 0.4f,
                        armSwing = sin(time * 2f) * 0.7f,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "NEOPAL",
                style = MaterialTheme.typography.displayLarge,
                color = NeoColors.OnDark,
            )
            Text(
                text = "VIRTUAL PET SYSTEM",
                style = MaterialTheme.typography.labelMedium,
                color = NeoColors.NeonCyan.copy(alpha = glow),
            )
            Spacer(Modifier.height(36.dp))
            // The prompt is the one piece of chrome on the boot screen, so it wears the kit:
            // a raised face once the save is in, held pressed-in while there is nothing to tap.
            PixelPanel(
                modifier = Modifier.graphicsLayer { alpha = if (ready) glow else 0.5f },
                fill = NeoColors.SurfaceCard,
                accent = if (ready) NeoColors.NeonCyan else NeoColors.OnDarkMuted,
                background = NeoColors.ChassisBlack,
                bevel = if (ready) PixelBevel.RAISED else PixelBevel.PRESSED,
                contentPadding = PaddingValues(horizontal = pixelUnits(4), vertical = pixelUnits(2)),
            ) {
                Text(
                    text = if (ready) "TAP TO START" else "LOADING...",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ready) NeoColors.OnDark else NeoColors.OnDarkMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }

        // Two rail stripes framing the screen, the visual signature of the console look.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stripe = size.width * 0.045f
            drawRect(
                brush = Brush.verticalGradient(listOf(NeoColors.NeonCyan, Color.Transparent)),
                topLeft = Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(stripe, size.height),
            )
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, NeoColors.NeonRed)),
                topLeft = Offset(size.width - stripe, 0f),
                size = androidx.compose.ui.geometry.Size(stripe, size.height),
            )
        }
    }
}
