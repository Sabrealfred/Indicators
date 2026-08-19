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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.sin

/**
 * End of a run. Shows what the pet achieved, how it died, and hands the player straight into
 * the next generation — which inherits coins, cosmetics, awards and the album.
 */
@Composable
fun MemorialScreen(
    viewModel: PetViewModel,
    onStartNextGeneration: () -> Unit,
    onOpenDiary: () -> Unit,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val config = ui.config

    val transition = rememberInfiniteTransition(label = "memorial")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "memorial-clock",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF141726), Color(0xFF08090F))),
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Text("IN MEMORY", style = MaterialTheme.typography.labelMedium, color = NeoColors.OnDarkMuted)
        Text(pet.name.uppercase(), style = MaterialTheme.typography.displayLarge, color = NeoColors.OnDark)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(220.dp)) {
                // A gravestone with a soft spirit light drifting upward.
                val w = size.width
                val h = size.height
                drawRoundRect(
                    color = Color(0xFF4A4E5A),
                    topLeft = Offset(w * 0.30f, h * 0.35f),
                    size = Size(w * 0.40f, h * 0.45f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.20f, w * 0.20f),
                )
                drawRect(
                    color = Color(0xFF2F3340),
                    topLeft = Offset(w * 0.22f, h * 0.78f),
                    size = Size(w * 0.56f, h * 0.06f),
                )
                // Cross-free marker: a simple engraved line.
                drawLine(
                    color = Color(0xFF9AA0AA),
                    start = Offset(w * 0.40f, h * 0.55f),
                    end = Offset(w * 0.60f, h * 0.55f),
                    strokeWidth = w * 0.02f,
                )
                val spiritY = h * (0.35f - 0.12f * ((sin(time) + 1f) / 2f))
                drawCircle(
                    color = NeoColors.NeonCyan.copy(alpha = 0.30f),
                    radius = w * 0.10f,
                    center = Offset(w * 0.5f, spiritY),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = w * 0.035f,
                    center = Offset(w * 0.5f, spiritY),
                )
            }
        }

        Text(
            text = pet.deathReason?.displayName ?: "Gone",
            style = MaterialTheme.typography.headlineMedium,
            color = NeoColors.NeonRed,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Lived ${pet.ageInPetDays(config)} days as a ${pet.stage.displayName}, " +
                "${pet.branch.displayName.lowercase()} to the end.",
            style = MaterialTheme.typography.bodyMedium,
            color = NeoColors.OnDarkMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        // The last thing it wrote, not a tally of what you got wrong. A memorial that prints a
        // mistake counter turns a death into an invoice.
        pet.chronicle.lastOrNull()?.let { last ->
            Text(
                text = "“${last.text}”",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = NeoColors.OnDark,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "${pet.mealsEaten} meals shared · ${pet.gamesWon} games won · ${pet.album.size} pictures kept",
            style = MaterialTheme.typography.labelSmall,
            color = NeoColors.OnDarkMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onOpenDiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Read the diary")
            }
            OutlinedButton(
                onClick = onStartNextGeneration,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("When you're ready: generation ${pet.generation + 1}")
            }
            Text(
                text = "Stay a moment",
                style = MaterialTheme.typography.labelSmall,
                color = NeoColors.OnDarkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBack)
                    .padding(vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
