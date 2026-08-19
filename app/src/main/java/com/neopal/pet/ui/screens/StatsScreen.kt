package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.neopal.pet.domain.Simulation
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.StatBar
import com.neopal.pet.ui.theme.NeoColors
import kotlin.math.roundToInt

/** The full read-out: every meter, the growth timer, and the care record behind evolutions. */
@Composable
fun StatsScreen(viewModel: PetViewModel, onBack: () -> Unit) {
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(pet.name.uppercase(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            "${pet.species.displayName} · ${pet.stage.displayName} · ${pet.branch.displayName} · ${pet.personality.displayName}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        SectionCard("Needs") {
            StatBar("Satiety", pet.stats.satiety, NeoColors.StatSatiety)
            Spacer(Modifier.height(10.dp))
            StatBar("Happiness", pet.stats.happiness, NeoColors.StatHappiness)
            Spacer(Modifier.height(10.dp))
            StatBar("Energy", pet.stats.energy, NeoColors.StatEnergy)
            Spacer(Modifier.height(10.dp))
            StatBar("Hygiene", pet.stats.hygiene, NeoColors.StatHygiene)
            Spacer(Modifier.height(10.dp))
            StatBar("Health", pet.stats.health, NeoColors.StatHealth)
            Spacer(Modifier.height(10.dp))
            StatBar("Discipline", pet.stats.discipline, NeoColors.StatDiscipline)
            Spacer(Modifier.height(10.dp))
            StatBar("Bond", pet.stats.bond, NeoColors.StatBond)
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("Growth") {
            InfoRow("Age", "${pet.ageInPetDays(config)} pet days")
            InfoRow("Stage progress", "${(Simulation.stageProgress(pet, config) * 100).roundToInt()} %")
            InfoRow("Weight", "${pet.weightGrams.roundToInt()} g")
            InfoRow("Generation", "#${pet.generation}")
            InfoRow("Care grade", careGrade(pet.stats.careScore))
            // Naming the exact next form turns raising a pet into reading a spec sheet.
            InfoRow("Leaning toward", branchHint(Simulation.decideBranch(pet)))
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("Record") {
            InfoRow("Meals served", "${pet.mealsEaten}")
            InfoRow("Clean-ups", "${pet.cleanups}")
            InfoRow("Games played", "${pet.gamesPlayed}")
            InfoRow("Games won", "${pet.gamesWon}")
            InfoRow("Praises", "${pet.praises}")
            InfoRow("Scoldings", "${pet.scolds}")
            InfoRow("Illnesses cured", "${pet.medicineDoses}")
            InfoRow("Care mistakes", "${pet.careMistakes}")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A hint about where the care history is pointing, without naming the form outright. */
private fun branchHint(branch: com.neopal.pet.domain.EvolutionBranch): String = when (branch) {
    com.neopal.pet.domain.EvolutionBranch.ATHLETIC -> "restless, always moving"
    com.neopal.pet.domain.EvolutionBranch.GOURMAND -> "fond of its meals"
    com.neopal.pet.domain.EvolutionBranch.SCHOLAR -> "attentive, well behaved"
    com.neopal.pet.domain.EvolutionBranch.FERAL -> "wary, left to itself"
    com.neopal.pet.domain.EvolutionBranch.BALANCED -> "even tempered"
}

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
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = NeoColors.NeonCyan)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
