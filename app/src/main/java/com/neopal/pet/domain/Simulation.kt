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
    //
    // These were originally tuned against a five-hour lifetime. Once a life became two days
    // long, the same numbers demanded a meal every fifteen minutes for forty-eight hours
    // straight — an attentive keeper still starved their pet. The rates below are set from the
    // experience instead: a need takes hours to empty, so checking in a few times a day is
    // enough, and a night away leaves the pet genuinely wanting rather than dead.
    //
    //   satiety   100 -> 0 in about 5 h of neglect
    //   happiness 100 -> 0 in about 9 h
    //   energy    100 -> 0 in about 8 h awake, refilled in about 1 h of sleep
    //   hygiene   100 -> 0 in about 8 h
    //   health    100 -> 0 in about 2.8 h of continuous starvation
    private const val SATIETY_DRAIN = 0.0056f
    private const val HAPPINESS_DRAIN = 0.0030f
    private const val ENERGY_DRAIN = 0.0035f
    private const val ENERGY_RECOVERY = 0.0280f
    private const val HYGIENE_DRAIN = 0.0035f
    private const val BOND_DRAIN = 0.00040f
    private const val DISCIPLINE_DRAIN = 0.00020f
    private const val DISCIPLINE_FLOOR = 12f
    private const val HEALTH_DRAIN_CRITICAL = 0.0100f
    private const val HEALTH_REGEN = 0.0040f

    private const val EGG_HATCH_SECONDS = 90L
    /** Gap that means the app was closed rather than merely idle. */
    private const val CATCH_UP_THRESHOLD_SECONDS = 180L
    private const val POOP_CHANCE_PER_SECOND = 0.00025f
    private const val SICK_CHECK_INTERVAL = 30L

    /**
     * How long each stage lasts, in real seconds at [GameConfig.lifeSpeed] 1.0.
     *
     * The curve is deliberately front-loaded: the first evolution lands inside a single
     * sitting so a new player sees the game's one big promise quickly, and the later stages
     * stretch to hours so the relationship has time to mean something. The whole arc runs
     * about two days — long enough that a night's sleep is a chapter, not the ending.
     */
    private fun baseStageSeconds(stage: LifeStage): Long = when (stage) {
        LifeStage.EGG -> EGG_HATCH_SECONDS
        LifeStage.BABY -> 45L * 60L
        LifeStage.CHILD -> 3L * 3600L
        LifeStage.TEEN -> 8L * 3600L
        LifeStage.ADULT -> 24L * 3600L
        LifeStage.ELDER -> 12L * 3600L
    }

    /** How long each stage lasts for this run, after the player's pace setting. */
    fun stageDuration(stage: LifeStage, config: GameConfig): Long {
        val speed = config.lifeSpeed.coerceIn(0.1f, 20f)
        return max(1L, (baseStageSeconds(stage) / speed).toLong())
    }

    /** Total lifespan in real seconds, for the settings screen to explain the pace honestly. */
    fun expectedLifetimeSeconds(config: GameConfig): Long =
        LifeStage.entries.sumOf { stageDuration(it, config) }

    /**
     * True exactly once per [window] of pet time. The old `age % window > dt` test fired on two
     * consecutive seconds at dt=1 and could skip a window entirely at large dt, so the real rate
     * depended on how the elapsed time happened to be sliced.
     */
    private fun crossedWindow(ageSeconds: Long, dt: Long, window: Long): Boolean =
        window > 0 && (ageSeconds / window) != ((ageSeconds - dt) / window)

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
            chronicle = previous.chronicle,
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

        // Anything longer than a few minutes means the app was closed. Time away is simulated
        // at a fraction of the live rate, and — unless the pet was already ill — absence alone
        // is not allowed to kill it. Losing a pet should be something you did, not something
        // that happened while you slept.
        val isCatchUp = rawElapsed > CATCH_UP_THRESHOLD_SECONDS
        val decayScale = if (isCatchUp) config.offlineDecayMultiplier.coerceIn(0.05f, 1f) else 1f
        // Illness is the only thing absence is allowed to be fatal through. Tying this to the
        // current health level instead meant a pet that came back from one long gap sitting
        // exactly on the floor lost its protection on the next one, so two absences in a row
        // killed it — the very thing the floor exists to prevent.
        val protectHealth = isCatchUp && !state.isSick

        // Bound the loop: long absences use coarser steps instead of more iterations.
        val maxSteps = 2_000
        val step = max(1L, ceil(elapsed.toDouble() / maxSteps).toLong()).coerceAtMost(60L)
        var remaining = elapsed
        while (remaining > 0 && !current.isDead) {
            val dt = min(step, remaining)
            current = stepOnce(
                state = current,
                dt = dt,
                config = config,
                random = random,
                events = events,
                decayScale = decayScale,
                healthFloor = if (protectHealth) config.offlineHealthFloor else 0f,
            )
            remaining -= dt
        }

        current = current.copy(lastTickMillis = nowMillis, rngSeed = random.nextLong())
        // Missions close out here rather than in the step loop: a long absence crosses several
        // day boundaries at once, and only the final one is the day the player is looking at.
        current = Missions.rollOver(current, config, events)
        val (withAchievements, unlocked) = Achievements.evaluate(current)
        unlocked.forEach { events += GameEvent.Unlocked(it) }
        // The diary is written from the same events the UI reacts to, so the two can never disagree.
        return SimResult(Chronicle.record(withAchievements, events, config), events)
    }

    // ------------------------------------------------------------------ internals

    private fun stepOnce(
        state: PetState,
        dt: Long,
        config: GameConfig,
        random: Random,
        events: MutableList<GameEvent>,
        decayScale: Float = 1f,
        /** Floor applied before death is evaluated, so absence alone cannot be fatal. */
        healthFloor: Float = 0f,
    ): PetState {
        var s = state.copy(ageSeconds = state.ageSeconds + dt)
        // Needs move at the scaled rate; age, sleep and evolution still run on real seconds.
        val d = dt.toFloat() * decayScale

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
                // Manners fade, but a pet does not forget everything it was ever taught.
                discipline = max(DISCIPLINE_FLOOR, stats.discipline - DISCIPLINE_DRAIN * d),
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
        stats = stats.copy(health = stats.health.coerceAtLeast(healthFloor))
        s = s.copy(stats = stats.coerced(), weightGrams = weight)

        s = handleSleepCycle(s, config, events)
        s = handlePoop(s, dt, random, events)
        s = handleSickness(s, dt, random, events, decayScale)
        s = handleCareMistakes(s, dt, events, awayFromKeyboard = decayScale < 1f)
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

    private fun handleSickness(
        state: PetState,
        dt: Long,
        random: Random,
        events: MutableList<GameEvent>,
        decayScale: Float,
    ): PetState {
        if (!crossedWindow(state.ageSeconds, dt, SICK_CHECK_INTERVAL)) return state
        if (state.isSick) {
            // Illness can break on its own only if health is holding up.
            if (state.stats.health > 70f && random.nextFloat() < 0.05f) {
                events += GameEvent.Recovered
                return state.copy(isSick = false)
            }
            return state
        }
        // Expressed per hour and then converted, so the rate does not silently change when the
        // step size or the length of a life does. Tuned so a night of neglect makes illness a
        // real possibility rather than a certainty.
        var riskPerHour = 0.01f
        if (state.poops >= 3) riskPerHour += 0.05f
        if (state.stats.hygiene < 25f) riskPerHour += 0.05f
        if (state.stats.satiety < 15f) riskPerHour += 0.04f
        if (state.weightGrams > 70f) riskPerHour += 0.02f
        if (state.stage == LifeStage.ELDER) riskPerHour += 0.03f
        if (state.stats.health < 50f) riskPerHour += 0.03f
        val chance = (riskPerHour.coerceAtMost(0.18f) * decayScale) * (SICK_CHECK_INTERVAL / 3600f)
        return if (random.nextFloat() < chance) {
            events += GameEvent.GotSick
            state.copy(isSick = true, sickSinceSeconds = state.ageSeconds)
        } else state
    }

    /**
     * A care mistake is neglect the player could have prevented. While the app is open that is
     * once a minute; while it is closed it is once an hour, because eight hours of sleep is not
     * four hundred and eighty separate failures. Getting this wrong made every pet that was ever
     * slept through come back Feral.
     */
    private fun handleCareMistakes(
        state: PetState,
        dt: Long,
        events: MutableList<GameEvent>,
        awayFromKeyboard: Boolean,
    ): PetState {
        val reason = when {
            state.stats.satiety <= 2f -> "starving"
            state.isSick && state.ageSeconds - state.sickSinceSeconds > 180 -> "untreated illness"
            state.poops >= 4 -> "filthy room"
            state.stats.happiness <= 5f -> "left alone"
            state.isSleeping && !state.lightsOff -> "lights left on"
            else -> null
        } ?: return state

        // Each failure has its own patience. Sleeping with the light on is a nuisance; letting a
        // pet starve is not, and charging both once a minute made an attentive keeper look
        // exactly as bad as an absent one.
        val window = when {
            awayFromKeyboard -> 3_600L
            reason == "starving" -> 60L
            reason == "untreated illness" -> 120L
            reason == "left alone" -> 180L
            reason == "filthy room" -> 300L
            // Sleeping with the light on is a nuisance, not neglect: once an hour at most.
            else -> 3_600L
        }
        if (!crossedWindow(state.ageSeconds, dt, window)) return state

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
            // Feral is the pet raised by absence: someone who checks in twice in two days
            // logs roughly one mistake per hour away. An attentive keeper logs none, and
            // someone who simply sleeps at night lands nowhere near this.
            state.careMistakes >= 15 && neglect >= 0.5f -> EvolutionBranch.FERAL
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
