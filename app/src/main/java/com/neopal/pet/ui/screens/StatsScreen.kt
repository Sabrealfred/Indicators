package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neopal.pet.R
import com.neopal.pet.domain.AlbumEntry
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.LifeStage
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.Simulation
import com.neopal.pet.domain.Species
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.StatBar
import com.neopal.pet.ui.components.rememberWindowSize
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.roundToInt

/**
 * What a past pet managed, rebuilt from the album. The save file keeps no per-generation record,
 * and the album is the one thing that survives a death — so it is the only witness there is.
 */
private data class RunSummary(
    val stage: LifeStage,
    val branch: EvolutionBranch,
    val species: Species,
    val ageSeconds: Long,
)

/** The full read-out: every meter, the growth timer, and the care record behind evolutions. */
@Composable
fun StatsScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val config = ui.config
    val window = rememberWindowSize()
    val previous = remember(pet.album, pet.bornAtMillis) { previousRun(pet.album, pet.bornAtMillis) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The app draws edge to edge; without this the back button sits under the status bar.
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
                    // The name is a shortcut to the same dialog as the pencil, so it says so.
                    .clickable(onClickLabel = "Rename", role = Role.Button) { renaming = true }
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

        // A wide window gets two columns: one tall ribbon of cards down the middle of a tablet
        // is a phone layout that was never re-thought.
        if (window.isTwoPane) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NeedsCard(pet)
                    GrowthCard(pet, config)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GenerationsCard(pet, config, previous)
                    RecordCard(pet)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NeedsCard(pet)
                GrowthCard(pet, config)
                GenerationsCard(pet, config, previous)
                RecordCard(pet)
            }
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
private fun NeedsCard(pet: PetState) {
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
}

@Composable
private fun GrowthCard(pet: PetState, config: GameConfig) {
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
}

@Composable
private fun RecordCard(pet: PetState) {
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
}

/**
 * This pet against the last one. The point of a second generation is finding out whether you
 * have got any better at this, and that question needs the previous run standing next to it.
 */
@Composable
private fun GenerationsCard(pet: PetState, config: GameConfig, previous: RunSummary?) {
    if (pet.generation <= 1) return
    val last = pet.generation - 1
    SectionCard("Generations") {
        if (previous == null) {
            Text(
                "Generation $last left no photos behind, so there is nothing to compare against. " +
                    "Every evolution files itself in the album on its own — this one will have a record.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val nowDays = pet.ageInPetDays(config)
            val thenDays = (previous.ageSeconds / config.secondsPerPetDay).toInt()
            CompareHeader(now = "Now", then = "Gen $last")
            CompareRow(
                label = stringResource(R.string.stats_age),
                now = stringResource(R.string.stats_age_value, nowDays),
                then = stringResource(R.string.stats_age_value, thenDays),
                ahead = nowDays > thenDays,
            )
            CompareRow(
                label = "Stage",
                now = pet.stage.displayName,
                then = previous.stage.displayName,
                ahead = pet.stage.order > previous.stage.order,
            )
            CompareRow(label = "Branch", now = pet.branch.displayName, then = previous.branch.displayName)
            CompareRow(label = "Species", now = pet.species.displayName, then = previous.species.displayName)
            Spacer(Modifier.height(8.dp))
            Text(
                text = verdict(pet, previous, nowDays, thenDays, last),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One honest sentence about the comparison, including "too early to tell". */
private fun verdict(pet: PetState, previous: RunSummary, nowDays: Int, thenDays: Int, last: Int): String = when {
    pet.stage.order > previous.stage.order ->
        "${pet.name} has already outgrown generation $last."
    pet.stage.order < previous.stage.order ->
        "Generation $last reached ${previous.stage.displayName}. ${pet.name} is not there yet."
    nowDays < thenDays ->
        "Same stage as generation $last, and ${thenDays - nowDays} pet day(s) quicker about it."
    nowDays > thenDays ->
        "Same stage as generation $last, ${nowDays - thenDays} pet day(s) later."
    else ->
        "Neck and neck with generation $last so far."
}

@Composable
private fun CompareHeader(now: String, then: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            // Every row below names both sides in full, so the header would only repeat itself.
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.weight(1.1f))
        Text(
            now.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            then.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Label, this run, last run. All three carry weight so a long species name or a 1.3x font scale
 * wraps the row instead of pushing the previous generation off the card.
 */
@Composable
private fun CompareRow(label: String, now: String, then: String, ahead: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label. This generation $now. Generation before $then."
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.1f),
        )
        Text(
            now,
            style = MaterialTheme.typography.bodyMedium,
            // Ahead of the last run is worth a colour, but the sentence underneath says it too:
            // colour is never the only carrier of the comparison.
            color = if (ahead) NeoAccents.green else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            then,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Rebuilds the run that came before this one. Album entries are kept across generations and each
 * new pet restarts its own clock, so an age that jumps backwards is exactly where one life ended.
 */
private fun previousRun(album: List<AlbumEntry>, bornAtMillis: Long): RunSummary? {
    if (bornAtMillis <= 0L) return null
    val earlier = album
        .filter { it.capturedAtMillis in 1 until bornAtMillis }
        .sortedBy { it.capturedAtMillis }
    if (earlier.isEmpty()) return null

    var current = mutableListOf<AlbumEntry>()
    var previousAge = -1L
    earlier.forEach { entry ->
        if (entry.petAgeSeconds < previousAge) current = mutableListOf()
        current += entry
        previousAge = entry.petAgeSeconds
    }
    val furthest = current.maxByOrNull { it.stage.order } ?: return null
    return RunSummary(
        stage = furthest.stage,
        branch = furthest.branch,
        species = furthest.species,
        ageSeconds = current.maxOf { it.petAgeSeconds },
    )
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
