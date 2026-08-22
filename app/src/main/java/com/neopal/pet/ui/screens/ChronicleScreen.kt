package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontStyle
import com.neopal.pet.domain.ChronicleEntry
import com.neopal.pet.domain.ChronicleKind
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.ui.theme.NeoColors

/**
 * The diary. Meters say what the pet needs; the diary says what the pet went through, in its
 * own voice — including the days you were not there. It is the memory the player is really
 * playing for.
 */
@Composable
fun ChronicleScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val entries = pet.chronicle.reversed()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Edge to edge: keep the content out of the status and gesture bars. The
            // background is applied first on purpose, so it still bleeds under them.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = pixelUnits(3)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column {
                Text(
                    "DIARY",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "${pet.name}'s own words · ${entries.size} entries",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(pixelUnits(3)))

        if (entries.isEmpty()) {
            Column(modifier = Modifier.padding(top = pixelUnits(10))) {
                Text(
                    "The first page is blank",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(pixelUnits(2)))
                Text(
                    "${pet.name} writes this itself — the good days and the ones where nobody came. " +
                        "Give it a life worth writing about.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn {
            items(entries) { entry -> ChronicleRow(entry) }
        }
    }
}

@Composable
private fun ChronicleRow(entry: ChronicleEntry) {
    val accent = when (entry.kind) {
        ChronicleKind.MILESTONE -> NeoColors.NeonCyan
        ChronicleKind.CARE -> NeoColors.StatHygiene
        ChronicleKind.TROUBLE -> NeoColors.NeonRed
        ChronicleKind.JOY -> NeoColors.NeonYellow
        ChronicleKind.LOSS -> NeoColors.OnDarkMuted
    }
    val background = MaterialTheme.colorScheme.background
    val fill = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier = Modifier.fillMaxWidth()) {
        // A timeline rail, so a run reads as one continuous life rather than a list of rows.
        // Squared node, whole-unit thread: the same grid the panel beside it is cut from.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(pixelUnits(7)),
        ) {
            Spacer(Modifier.height(pixelUnits(2)))
            Box(
                modifier = Modifier
                    .size(pixelUnits(3))
                    .pixelSurface(
                        fill = accent,
                        accent = accent,
                        bevel = PixelBevel.FLAT,
                        borderUnits = 1,
                        background = background,
                    ),
            )
            Box(
                modifier = Modifier
                    .width(pixelUnits(1))
                    .height(pixelUnits(14))
                    .background(lerp(background, accent, 0.35f)),
            )
        }
        PixelPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = pixelUnits(2)),
            fill = fill,
            // The entry wears the colour of what happened, edge and title plate alike.
            accent = accent,
            background = background,
            title = "Day ${entry.petDay}",
            contentPadding = PaddingValues(pixelUnits(3)),
        ) {
            Text(
                text = "“${entry.text}”",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = if (entry.kind == ChronicleKind.LOSS) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
