package com.neopal.pet.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neopal.pet.R
import com.neopal.pet.domain.CareActions
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.Routes
import com.neopal.pet.ui.components.MinTouchTarget
import com.neopal.pet.ui.components.PixelBadge
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelDivider
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.ui.theme.NeoColors

/** Below this the tiles read as a stack of ribbons; above it a third column still has room. */
private val TwoColumnWidth = 300.dp
private val ThreeColumnWidth = 640.dp

/** One cartridge on the shelf. Held as data so the same three render at any column count. */
private data class GameEntry(
    val title: String,
    val subtitle: String,
    val accent: Color,
    val gameId: String,
    val route: String,
    val art: @Composable () -> Unit,
)

/** Game-select grid, styled like a console home menu row of cartridges. */
@Composable
fun GamesScreen(viewModel: PetViewModel, onPlay: (String) -> Unit, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val blocker = CareActions.canPlay(pet)
    val locked = blocker != null

    val games = listOf(
        GameEntry(
            title = "Rhythm Tap",
            subtitle = "4 lanes · 30 s\nTiming drill — big mood, small bond, ~10 energy",
            accent = NeoColors.NeonCyan,
            gameId = "rhythm",
            route = Routes.GAME_RHYTHM,
        ) {
            Canvas(Modifier.size(52.dp)) {
                repeat(4) { i ->
                    drawRect(
                        color = listOf(NeoColors.NeonCyan, NeoColors.NeonRed, NeoColors.NeonYellow, NeoColors.NeonGreen)[i],
                        topLeft = Offset(i * size.width / 4f + 2f, size.height * (0.15f + i * 0.12f)),
                        size = androidx.compose.ui.geometry.Size(size.width / 4f - 4f, size.height * 0.18f),
                    )
                }
            }
        },
        GameEntry(
            title = "Memory Match",
            subtitle = "6 rounds\nRecall drill — steady mood and bond, ~8 energy",
            accent = NeoColors.NeonPurple,
            gameId = "memory",
            route = Routes.GAME_MEMORY,
        ) {
            Canvas(Modifier.size(52.dp)) {
                val half = size.width / 2f
                listOf(NeoColors.NeonCyan, NeoColors.NeonRed, NeoColors.NeonYellow, NeoColors.NeonGreen)
                    .forEachIndexed { i, color ->
                        drawRect(
                            color = color.copy(alpha = if (i == 1) 1f else 0.45f),
                            topLeft = Offset((i % 2) * half + 2f, (i / 2) * half + 2f),
                            size = androidx.compose.ui.geometry.Size(half - 4f, half - 4f),
                        )
                    }
            }
        },
        GameEntry(
            title = "Snack Catch",
            subtitle = "40 s · 3 lives\nReflex drill — best bond gain, ~12 energy",
            accent = NeoColors.NeonYellow,
            gameId = "catch",
            route = Routes.GAME_CATCH,
        ) {
            Canvas(Modifier.size(52.dp)) {
                drawCircle(Color(0xFFE0555F), size.minDimension * 0.14f, Offset(size.width * 0.3f, size.height * 0.25f))
                drawCircle(Color(0xFF8FD8E8), size.minDimension * 0.12f, Offset(size.width * 0.7f, size.height * 0.42f))
                drawArc(
                    color = NeoColors.NeonGreen,
                    startAngle = 180f, sweepAngle = 180f, useCenter = true,
                    topLeft = Offset(size.width * 0.25f, size.height * 0.62f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.5f, size.height * 0.34f),
                )
            }
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The app draws edge to edge; without this the back button sits under the status bar.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(pixelUnits(4)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                "PLAY",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }

        if (blocker != null) {
            // The reason has to outrank the tiles, or players keep tapping a dead grid.
            PixelPanel(
                modifier = Modifier.fillMaxWidth(),
                fill = lerp(MaterialTheme.colorScheme.surfaceVariant, NeoColors.NeonRed, 0.12f),
                accent = NeoColors.NeonRed,
                title = "No games right now",
                contentPadding = PaddingValues(pixelUnits(3)),
            ) {
                Text(blocker, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            Text(
                text = "Winning raises mood and bond, and burns energy. Play often for an Athletic evolution.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(pixelUnits(4)))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = when {
                maxWidth >= ThreeColumnWidth -> 3
                maxWidth >= TwoColumnWidth -> 2
                else -> 1
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(pixelUnits(3)),
                modifier = Modifier
                    .fillMaxWidth()
                    // Dimmed rather than hidden: the shelf is still worth seeing while it is shut.
                    .alpha(if (locked) 0.45f else 1f),
            ) {
                games.chunked(columns).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(pixelUnits(3)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // No filler cells: an odd tile out reads better full width than beside a gap.
                        row.forEach { game ->
                            GameTile(
                                title = game.title,
                                subtitle = game.subtitle,
                                accent = game.accent,
                                best = pet.highScores[game.gameId] ?: 0,
                                enabled = !locked,
                                onClick = { onPlay(game.route) },
                                modifier = Modifier.weight(1f),
                                art = game.art,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(pixelUnits(5)))
        Text(
            "Record: ${pet.gamesWon} wins in ${pet.gamesPlayed} games",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A cartridge: a panel in the game's own colour, with the personal best sitting on the title
 * strip. The strip is rebuilt by hand rather than passed to [PixelPanel] because the kit's strip
 * takes a title and nothing else, and the best score belongs up there with the name.
 */
@Composable
private fun GameTile(
    title: String,
    subtitle: String,
    accent: Color,
    best: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    art: @Composable () -> Unit,
) {
    val fill = MaterialTheme.colorScheme.surfaceVariant
    PixelPanel(
        modifier = modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget),
        fill = fill,
        accent = accent,
        contentPadding = PaddingValues(0.dp),
        // Null, not a no-op: a tile that cannot be played should not announce itself as a button.
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(lerp(fill, accent, 0.20f))
                .padding(horizontal = pixelUnits(2), vertical = pixelUnits(2)),
        ) {
            // Same accent block the kit's own strip uses, so a game tile reads as one of the family.
            Box(Modifier.size(width = pixelUnits(1), height = pixelUnits(3)).background(accent))
            Spacer(Modifier.width(pixelUnits(2)))
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (best > 0) {
                Spacer(Modifier.width(pixelUnits(1)))
                // The chip is a bare number so it fits a half-width tile; the readout says what it is.
                PixelBadge(
                    text = "$best",
                    color = NeoColors.NeonYellow,
                    contentColor = NeoColors.OnLight,
                    modifier = Modifier.semantics { contentDescription = "Best score $best" },
                )
            }
        }
        PixelDivider(color = lerp(fill, accent, 0.45f))
        Column(modifier = Modifier.padding(pixelUnits(2))) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pixelUnits(18))
                    // The art slot is pressed *into* the tile, so the cartridge reads as inset glass.
                    .pixelSurface(
                        fill = lerp(fill, accent, 0.24f),
                        accent = accent,
                        bevel = PixelBevel.PRESSED,
                    )
                    .padding(pixelUnits(2)),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { art() }
            }
            Spacer(Modifier.height(pixelUnits(2)))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
