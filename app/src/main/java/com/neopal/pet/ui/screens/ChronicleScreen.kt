package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.ChronicleEntry
import com.neopal.pet.domain.ChronicleKind
import com.neopal.pet.ui.PetViewModel
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
            .padding(horizontal = 12.dp),
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
        Spacer(Modifier.height(10.dp))

        if (entries.isEmpty()) {
            Column(modifier = Modifier.padding(top = 40.dp)) {
                Text(
                    "The first page is blank",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
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
    Row(modifier = Modifier.fillMaxWidth()) {
        // A timeline rail, so a run reads as one continuous life rather than a list of rows.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(58.dp)
                    .background(accent.copy(alpha = 0.25f)),
            )
        }
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "DAY ${entry.petDay}",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
                Spacer(Modifier.height(4.dp))
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
}
