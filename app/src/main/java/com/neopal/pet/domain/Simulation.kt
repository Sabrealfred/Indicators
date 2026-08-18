package com.neopal.pet.domain

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Anything the simulation wants the UI (or a notification) to react to. */
sealed interface GameEvent {
    data object Hatched : GameEvent
    data object Pooped : GameEvent
    data object FellAsleep : GameEvent
    data object WokeUp : GameEvent
    data object GotSick : GameEvent
    data object Recovered : GameEvent
    data class CareMistake(val reason: String) : GameEvent
    data class Evolved(val from: LifeStage, val to: LifeStage, val branch: EvolutionBranch) : GameEvent
    data class LeveledUp(val level: Int) : GameEvent
    data class Died(val reason: DeathReason) : GameEvent
    data class Unlocked(val achievement: Achievement) : GameEvent
    data class Message(val text: String) : GameEvent
}

data class SimResult(val state: PetState, val events: List<GameEvent>)

/**
 * The clock of the game. Everything that changes without the player touching the screen
 * happens here, so the same code path serves the foreground loop, the app coming back from
 * the background, and the background worker that fires notifications.
 */
object Simulation {

    // ---- Tunables, expressed per real second at the default speed ----
    private const val SATIETY_DRAIN = 0.085f
    private const val HAPPINESS_DRAIN = 0.045f
    private const val ENERGY_DRAIN = 0.050f
    private const val ENERGY_RECOVERY = 0.320f
    private const val HYGIENE_DRAIN = 0.028f
    private const val BOND_DRAIN = 0.005f
    private const val DISCIPLINE_DRAIN = 0.004f
    private const val HEALTH_DRAIN_CRITICAL = 0.055f
    private const val HEALTH_REGEN = 0.020f

    private const val EGG_HATCH_SECONDS = 90L
    private const val POOP_CHANCE_PER_SECOND = 0.0009f
    private const val SICK_CHECK_INTERVAL = 30L

    /** How long each stage lasts, in pet seconds, before the pet is eligible to evolve. */
    fun stageDuration(stage: LifeStage, config: GameConfig): Long = when (stage) {
        LifeStage.EGG -> EGG_HATCH_SECONDS
        LifeStage.BABY -> config.secondsPerPetDay
        LifeStage.CHILD -> config.secondsPerPetDay * 2
        LifeStage.TEEN -> config.secondsPerPetDay * 3
        LifeStage.ADULT -> config.secondsPerPetDay * 5
        LifeStage.ELDER -> config.secondsPerPetDay * 4
    }

    fun stageProgress(state: PetState, config: GameConfig): Float {
        val total = stageDuration(state.stage, config).toFloat()
        return (state.secondsInStage / total).coerceIn(0f, 1f)
    }

    /** In-game hour, 0..24, derived from the pet's own age so it never fights the wall clock. */
    fun petClockHour(state: PetState, config: GameConfig): Float {
        val intoDay = (state.ageSeconds % config.secondsPerPetDay).toFloat()
        return (intoDay / config.secondsPerPetDay) * 24f
    }

    fun isNight(state: PetState, config: GameConfig): Boolean {
        val h = petClockHour(state, config)
        return h >= 21.5f || h < 6.5f
    }

    fun newGame(name: String, species: Species, nowMillis: Long, seed: Long = nowMillis): PetState =
        PetState(
            name = name,
            species = species,
            stage = LifeStage.EGG,
            personality = Personality.entries[Random(seed).nextInt(Personality.entries.size)],
            lastTickMillis = nowMillis,
            bornAtMillis = nowMillis,
            rngSeed = seed,
        )

    /** Restarts after a death, carrying the album, achievements, coins and generation forward. */
    fun nextGeneration(previous: PetState, name: String, species: Species, nowMillis: Long): PetState =
        newGame(name, species, nowMillis, seed = previous.rngSeed * 31 + nowMillis).copy(
            generation = previous.generation + 1,
            coins = previous.coins,
            album = previous.album,
            unlockedAchievements = previous.unlockedAchievements,
            inventory = previous.inventory.filterKeys { id ->
                ItemCatalog[id]?.isCosmetic == true
            } + mapOf("snack_berry" to 3, "meal_bowl" to 2, "medicine" to 1),
        )

