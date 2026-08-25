package com.neopal.pet.domain

/**
 * Makes a save from outside safe to keep.
 *
 * Everywhere else in this game the list caps are applied *when the game appends* — the chronicle
 * is trimmed as it is written to, the decision log as a decision is made. That is the right place
 * for them and it works, right up until a save arrives by some route other than play.
 *
 * A pasted save is untrusted input in exactly the sense a model's reply is, and it skips every
 * one of those write paths. A file carrying two hundred thousand decisions is not trimmed until
 * the creature next decides — which in manual autonomy is never — and the screen that lists them
 * is not lazy. The same goes for a name far longer than the new-game screen allows, which then
 * travels into prompts and notifications, and for a `NaN` in the stats, which survives `coerceIn`
 * because every comparison against it is false.
 *
 * So this is the boundary. One place, applied on the way in, rather than a check scattered over
 * every screen that reads a list. It is the same idea as [Errands.sanitise] and [Lineage.sanitise]
 * turned on the other untrusted door.
 */

/** Longest a creature's name may be, wherever it came from. */
const val MAX_NAME_CHARS = 24

/** A stat that is not a real number is replaced rather than clamped; see [safe]. */
private const val STAT_FALLBACK = 50f

/** Returns a copy that obeys every bound the game itself would have imposed. */
fun PetState.sanitised(): PetState = copy(
    name = name.trim().take(MAX_NAME_CHARS).ifBlank { "Pip" },
    stats = stats.sanitised(),
    // Oldest dropped, because the recent past is the part a player remembers and the part the
    // creature's own brief quotes back.
    chronicle = chronicle.takeLast(Chronicle.MAX_ENTRIES),
    decisions = decisions.takeLast(Simulation.MAX_DECISION_LOG),
    chat = chat.takeLast(Simulation.MAX_CHAT_TURNS),
    lessons = Lineage.inherit(emptyList(), lessons.mapNotNull { Lineage.sanitise(it) }),
    pals = pals.take(Colony.MAX_REMEMBERED_PALS),
    nest = nest.take(Colony.MAX_NEST_EGGS),
    album = album.takeLast(MAX_ALBUM_ENTRIES),
    plan = plan?.let { Errands.sanitise(it, this) },
    genome = genome.sanitised(),
    intellect = intellect.safe(0f).coerceIn(0f, 100f),
    weightGrams = weightGrams.safe(120f).coerceIn(1f, 9_999f),
    coins = coins.coerceIn(0, MAX_COINS),
    generation = generation.coerceIn(1, MAX_GENERATION),
    // A negative count is not a smaller number of things, it is a number that will underflow a
    // loop somewhere later.
    poops = poops.coerceIn(0, MAX_POOPS),
    inventory = inventory.filterValues { it > 0 }.mapValues { it.value.coerceAtMost(MAX_STACK) },
    highScores = highScores.filterKeys { MiniGame.byId(it) != null }.mapValues { it.value.coerceIn(0, MAX_SCORE) },
    unlockedAchievements = unlockedAchievements.filter { Achievements.get(it) != null }.toSet(),
)

/**
 * The same boundary, turned on the settings blob.
 *
 * [PetState] and [GameConfig] are two separate values decoded from two separate keys, and only the
 * first of them was ever cleaned. That asymmetry is not obvious from either side: the settings are
 * "just preferences", so nothing looked like it could be dangerous, and the one field that is
 * dangerous is dangerous through a screen and a divisor rather than through a list length.
 *
 * The range each control offers is not a fact the control owns -- [PetClock] owns this one -- so
 * the check on the way in and the clamp at the slider read the same numbers rather than two copies
 * of them. That is the whole lesson of the range that shipped as a pair of literals not containing
 * the game's own default.
 */
fun GameConfig.sanitised(): GameConfig = copy(
    secondsPerPetDay = PetClock.sanitiseSeconds(secondsPerPetDay),
)

private fun Stats.sanitised(): Stats = Stats(
    satiety = satiety.safe(STAT_FALLBACK).coerceIn(0f, 100f),
    happiness = happiness.safe(STAT_FALLBACK).coerceIn(0f, 100f),
    energy = energy.safe(STAT_FALLBACK).coerceIn(0f, 100f),
    hygiene = hygiene.safe(STAT_FALLBACK).coerceIn(0f, 100f),
    health = health.safe(STAT_FALLBACK).coerceIn(0f, 100f),
    bond = bond.safe(STAT_FALLBACK).coerceIn(0f, 100f),
    discipline = discipline.safe(STAT_FALLBACK).coerceIn(0f, 100f),
)

private fun Genome.sanitised(): Genome = Genome(
    muzzle = muzzle.gene(), ears = ears.gene(), earDroop = earDroop.gene(), limbs = limbs.gene(),
    stance = stance.gene(), tail = tail.gene(), build = build.gene(), coat = coat.gene(),
    hue = hue.gene(), curiosity = curiosity.gene(), sociability = sociability.gene(),
    appetite = appetite.gene(), vigor = vigor.gene(), wit = wit.gene(),
)

private fun Float.gene(): Float = safe(0.5f).coerceIn(0f, 1f)

/**
 * Replaces a value that is not a real number.
 *
 * `NaN` cannot be clamped away: every comparison against it is false, so `coerceIn` hands it
 * straight back. It then spreads through every arithmetic it touches and eventually reaches a
 * draw call, where it blanks the frame rather than announcing itself.
 */
private fun Float.safe(fallback: Float): Float = if (isNaN() || isInfinite()) fallback else this

private const val MAX_ALBUM_ENTRIES = 60
private const val MAX_COINS = 9_999_999
private const val MAX_GENERATION = 9_999
private const val MAX_POOPS = 12
private const val MAX_STACK = 999
private const val MAX_SCORE = 9_999_999
