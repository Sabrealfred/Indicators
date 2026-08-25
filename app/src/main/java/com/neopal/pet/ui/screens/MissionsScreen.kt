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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.neopal.pet.R
import com.neopal.pet.domain.MissionProgress
import com.neopal.pet.domain.Missions
import com.neopal.pet.ui.PetViewModel
import com.neopal.pet.ui.components.NeoAccents
import com.neopal.pet.ui.components.PixelBadge
import com.neopal.pet.ui.components.PixelBar
import com.neopal.pet.ui.components.PixelBevel
import com.neopal.pet.ui.components.PixelButton
import com.neopal.pet.ui.components.PixelDigits
import com.neopal.pet.ui.components.PixelDivider
import com.neopal.pet.ui.components.PixelPanel
import com.neopal.pet.ui.components.dimmedFor
import com.neopal.pet.ui.components.pixelShine
import com.neopal.pet.ui.components.pixelSurface
import com.neopal.pet.ui.components.pixelUnits
import kotlinx.coroutines.delay

/** What the last tap on collect paid, held only long enough to be seen. */
private data class Collected(val missions: Int, val coins: Int, val xp: Int)

/** How long the payout banner stays up before the screen goes quiet again. */
private const val COLLECTED_BANNER_MILLIS = 3200L

/**
 * Today's three missions and the care streak.
 *
 * Every other screen in this app is a meter that starts falling again the moment it is topped
 * up. This one has a bottom of the page: three goals that finish, and a button that pays out and
 * then says so. Collect is deliberately the loudest control here — it is the only beat in the
 * game that closes, and a payout that slips by unnoticed spends it for nothing.
 */
@Composable
fun MissionsScreen(viewModel: PetViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val pet = ui.pet ?: return
    val missions = viewModel.missions()

    val claimable = missions.filter { it.claimable }
    val pendingCoins = claimable.sumOf { it.mission.rewardCoins }
    val pendingXp = claimable.sumOf { it.mission.rewardXp }
    val finished = missions.count { it.complete }

    // Held here rather than read back off the state: once claimMissions runs, the reward is
    // folded into the coin total and there is nothing left to point at and say "that was this".
    var collected by remember { mutableStateOf<Collected?>(null) }
    LaunchedEffect(collected) {
        if (collected != null) {
            delay(COLLECTED_BANNER_MILLIS)
            collected = null
        }
    }

    val secondsPerDay = ui.config.secondsPerPetDay
    val secondsLeft = if (secondsPerDay > 0L) secondsPerDay - (pet.ageSeconds % secondsPerDay) else 0L

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
                    contentDescription = stringResource(R.string.nav_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.missions_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.missions_day, pet.ageInPetDays(ui.config), remaining(secondsLeft)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(pixelUnits(2)))

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            PixelPanel(
                modifier = Modifier.fillMaxWidth(),
                accent = NeoAccents.cyan,
                title = stringResource(R.string.missions_today),
                contentPadding = PaddingValues(pixelUnits(2)),
                titleTrailing = {
                    PixelBadge(
                        text = stringResource(R.string.fraction, finished, missions.size),
                        color = if (finished == missions.size) NeoAccents.green else MaterialTheme.colorScheme.surface,
                        contentDescription = stringResource(R.string.cd_missions_finished, finished, missions.size),
                    )
                },
            ) {
                missions.forEachIndexed { index, progress ->
                    if (index > 0) {
                        Spacer(Modifier.height(pixelUnits(2)))
                        PixelDivider()
                        Spacer(Modifier.height(pixelUnits(2)))
                    }
                    MissionRow(progress)
                }

                Spacer(Modifier.height(pixelUnits(3)))
                CollectButton(
                    ready = claimable.size,
                    coins = pendingCoins,
                    xp = pendingXp,
                    allCollected = missions.isNotEmpty() && missions.all { it.claimed },
                    onCollect = {
                        collected = Collected(claimable.size, pendingCoins, pendingXp)
                        viewModel.claimMissions()
                    },
                )

                collected?.let { payout ->
                    Spacer(Modifier.height(pixelUnits(2)))
                    CollectedBanner(payout)
                }
            }

            Spacer(Modifier.height(pixelUnits(3)))
            StreakPanel(
                days = pet.careStreakDays,
                best = pet.bestCareStreak,
                allDoneToday = finished == missions.size && missions.isNotEmpty(),
            )
            Spacer(Modifier.height(pixelUnits(4)))
        }
    }
}