    /**
     * Advances the world to [nowMillis]. Safe to call every frame (elapsed ≈ 0 is a no-op) and
     * safe to call after days away — the catch-up is capped by [GameConfig.maxOfflineSeconds]
     * and split into bounded steps so a long absence never blocks the main thread.
     */
    fun advance(state: PetState, nowMillis: Long, config: GameConfig = GameConfig.Default): SimResult {
        if (state.lastTickMillis == 0L) {
            return SimResult(state.copy(lastTickMillis = nowMillis), emptyList())
        }
        val rawElapsed = ((nowMillis - state.lastTickMillis) / 1000L).coerceAtLeast(0L)
        if (rawElapsed <= 0L) return SimResult(state, emptyList())
        if (state.isDead) return SimResult(state.copy(lastTickMillis = nowMillis), emptyList())

        val elapsed = min(rawElapsed, config.maxOfflineSeconds)
        val events = mutableListOf<GameEvent>()
        var current = state
        val random = Random(state.rngSeed)

        // Bound the loop: long absences use coarser steps instead of more iterations.
        val maxSteps = 2_000
        val step = max(1L, ceil(elapsed.toDouble() / maxSteps).toLong()).coerceAtMost(60L)
        var remaining = elapsed
        while (remaining > 0 && !current.isDead) {
            val dt = min(step, remaining)
            current = stepOnce(current, dt, config, random, events)
            remaining -= dt
        }

        current = current.copy(lastTickMillis = nowMillis, rngSeed = random.nextLong())
        val (withAchievements, unlocked) = Achievements.evaluate(current)
        unlocked.forEach { events += GameEvent.Unlocked(it) }
        return SimResult(withAchievements, events)
    }

    // ------------------------------------------------------------------ internals

    private fun stepOnce(
        state: PetState,
        dt: Long,
        config: GameConfig,
        random: Random,
        events: MutableList<GameEvent>,
    ): PetState {
        var s = state.copy(ageSeconds = state.ageSeconds + dt)
        val d = dt.toFloat()

        if (s.stage == LifeStage.EGG) {
            return if (s.secondsInStage >= stageDuration(LifeStage.EGG, config)) {
                events += GameEvent.Hatched
                s.copy(
                    stage = LifeStage.BABY,
                    stageStartedSeconds = s.ageSeconds,
                    stats = s.stats.copy(happiness = 80f, satiety = 60f),
                )
            } else s
        }

        val stageMult = when (s.stage) {
            LifeStage.EGG -> 0f
            LifeStage.BABY -> 1.30f
            LifeStage.CHILD -> 1.10f
            LifeStage.TEEN -> 1.00f
            LifeStage.ADULT -> 0.90f
            LifeStage.ELDER -> 1.05f
        }
        val personalityHunger = if (s.personality == Personality.GREEDY) 1.2f else 1f
        val personalityHappy = when (s.personality) {
            Personality.PLAYFUL -> 1.25f
            Personality.CALM -> 0.8f
            else -> 1f
        }

        var stats = s.stats

        if (s.isSleeping) {
            stats = stats.copy(
                energy = stats.energy + ENERGY_RECOVERY * d,
                satiety = stats.satiety - SATIETY_DRAIN * 0.4f * d * stageMult * s.species.hungerBias,
                happiness = stats.happiness - if (s.lightsOff) 0f else HAPPINESS_DRAIN * 1.5f * d,
                health = stats.health + if (s.isSick) 0f else HEALTH_REGEN * d,
            )
        } else {
            stats = stats.copy(
                satiety = stats.satiety - SATIETY_DRAIN * d * stageMult * s.species.hungerBias * personalityHunger,
                energy = stats.energy - ENERGY_DRAIN * d * stageMult * s.species.energyBias,
                happiness = stats.happiness - HAPPINESS_DRAIN * d * stageMult * personalityHappy,
                hygiene = stats.hygiene - HYGIENE_DRAIN * d * stageMult - s.poops * 0.02f * d,
                bond = stats.bond - BOND_DRAIN * d,
                discipline = stats.discipline - DISCIPLINE_DRAIN * d,
            )
        }

        // Health responds to how badly the needs are being met.
        val starving = stats.satiety <= 1f
        val filthy = stats.hygiene <= 5f || s.poops >= 4
        val healthDelta = when {
            s.isSick -> -HEALTH_DRAIN_CRITICAL * 0.6f * d
            starving -> -HEALTH_DRAIN_CRITICAL * d
            filthy -> -HEALTH_DRAIN_CRITICAL * 0.5f * d
            stats.satiety > 40f && stats.hygiene > 40f -> HEALTH_REGEN * d
            else -> 0f
        }
        stats = stats.copy(health = stats.health + healthDelta)

        // Being hungry or dirty also eats into the mood.
        if (starving || filthy) {
            stats = stats.copy(happiness = stats.happiness - HAPPINESS_DRAIN * 0.8f * d)
        }

        // Weight drifts down slowly when the pet is not overfed.
        val weight = (s.weightGrams - 0.0015f * d).coerceIn(6f, 120f)
        s = s.copy(stats = stats.coerced(), weightGrams = weight)

        s = handleSleepCycle(s, config, events)
        s = handlePoop(s, dt, random, events)
        s = handleSickness(s, dt, random, events)
        s = handleCareMistakes(s, dt, events)
        s = handleEvolution(s, config, events)
        s = handleDeath(s, config, events)
        return s
    }

