package com.neopal.pet.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.AlbumEntry
import com.neopal.pet.domain.Mood
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.art.CreatureFrame
import com.neopal.pet.ui.art.CreatureSpec
import com.neopal.pet.ui.art.Palettes
import com.neopal.pet.ui.art.drawCreature
import com.neopal.pet.ui.theme.NeoColors

/**
 * The family album. Every evolution files itself here automatically, and photo mode
 * (long-press the pet on the home screen) adds a shot of exactly how it looks right now.
 */
@Composable
fun AlbumScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("ALBUM", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            Button(onClick = { viewModel.snapshot("${pet.name}, ${pet.stage.displayName}") }) {
                Text("Take a photo")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (pet.album.isEmpty()) {
            Column(modifier = Modifier.padding(top = 40.dp)) {
                Text(
                    "Nothing here yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Every evolution files itself here on its own. For everything in between, " +
                        "hold your finger on ${pet.name} and the moment gets kept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pet.album.reversed()) { entry -> AlbumCard(entry) }
            }
        }
    }
}

@Composable
private fun AlbumCard(entry: AlbumEntry) {
    val room = Palettes.room(entry.roomTheme)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp)),
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
            Spacer(Modifier.height(6.dp))
            Text(entry.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "${entry.stage.displayName} · ${entry.branch.displayName}",
                style = MaterialTheme.typography.labelSmall,
                color = NeoColors.OnDarkMuted,
            )
        }
    }
}
