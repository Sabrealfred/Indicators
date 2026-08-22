package com.neopal.pet.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/** One "+12 MOOD" style readout, produced by diffing the state before and after an action. */
data class StatDelta(val label: String, val amount: Int, val positive: Boolean)

/**
 * Turns an action into the handful of numbers worth showing the player. Without this the game
 * silently moves seven meters at once and nothing on screen explains why.
 */
fun statDeltas(before: PetState, after: PetState): List<StatDelta> {
    val candidates = listOf(
        "FOOD" to (after.stats.satiety - before.stats.satiety),
        "MOOD" to (after.stats.happiness - before.stats.happiness),
        "ENERGY" to (after.stats.energy - before.stats.energy),
        "CLEAN" to (after.stats.hygiene - before.stats.hygiene),
        "HP" to (after.stats.health - before.stats.health),
        "BOND" to (after.stats.bond - before.stats.bond),
        "DISC" to (after.stats.discipline - before.stats.discipline),
        "COINS" to (after.coins - before.coins).toFloat(),
        "XP" to (after.xp - before.xp + (after.level - before.level) * before.xpForNextLevel).toFloat(),
    )
    return candidates
        .filter { abs(it.second) >= 1f }
        .sortedByDescending { abs(it.second) }
        .take(3)
        .map { (label, amount) ->
            StatDelta(label = label, amount = amount.roundToInt(), positive = amount > 0f)
        }
}