    private fun handleSleepCycle(state: PetState, config: GameConfig, events: MutableList<GameEvent>): PetState {
        val night = isNight(state, config)
        return when {
            !state.isSleeping && (state.stats.energy <= 8f || (night && state.lightsOff)) -> {
                events += GameEvent.FellAsleep
                state.copy(isSleeping = true)
            }
            state.isSleeping && (state.stats.energy >= 98f || (!night && !state.lightsOff)) -> {
                events += GameEvent.WokeUp
                state.copy(isSleeping = false)
            }
            else -> state
        }
    }

    private fun handlePoop(state: PetState, dt: Long, random: Random, events: MutableList<GameEvent>): PetState {
        if (state.isSleeping || state.poops >= 6) return state
        // Well-fed pets produce more; the chance scales with satiety and with the elapsed step.
        val chance = POOP_CHANCE_PER_SECOND * dt * (0.5f + state.stats.satiety / 100f)
        return if (random.nextFloat() < chance) {
            events += GameEvent.Pooped
            state.copy(poops = state.poops + 1, stats = state.stats.copy(hygiene = state.stats.hygiene - 6f).coerced())
        } else state
    }

    private fun handleSickness(state: PetState, dt: Long, random: Random, events: MutableList<GameEvent>): PetState {
        if (state.ageSeconds % SICK_CHECK_INTERVAL > dt) return state
        if (state.isSick) {
            // Illness can break on its own only if health is holding up.
            if (state.stats.health > 70f && random.nextFloat() < 0.05f) {
                events += GameEvent.Recovered
                return state.copy(isSick = false)
            }
            return state
        }
        var risk = 0.004f
        if (state.poops >= 3) risk += 0.030f
        if (state.stats.hygiene < 25f) risk += 0.035f
        if (state.stats.satiety < 15f) risk += 0.030f
        if (state.weightGrams > 70f) risk += 0.020f
        if (state.stage == LifeStage.ELDER) risk += 0.020f
        if (state.stats.health < 50f) risk += 0.025f
        return if (random.nextFloat() < risk) {
            events += GameEvent.GotSick
            state.copy(isSick = true, sickSinceSeconds = state.ageSeconds)
        } else state
    }

