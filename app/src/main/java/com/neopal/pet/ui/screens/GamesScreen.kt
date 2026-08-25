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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neopal.pet.R
import com.neopal.pet.domain.CareActions
import com.neopal.pet.domain.MiniGame
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

/** One cartridge on the shelf. Held as data so the whole shelf reflows at any column count. */
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
            title = stringResource(R.string.game_rhythm),
            subtitle = stringResource(R.string.game_rhythm_blurb),
            accent = NeoColors.NeonCyan,
            gameId = MiniGame.RHYTHM.id,
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
            title = stringResource(R.string.game_memory),
            subtitle = stringResource(R.string.game_memory_blurb),
            accent = NeoColors.NeonPurple,
            gameId = MiniGame.MEMORY.id,
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
            title = stringResource(R.string.game_catch),
            subtitle = stringResource(R.string.game_catch_blurb),
            accent = NeoColors.NeonYellow,
            gameId = MiniGame.CATCH.id,
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
        GameEntry(
            title = stringResource(R.string.game_hide),
            subtitle = stringResource(R.string.game_hide_blurb),
            accent = NeoColors.NeonGreen,
            gameId = MiniGame.HIDE.id,
            route = Routes.GAME_HIDE,
        ) {
            Canvas(Modifier.size(52.dp)) {
                // A crate with a tail showing behind it: the whole game in one picture.
                drawRect(
                    color = NeoColors.NeonGreen,
                    topLeft = Offset(size.width * 0.30f, size.height * 0.42f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.46f, size.height * 0.44f),
                )
                drawRect(
                    color = Color(0xFF1B2B20),
                    topLeft = Offset(size.width * 0.30f, size.height * 0.60f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.46f, size.height * 0.05f),
                )
                drawArc(
                    color = NeoColors.NeonYellow,
                    startAngle = 200f, sweepAngle = 140f, useCenter = false,
                    topLeft = Offset(size.width * 0.05f, size.height * 0.38f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.30f, size.height * 0.34f),
                    style = Stroke(width = size.minDimension * 0.09f),
                )
            }
        },
        GameEntry(
            title = stringResource(R.string.game_fetch),
            subtitle = stringResource(R.string.game_fetch_blurb),
            accent = NeoColors.NeonRed,
            gameId = MiniGame.FETCH.id,
            route = Routes.GAME_FETCH,
        ) {
            Canvas(Modifier.size(52.dp)) {
                // An arc with the ball at its apex and the hunch marked where it will land.
                drawArc(
                    color = NeoColors.NeonRed,
                    startAngle = 200f, sweepAngle = 140f, useCenter = false,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.16f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.72f),
                    style = Stroke(width = size.minDimension * 0.07f),
                )
                drawCircle(NeoColors.NeonYellow, size.minDimension * 0.13f, Offset(size.width * 0.5f, size.height * 0.20f))
                drawCircle(
                    color = NeoColors.NeonPurple,
                    radius = size.minDimension * 0.10f,
                    center = Offset(size.width * 0.84f, size.height * 0.80f),
                    style = Stroke(width = size.minDimension * 0.05f),
                )
            }
        },
        GameEntry(
            title = stringResource(R.string.game_duet),
            subtitle = stringResource(R.string.game_duet_blurb),
            accent = NeoColors.NeonPurple,
            gameId = MiniGame.DUET.id,
            route = Routes.GAME_DUET,
        ) {
            Canvas(Modifier.size(52.dp)) {
                // Call and response: your bars, then its, pitched a little differently.
                val w = size.width * 0.13f
                listOf(0.40f, 0.24f, 0.52f).forEachIndexed { i, top ->
                    drawRect(
                        color = NeoColors.NeonCyan,
                        topLeft = Offset(size.width * (0.06f + i * 0.15f), size.height * top),
                        size = androidx.compose.ui.geometry.Size(w, size.height * (0.88f - top)),
                    )
                }
                listOf(0.18f, 0.44f, 0.30f).forEachIndexed { i, top ->
                    drawRect(
                        color = NeoColors.NeonPurple,
                        topLeft = Offset(size.width * (0.53f + i * 0.15f), size.height * top),
                        size = androidx.compose.ui.geometry.Size(w, size.height * (0.88f - top)),
                    )
                }
            }
        },
        GameEntry(
            title = stringResource(R.string.game_puzzle),
            subtitle = stringResource(R.string.game_puzzle_blurb),
            accent = NeoColors.NeonCyan,
            gameId = MiniGame.PUZZLE.id,
            route = Routes.GAME_PUZZLE,
        ) {
            Canvas(Modifier.size(52.dp)) {
                // A square in its hole, a triangle still looking for one.
                drawRect(
                    color = NeoColors.NeonCyan,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.10f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.34f, size.height * 0.34f),
                )
                drawRect(
                    color = NeoColors.NeonCyan.copy(alpha = 0.30f),
                    topLeft = Offset(size.width * 0.56f, size.height * 0.10f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.34f, size.height * 0.34f),
                    style = Stroke(width = size.minDimension * 0.05f),
                )
                val path = Path().apply {
                    moveTo(size.width * 0.30f, size.height * 0.58f)
                    lineTo(size.width * 0.52f, size.height * 0.92f)
                    lineTo(size.width * 0.08f, size.height * 0.92f)
                    close()
                }
                drawPath(path, NeoColors.NeonYellow)
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
                stringResource(R.string.games_title),
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
                title = stringResource(R.string.games_blocked_title),
                contentPadding = PaddingValues(pixelUnits(3)),
            ) {
                Text(blocker, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            Text(
                text = stringResource(R.string.games_blurb),
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
            stringResource(R.string.games_record, pet.gamesWon, pet.gamesPlayed),
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
                // `semantics {}` is not composition, so the sentence is read out here.
                val bestReadOut = stringResource(R.string.cd_best_score, best)
                PixelBadge(
                    text = "$best",
                    color = NeoColors.NeonYellow,
                    contentColor = NeoColors.OnLight,
                    modifier = Modifier.semantics { contentDescription = bestReadOut },
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
