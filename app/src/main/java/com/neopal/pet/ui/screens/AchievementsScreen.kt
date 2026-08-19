package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neopal.pet.R
import com.neopal.pet.domain.Achievement
import com.neopal.pet.domain.Achievements
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.NeoAccents

/** Trophy list with unlocked entries pulled to the top. */
@Composable
fun AchievementsScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val sorted = Achievements.all.sortedByDescending { it.id in pet.unlockedAchievements }

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
                    contentDescription = stringResource(R.string.nav_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                stringResource(R.string.awards_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            Spacer(Modifier.width(8.dp))
            val progressReadOut = stringResource(
                R.string.cd_awards_progress,
                pet.unlockedAchievements.size,
                Achievements.all.size,
            )
            Text(
                stringResource(R.string.awards_progress, pet.unlockedAchievements.size, Achievements.all.size),
                style = MaterialTheme.typography.labelMedium,
                // Straight neon cyan is 1.9:1 on the light theme, so it steps down there.
                color = NeoAccents.cyan,
                maxLines = 1,
                modifier = Modifier.semantics { contentDescription = progressReadOut },
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn {
            items(sorted) { achievement ->
                AchievementRow(
                    achievement = achievement,
                    unlocked = achievement.id in pet.unlockedAchievements,
                )
            }
        }
    }
}

@Composable
private fun AchievementRow(achievement: Achievement, unlocked: Boolean) {
    // One row is one fact — locked or not, what it is, what it pays — so it reads as one node.
    val readOut = stringResource(
        if (unlocked) R.string.cd_award_unlocked else R.string.cd_award_locked,
        achievement.title,
        achievement.description,
        achievement.rewardCoins,
    )
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = if (unlocked) Icons.Filled.EmojiEvents else Icons.Filled.Lock,
                contentDescription = null,
                tint = if (unlocked) NeoAccents.gold else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(10.dp))
            // The row has no fixed height, so at a 1.3x font scale it grows instead of clipping;
            // the caps stop one long description from pushing the reward off the edge.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = achievement.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = achievement.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.awards_reward, achievement.rewardCoins),
                style = MaterialTheme.typography.labelMedium,
                color = if (unlocked) NeoAccents.green else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