    /**
     * A care mistake is logged at most once per minute of sustained neglect. The counter is the
     * main input to the evolution branch, so it deliberately forgives short lapses.
     */
    private fun handleCareMistakes(state: PetState, dt: Long, events: MutableList<GameEvent>): PetState {
        if (state.ageSeconds % 60L > dt) return state
        val reason = when {
            state.stats.satiety <= 2f -> "starving"
            state.isSick && state.ageSeconds - state.sickSinceSeconds > 180 -> "untreated illness"
            state.poops >= 4 -> "filthy room"
            state.isSleeping && !state.lightsOff -> "lights left on"
            state.stats.happiness <= 5f -> "left alone"
            else -> null
        } ?: return state
        events += GameEvent.CareMistake(reason)
        return state.copy(careMistakes = state.careMistakes + 1)
    }

    private fun handleEvolution(state: PetState, config: GameConfig, events: MutableList<GameEvent>): PetState {
        val next = when (state.stage) {
            LifeStage.BABY -> LifeStage.CHILD
            LifeStage.CHILD -> LifeStage.TEEN
            LifeStage.TEEN -> LifeStage.ADULT
            LifeStage.ADULT -> LifeStage.ELDER
            else -> null
        } ?: return state
        if (state.secondsInStage < stageDuration(state.stage, config)) return state

        val branch = if (next == LifeStage.TEEN || next == LifeStage.ADULT) decideBranch(state) else state.branch
        events += GameEvent.Evolved(state.stage, next, branch)
        return state.copy(
            stage = next,
            branch = branch,
            stageStartedSeconds = state.ageSeconds,
            stats = state.stats.copy(
                happiness = min(100f, state.stats.happiness + 15f),
                energy = min(100f, state.stats.energy + 20f),
            ),
            album = state.album + AlbumEntry(
                id = "evo_${next.name}_${state.ageSeconds}",
                title = "${state.name} became a ${next.displayName}",
                species = state.species,
                stage = next,
                branch = branch,
                hatId = state.equippedHat,
                roomTheme = state.roomTheme,
                petAgeSeconds = state.ageSeconds,
                capturedAtMillis = state.lastTickMillis,
            ),
        )
    }

    /**
     * Reads the whole care history and picks the form the pet grew into.
     * Nothing here is random: two players raising the same way get the same creature.
     */
    fun decideBranch(state: PetState): EvolutionBranch {
        val hours = max(1f, state.ageSeconds / 3600f)
        val neglect = state.careMistakes / hours
        val winRate = if (state.gamesPlayed == 0) 0f else state.gamesWon.toFloat() / state.gamesPlayed
        return when {
            neglect >= 6f || state.stats.discipline < 15f -> EvolutionBranch.FERAL
            state.gamesPlayed >= 12 && winRate >= 0.6f && state.stats.energy >= 55f -> EvolutionBranch.ATHLETIC
            state.weightGrams >= 45f || state.mealsEaten >= 35 -> EvolutionBranch.GOURMAND
            state.stats.discipline >= 60f && state.praises >= 8 -> EvolutionBranch.SCHOLAR
            else -> EvolutionBranch.BALANCED
        }
    }

    private fun handleDeath(state: PetState, config: GameConfig, events: MutableList<GameEvent>): PetState {
        val reason = when {
            state.stats.health <= 0f && state.isSick -> DeathReason.ILLNESS
            state.stats.health <= 0f && state.stats.satiety <= 0f -> DeathReason.STARVATION
            state.stats.health <= 0f -> DeathReason.NEGLECT
            state.stage == LifeStage.ELDER &&
                state.secondsInStage >= stageDuration(LifeStage.ELDER, config) -> DeathReason.OLD_AGE
            else -> null
        } ?: return state
        events += GameEvent.Died(reason)
        return state.copy(isDead = true, deathReason = reason, deathAtSeconds = state.ageSeconds, isSleeping = false)
    }

    /** Adds XP and rolls over levels, emitting one event per level gained. */
    fun applyXp(state: PetState, amount: Int, events: MutableList<GameEvent>): PetState {
        if (amount <= 0) return state
        var s = state.copy(xp = state.xp + amount)
        while (s.xp >= s.xpForNextLevel) {
            s = s.copy(xp = s.xp - s.xpForNextLevel, level = s.level + 1, coins = s.coins + 15 * (s.level + 1))
            events += GameEvent.LeveledUp(s.level)
        }
        return s
    }
}
