package com.neopal.pet.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neopal.pet.R
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.Simulation
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.StatBar
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.roundToInt

/** The full read-out: every meter, the growth timer, and the care record behind evolutions. */
@Composable
fun StatsScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val config = ui.config

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
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
                pet.name.uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { renaming = true }
                    .semantics { heading() },
            )
            IconButton(onClick = { renaming = true }) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Rename ${pet.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "${pet.species.displayName} · ${pet.stage.displayName} · ${pet.branch.displayName} · ${pet.personality.displayName}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        SectionCard(stringResource(R.string.stats_section_needs)) {
            StatBar(stringResource(R.string.stat_satiety), pet.stats.satiety, NeoColors.StatSatiety)
            Spacer(Modifier.height(10.dp))
            StatBar(stringResource(R.string.stat_happiness), pet.stats.happiness, NeoColors.StatHappiness)
            Spacer(Modifier.height(10.dp))
            StatBar(stringResource(R.string.stat_energy), pet.stats.energy, NeoColors.StatEnergy)
            Spacer(Modifier.height(10.dp))
            StatBar(stringResource(R.string.stat_hygiene), pet.stats.hygiene, NeoColors.StatHygiene)
            Spacer(Modifier.height(10.dp))
            StatBar(stringResource(R.string.stat_health), pet.stats.health, NeoColors.StatHealth)
            Spacer(Modifier.height(10.dp))
            StatBar(stringResource(R.string.stat_discipline), pet.stats.discipline, NeoColors.StatDiscipline)
            Spacer(Modifier.height(10.dp))
            StatBar(stringResource(R.string.stat_bond), pet.stats.bond, NeoColors.StatBond)
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(stringResource(R.string.stats_section_growth)) {
            InfoRow(
                stringResource(R.string.stats_age),
                stringResource(R.string.stats_age_value, pet.ageInPetDays(config)),
            )
            InfoRow(
                stringResource(R.string.stats_stage_progress),
                stringResource(
                    R.string.stats_percent_value,
                    (Simulation.stageProgress(pet, config) * 100).roundToInt(),
                ),
            )
            InfoRow(
                stringResource(R.string.stats_weight),
                stringResource(R.string.stats_weight_value, pet.weightGrams.roundToInt()),
            )
            InfoRow(
                stringResource(R.string.stats_generation),
                stringResource(R.string.stats_generation_value, pet.generation),
            )
            // A bare letter grade is a riddle; the hint says which numbers it is averaging.
            InfoRow(
                stringResource(R.string.stats_care_grade),
                careGrade(pet.stats.careScore),
                hint = stringResource(R.string.stats_care_grade_hint),
            )
            // Naming the exact next form turns raising a pet into reading a spec sheet.
            InfoRow(
                stringResource(R.string.stats_leaning_toward),
                branchHint(Simulation.decideBranch(pet)),
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(stringResource(R.string.stats_section_record)) {
            InfoRow(stringResource(R.string.stats_record_meals), "${pet.mealsEaten}")
            InfoRow(stringResource(R.string.stats_record_cleanups), "${pet.cleanups}")
            InfoRow(stringResource(R.string.stats_record_games_played), "${pet.gamesPlayed}")
            InfoRow(stringResource(R.string.stats_record_games_won), "${pet.gamesWon}")
            InfoRow(stringResource(R.string.stats_record_praises), "${pet.praises}")
            InfoRow(stringResource(R.string.stats_record_scoldings), "${pet.scolds}")
            InfoRow(stringResource(R.string.stats_record_cures), "${pet.medicineDoses}")
            InfoRow(stringResource(R.string.stats_record_mistakes), "${pet.careMistakes}")
        }
        Spacer(Modifier.height(24.dp))
    }

    if (renaming) {
        RenameDialog(
            current = pet.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                viewModel.rename(name)
                renaming = false
            },
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var draft by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 12) draft = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Twelve characters. It keeps everything else — the diary is still its diary.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = draft.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A hint about where the care history is pointing, without naming the form outright. */
@Composable
private fun branchHint(branch: EvolutionBranch): String = stringResource(
    when (branch) {
        EvolutionBranch.ATHLETIC -> R.string.branch_hint_athletic
        EvolutionBranch.GOURMAND -> R.string.branch_hint_gourmand
        EvolutionBranch.SCHOLAR -> R.string.branch_hint_scholar
        EvolutionBranch.FERAL -> R.string.branch_hint_feral
        EvolutionBranch.BALANCED -> R.string.branch_hint_balanced
    },
)

// Grade letters are symbols, not prose, so they stay out of strings.xml.
private fun careGrade(score: Float): String = when {
    score >= 0.9f -> "S"
    score >= 0.78f -> "A"
    score >= 0.62f -> "B"
    score >= 0.45f -> "C"
    score >= 0.3f -> "D"
    else -> "E"
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The neon accent moved off the text and onto a rule: cyan type on the light
                // theme sits at 1.9:1, the same cyan as a 3dp bar only has to clear 3:1.
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(NeoAccents.cyan),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * Label on the left, value on the right. Both halves carry weight so a 1.3x font scale wraps
 * the row instead of squeezing the value off the edge.
 */
@Composable
private fun InfoRow(label: String, value: String, hint: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            // Label, value and hint are one fact; TalkBack should say it in one breath.
            .semantics(mergeDescendants = true) { },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