/** One goal: what it is, how far along it is, and what it pays. */
@Composable
private fun MissionRow(progress: MissionProgress, modifier: Modifier = Modifier) {
    val mission = progress.mission
    val panel = MaterialTheme.colorScheme.surfaceVariant
    val accent = when {
        progress.claimed -> NeoAccents.green
        progress.complete -> NeoAccents.gold
        else -> NeoAccents.cyan
    }
    // Words, not just hues: "Collected" and "Ready to collect" are the whole state, said out loud.
    val state = when {
        progress.claimed -> stringResource(R.string.missions_state_collected)
        progress.complete -> stringResource(R.string.missions_state_ready)
        else -> ""
    }
    val readOut = state + stringResource(
        R.string.cd_mission_row,
        mission.title,
        mission.description,
        progress.done,
        mission.target,
        mission.rewardCoins,
        mission.rewardXp,
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = readOut },
    ) {
        // A finished goal gets a filled tick; an unfinished one is an empty slot. The shape
        // carries it on its own, before any colour does.
        Box(
            modifier = Modifier
                .size(pixelUnits(9))
                .pixelSurface(
                    fill = lerp(panel, accent, if (progress.complete) 0.24f else 0.08f),
                    accent = accent,
                    bevel = PixelBevel.PRESSED,
                    borderUnits = 1,
                    background = panel,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (progress.complete) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(pixelUnits(5)),
                )
            }
        }
        Spacer(Modifier.width(pixelUnits(2)))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mission.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = mission.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(pixelUnits(1)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One cell per unit of work, so "2 of 3 meals" is countable rather than estimated.
                PixelBar(
                    fraction = progress.fraction,
                    color = accent,
                    segments = mission.target.coerceIn(1, 20),
                    height = pixelUnits(4),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(pixelUnits(2)))
                PixelDigits(
                    text = stringResource(R.string.fraction, progress.done, mission.target),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(pixelUnits(2)))

        Column(horizontalAlignment = Alignment.End) {
            if (progress.claimed) {
                Text(
                    text = stringResource(R.string.missions_collected),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            } else {
                PixelBadge(
                    text = stringResource(R.string.plus_value, mission.rewardCoins),
                    color = if (progress.complete) NeoAccents.gold else dimmedFor(NeoAccents.gold, panel, 0.55f),
                    background = panel,
                    contentDescription = "",
                )
                Spacer(Modifier.height(pixelUnits(1)))
                Text(
                    text = stringResource(R.string.plus_xp, mission.rewardXp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The payout. It is a full-width button rather than a link in a corner because collecting is the
 * one thing this screen exists for, and it says what it is about to pay before it is pressed —
 * a button that promises only "claim" is asking the player to take it on faith.
 */
@Composable
private fun CollectButton(
    ready: Int,
    coins: Int,
    xp: Int,
    allCollected: Boolean,
    onCollect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val armed = ready > 0
    val panel = MaterialTheme.colorScheme.surfaceVariant
    val accent = if (armed) NeoAccents.gold else MaterialTheme.colorScheme.onSurfaceVariant
    val label = when {
        armed -> stringResource(R.string.missions_collect_coins, coins)
        allCollected -> stringResource(R.string.missions_all_collected)
        else -> stringResource(R.string.missions_nothing_yet)
    }
    val detail = when {
        armed -> stringResource(R.string.missions_finished_detail, missionCount(ready), xp)
        allCollected -> stringResource(R.string.missions_come_back)
        else -> stringResource(R.string.missions_finish_one)
    }
    val readOut = when {
        armed -> stringResource(R.string.cd_missions_collect, missionCount(ready), coins, xp)
        allCollected -> stringResource(R.string.cd_missions_all_collected)
        else -> stringResource(R.string.cd_missions_nothing_yet, detail)
    }

    PixelButton(
        onClick = onCollect,
        modifier = modifier
            .fillMaxWidth()
            // Last in the chain draws over the face, so the glint reads as light on the button.
            .pixelShine(enabled = armed, color = NeoAccents.gold, alpha = 0.30f)
            .semantics(mergeDescendants = true) { contentDescription = readOut },
        enabled = armed,
        accent = accent,
        fill = if (armed) lerp(panel, NeoAccents.gold, 0.24f) else panel,
        contentPadding = PaddingValues(horizontal = pixelUnits(3), vertical = pixelUnits(2)),
    ) {
        Icon(
            imageVector = Icons.Filled.Redeem,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(pixelUnits(6)),
        )
        Spacer(Modifier.width(pixelUnits(2)))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The receipt. It is loud, it is brief, and it announces itself to a screen reader. */
@Composable
private fun CollectedBanner(collected: Collected, modifier: Modifier = Modifier) {
    val readOut = stringResource(
        R.string.cd_missions_receipt,
        missionCount(collected.missions),
        collected.coins,
        collected.xp,
    )
    PixelPanel(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = readOut
            },
        accent = NeoAccents.green,
        bevel = PixelBevel.FLAT,
        borderUnits = 1,
        contentPadding = PaddingValues(pixelUnits(2)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = NeoAccents.green,
                modifier = Modifier.size(pixelUnits(6)),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.missions_collected),
                    style = MaterialTheme.typography.labelMedium,
                    color = NeoAccents.green,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.missions_receipt, collected.coins, collected.xp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The streak, and what it is currently worth.
 *
 * The bonus is quoted for the *next* rollover rather than the current one, because that is the
 * number the player can still act on. Whether the bonus has stopped growing is asked of
 * [Missions.streakBonus] rather than hardcoded, so the cap can move without this text lying.
 */
@Composable
private fun StreakPanel(days: Int, best: Int, allDoneToday: Boolean, modifier: Modifier = Modifier) {
    val next = Missions.streakBonus(days + 1)
    val capped = days > 0 && next == Missions.streakBonus(days)
    val alive = days > 0
    val accent = if (alive) NeoAccents.gold else MaterialTheme.colorScheme.onSurfaceVariant

    val readOut = if (alive) {
        stringResource(R.string.cd_streak_alive, dayCount(days), best)
    } else {
        stringResource(R.string.cd_streak_none, dayCount(best))
    }

    PixelPanel(
        modifier = modifier.fillMaxWidth(),
        accent = accent,
        title = stringResource(R.string.streak_title),
        contentPadding = PaddingValues(pixelUnits(2)),
        titleTrailing = {
            PixelBadge(
                text = "$best",
                color = MaterialTheme.colorScheme.surface,
                contentDescription = stringResource(R.string.cd_streak_best, dayCount(best)),
            )
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = readOut },
        ) {
            Icon(
                imageVector = Icons.Filled.Whatshot,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(pixelUnits(7)),
            )
            Spacer(Modifier.width(pixelUnits(2)))
            PixelDigits(text = "$days", color = accent, scale = 2)
            Spacer(Modifier.width(pixelUnits(2)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (alive) {
                        pluralStringResource(R.plurals.streak_in_a_row, days)
                    } else {
                        stringResource(R.string.streak_none)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.streak_best, dayCount(best)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(pixelUnits(2)))
        PixelDivider()
        Spacer(Modifier.height(pixelUnits(2)))

        val worth = if (allDoneToday) {
            stringResource(R.string.streak_worth_done, next)
        } else {
            stringResource(R.string.streak_worth_pending, next)
        }
        StreakNote(
            text = if (capped) stringResource(R.string.streak_capped, worth) else worth,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(pixelUnits(1)))
        StreakNote(
            icon = true,
            text = stringResource(R.string.streak_warning),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StreakNote(text: String, color: Color, icon: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        if (icon) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(pixelUnits(4)),
            )
            Spacer(Modifier.width(pixelUnits(2)))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * "1 mission" / "3 missions", and "1 day" / "3 days".
 *
 * These were two hand-written `if (n == 1)` branches. English only has the two forms, so the
 * branch was right and would have stayed right — until the first locale with three. `<plurals>`
 * is the resource system's own answer to that, and it costs a `@Composable` on two one-liners.
 */
@Composable
private fun missionCount(n: Int): String = pluralStringResource(R.plurals.mission_count, n, n)

@Composable
private fun dayCount(n: Int): String = pluralStringResource(R.plurals.day_count, n, n)

/** Coarse on purpose: a pet day is hours long, and a ticking second hand is not information. */
@Composable
private fun remaining(seconds: Long): String {
    val left = seconds.coerceAtLeast(0L)
    val hours = left / 3600L
    val minutes = (left % 3600L) / 60L
    return if (hours > 0L) {
        stringResource(R.string.duration_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.duration_minutes, minutes)
    }
}
