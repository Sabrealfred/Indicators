package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.Color
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
import com.neopal.pet.domain.EvolutionBranch
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.RunRecord
import com.neopal.pet.domain.Simulation
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBadge
import com.neopal.pet.ui.components.PixelBar
import com.neopal.pet.ui.components.PixelDigits
import com.neopal.pet.ui.components.PixelDivider
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.StatBar
import com.neopal.pet.ui.components.dimmedFor
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.ui.components.rememberWindowSize
import com.neopal.pet.ui.theme.NeoColors
import java.util.Locale
import kotlin.math.roundToInt

/** The full read-out: every meter, the growth timer, and the care record behind evolutions. */
@Composable
fun StatsScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val config = ui.config
    val window = rememberWindowSize()

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
                    GenerationsCard(pet, config)
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
                GenerationsCard(pet, config)
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
 * This pet against the last one. The point of a second generation is finding out whether you have
 * got any better at this, so the comparison runs on the record the simulation sealed when the last
 * pet died — not on a silhouette pieced together from whichever milestones happened to be
 * photographed.
 */
@Composable
private fun GenerationsCard(pet: PetState, config: GameConfig) {
    if (pet.generation <= 1) return
    val last = pet.previousGenerations.lastOrNull()
    PixelPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = NeoAccents.cyan,
        title = "Generations",
        titleTrailing = {
            PixelBadge(
                text = "${pet.generation}",
                color = NeoAccents.cyan,
                contentDescription = "Generation ${pet.generation}",
            )
        },
    ) {
        if (last == null) {
            // A save carried over from a build that kept no history has nothing here and never
            // will. A table of zeroes would read as a generation that did nothing at all.
            val missing = pet.generation - 1
            val lead = if (missing == 1) {
                "The generation before this one was never written down"
            } else {
                "The $missing generations before this one were never written down"
            }
            Text(
                "$lead — this save is older than the log. Generation ${pet.generation} is being " +
                    "recorded, so the pet after it will have something to be measured against.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@PixelPanel
        }

        val nowDays = pet.ageInPetDays(config)
        val thenDays = (last.lifespanSeconds / config.secondsPerPetDay).toInt()
        // Raw mistake counts reward a pet for dying young, so both sides are rated per hour lived.
        val nowMistakes = pet.careMistakes / (pet.ageSeconds / 3600f).coerceAtLeast(1f)

        if (!pet.isDead) {
            // A finished life against a life in progress: the totals are not a fair race yet, and
            // a green number on an unfinished run should not be read as one.
            Text(
                "Generation ${last.generation} is a finished life. This one is still being lived, " +
                    "so its counts are still filling in.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(pixelUnits(2)))
        }
        CompareHeader(now = "Now", then = "Gen ${last.generation}")
        CompareDigits("Pet days", "$nowDays", "$thenDays", ahead = nowDays > thenDays)
        CompareText(
            label = "Stage",
            now = pet.stage.displayName,
            then = last.stage.displayName,
            ahead = pet.stage.order > last.stage.order,
        )
        // The growth card grades the stats as they stand this second; this one grades the whole
        // life, which is the only way two finished runs can be held against each other.
        CompareText(
            label = "Lifetime care",
            now = careGrade(pet.lifetimeCareScore),
            then = careGrade(last.careScore),
            ahead = pet.lifetimeCareScore > last.careScore,
        )
        CompareBars("Care", pet.lifetimeCareScore, last.careScore, NeoColors.StatHealth, last.generation)
        CompareDigits(
            label = "Mistakes per hour",
            now = oneDecimal(nowMistakes),
            then = oneDecimal(last.mistakesPerHour),
            ahead = nowMistakes < last.mistakesPerHour,
        )
        CompareDigits("Meals", "${pet.mealsEaten}", "${last.mealsEaten}", ahead = pet.mealsEaten > last.mealsEaten)
        CompareDigits(
            label = "Games won",
            now = "${pet.gamesWon}",
            then = "${last.gamesWon}",
            ahead = pet.gamesWon > last.gamesWon,
        )
        CompareBars("Peak bond", pet.peakBond / 100f, last.peakBond / 100f, NeoColors.StatBond, last.generation)

        Spacer(Modifier.height(pixelUnits(2)))
        PixelDivider()
        Spacer(Modifier.height(pixelUnits(2)))
        Ending(last)
        Spacer(Modifier.height(pixelUnits(2)))
        Text(
            text = verdict(pet, last, nowDays, thenDays),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How the last run ended — the one line of it this pet cannot be compared against. */
@Composable
private fun Ending(last: RunRecord) {
    val badge = last.deathReason?.displayName ?: "Unfinished"
    val who = "${last.name} · ${last.species.displayName} · " +
        "${last.branch.displayName} · ${last.personality.displayName}"
    val sentence = when {
        last.diedOfOldAge -> "A whole life, ended by nothing but time."
        last.deathReason != null -> "It never saw the end of ${last.stage.displayName}."
        else -> "Replaced rather than lost."
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Ending. $badge. $who. $sentence" },
    ) {
        PixelBadge(
            text = badge.uppercase(),
            // Old age is the one ending that is not a failure, so it does not get the alarm colour.
            color = if (last.diedOfOldAge) NeoAccents.gold else MaterialTheme.colorScheme.error,
            contentDescription = "",
        )
        Spacer(Modifier.height(pixelUnits(2)))
        Text(
            who,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            sentence,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One honest sentence about the comparison, including "too early to tell". */
private fun verdict(pet: PetState, last: RunRecord, nowDays: Int, thenDays: Int): String = when {
    pet.stage.order > last.stage.order ->
        "${pet.name} has already outgrown generation ${last.generation}."
    pet.stage.order < last.stage.order ->
        "Generation ${last.generation} reached ${last.stage.displayName}. ${pet.name} is not there yet."
    nowDays < thenDays ->
        "Same stage as generation ${last.generation}, and ${thenDays - nowDays} pet day(s) quicker about it."
    nowDays > thenDays ->
        "Same stage as generation ${last.generation}, ${nowDays - thenDays} pet day(s) later."
    else ->
        "Neck and neck with generation ${last.generation} so far."
}

@Composable
private fun CompareHeader(now: String, then: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = pixelUnits(1))
            // Every row below names both sides in full, so the header would only repeat itself.
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(pixelUnits(2)),
    ) {
        Spacer(Modifier.weight(CompareLabelWeight))
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

/** Wide enough for "Mistakes per hour" to wrap in two lines instead of eating a value column. */
private const val CompareLabelWeight = 1.2f

/**
 * Label, this run, last run. All three carry weight so a long branch name or a 1.3x font scale
 * wraps the row instead of pushing the previous generation off the card.
 */
@Composable
private fun CompareText(label: String, now: String, then: String, ahead: Boolean = false) {
    CompareRow(label, "$label. This generation $now. Generation before $then.") {
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
 * The numeric twin of [CompareText], drawn in the kit's numerals. Digits are a fixed-size canvas,
 * so only short readouts belong here — a value that can run to five characters stays as text.
 */
@Composable
private fun CompareDigits(label: String, now: String, then: String, ahead: Boolean = false) {
    CompareRow(label, "$label. This generation $now. Generation before $then.") {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            PixelDigits(now, color = if (ahead) NeoAccents.green else MaterialTheme.colorScheme.onSurface)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            PixelDigits(then, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CompareRow(label: String, readOut: String, values: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = pixelUnits(1))
            .semantics(mergeDescendants = true) { contentDescription = readOut },
        horizontalArrangement = Arrangement.spacedBy(pixelUnits(2)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(CompareLabelWeight),
        )
        values()
    }
}

/**
 * The same 0..1 measure twice, this run over the last. Two stacked meters read as one comparison
 * where two numbers side by side read as two separate facts.
 */
@Composable
private fun CompareBars(label: String, now: Float, then: Float, color: Color, generation: Int) {
    val background = MaterialTheme.colorScheme.background
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = pixelUnits(1))
            .semantics(mergeDescendants = true) {
                contentDescription = "$label. This generation ${percent(now)} percent. " +
                    "Generation $generation ${percent(then)} percent."
            },
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(pixelUnits(1)))
        PixelBar(fraction = now, color = color, height = pixelUnits(4))
        Spacer(Modifier.height(pixelUnits(1)))
        // The past run sits behind the present one in weight as well as in order.
        PixelBar(
            fraction = then,
            color = dimmedFor(color, background),
            height = pixelUnits(2),
            background = background,
        )
    }
}

private fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).roundToInt()

/** The pixel font carries a full stop and no comma, so the decimal mark cannot follow the locale. */
private fun oneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)

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
