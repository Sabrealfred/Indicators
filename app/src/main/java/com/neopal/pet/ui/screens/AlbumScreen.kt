package com.neopal.pet.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.neopal.pet.R
import com.neopal.pet.domain.AlbumEntry
import com.neopal.pet.domain.Mood
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.Palettes
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.pixelShine
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits

/**
 * The family album. Every evolution files itself here automatically, and photo mode
 * (long-press the pet on the home screen) adds a shot of exactly how it looks right now.
 */
@Composable
fun AlbumScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    // What was already on the shelf when the player walked in. Anything that appears afterwards is
    // a shot they just took, and that card — only that card — is worth a sweep of light.
    val onArrival = remember { pet.album.mapTo(HashSet()) { it.id } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = pixelUnits(3)),
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
                stringResource(R.string.album_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            Spacer(Modifier.width(pixelUnits(2)))
            // PixelButton claims the 48dp touch minimum itself, which a Material button does not.
            PixelButton(
                onClick = { viewModel.snapshot("${pet.name}, ${pet.stage.displayName}") },
                accent = NeoAccents.gold,
            ) {
                Text(
                    stringResource(R.string.album_take_photo),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(pixelUnits(2)))

        if (pet.album.isEmpty()) {
            Column(modifier = Modifier.padding(top = pixelUnits(10))) {
                Text(
                    stringResource(R.string.album_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(pixelUnits(2)))
                Text(
                    stringResource(R.string.album_empty_body, pet.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(pixelUnits(3)),
                horizontalArrangement = Arrangement.spacedBy(pixelUnits(3)),
            ) {
                items(pet.album.reversed()) { entry ->
                    AlbumCard(entry, fresh = entry.id !in onArrival)
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(entry: AlbumEntry, fresh: Boolean) {
    val room = Palettes.room(entry.roomTheme)
    // The drawing carries no information the caption does not, so the whole card is one node.
    val readOut = stringResource(
        R.string.cd_album_card,
        entry.title,
        entry.stage.displayName,
        entry.branch.displayName,
    )
    // A photograph in a pixel game belongs in a frame: brass moulding, the shot pressed into
    // the mount, and the caption on the plate along the top.
    val frame = NeoAccents.gold
    PixelPanel(
        modifier = Modifier
            .semantics(mergeDescendants = true) { contentDescription = readOut }
            // Over the whole card, brass and all, so a new photograph catches the light the way a
            // print does when you tilt it.
            .pixelShine(enabled = fresh, color = frame),
        accent = frame,
        title = entry.title,
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(pixelUnits(30))
                .pixelSurface(fill = room.wallBottom, accent = frame, bevel = PixelBevel.PRESSED)
                // A unit past the border, so the mount's bevel stays visible under the picture.
                .padding(pixelUnits(3)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // A still frame of the pet against the room it lived in.
                drawRect(room.wallBottom)
                drawRect(
                    color = room.floor,
                    topLeft = Offset(0f, size.height * 0.72f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.28f),
                )
                drawCreature(
                    center = Offset(size.width / 2f, size.height * 0.62f),
                    unit = size.minDimension,
                    spec = CreatureSpec(
                        species = entry.species,
                        stage = entry.stage,
                        branch = entry.branch,
                        mood = Mood.HAPPY,
                        hatId = entry.hatId,
                    ),
                    frame = CreatureFrame(mouthOpen = 0.35f),
                )
            }
        }
        Spacer(Modifier.height(pixelUnits(2)))
        Text(
            stringResource(R.string.album_caption, entry.stage.displayName, entry.branch.displayName),
            style = MaterialTheme.typography.labelSmall,
            // Was NeoColors.OnDarkMuted, a fixed dark-theme grey that drops to 2.4:1 on the light theme.
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
