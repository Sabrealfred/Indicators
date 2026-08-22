package com.neopal.pet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.neopal.pet.R
import com.neopal.pet.domain.Achievement
import com.neopal.pet.domain.Achievements
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBadge
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits
import com.neopal.pet.ui.theme.NeoColors

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
                stringResource(R.string.awards_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            Spacer(Modifier.width(pixelUnits(2)))
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
        Spacer(Modifier.height(pixelUnits(2)))

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
    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surfaceVariant
    // Locked rows are sunk into the page with a washed-out edge: the shape says "not yet"
    // before the padlock does, which is the half of the message an icon alone cannot carry.
    val accent = if (unlocked) NeoAccents.gold else lerp(NeoAccents.gold, background, 0.70f)
    val fill = if (unlocked) surface else lerp(surface, background, 0.45f)
    val badgeColor = if (unlocked) NeoAccents.green else lerp(surface, background, 0.20f)
    val badgeInk = when {
        !unlocked -> MaterialTheme.colorScheme.onSurfaceVariant
        badgeColor.luminance() > 0.4f -> NeoColors.ChassisBlack
        else -> NeoColors.OnDark
    }

    PixelPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = pixelUnits(1))
            .semantics(mergeDescendants = true) { contentDescription = readOut },
        fill = fill,
        accent = accent,
        background = background,
        bevel = if (unlocked) PixelBevel.RAISED else PixelBevel.PRESSED,
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(pixelUnits(10))
                    .pixelSurface(
                        fill = lerp(fill, accent, if (unlocked) 0.24f else 0.10f),
                        accent = accent,
                        bevel = PixelBevel.PRESSED,
                        borderUnits = 1,
                        background = fill,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (unlocked) Icons.Filled.EmojiEvents else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (unlocked) NeoAccents.gold else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(pixelUnits(6)),
                )
            }
            Spacer(Modifier.width(pixelUnits(3)))
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
            Spacer(Modifier.width(pixelUnits(2)))
            PixelBadge(
                text = stringResource(R.string.awards_reward, achievement.rewardCoins),
                color = badgeColor,
                contentColor = badgeInk,
                background = fill,
            )
        }
    }
}
