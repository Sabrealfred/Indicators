package com.neopal.pet.domain

import kotlinx.serialization.Serializable

/** The four starter families. Each one has its own palette, silhouette and stat bias. */
@Serializable
enum class Species(
    val displayName: String,
    /** Multiplies how fast satiety drains. */
    val hungerBias: Float,
    /** Multiplies how fast energy drains. */
    val energyBias: Float,
    /** Multiplies happiness gained from play. */
    val playBias: Float,
) {
    AQUA("Aqua", hungerBias = 0.9f, energyBias = 1.0f, playBias = 1.1f),
    EMBER("Ember", hungerBias = 1.2f, energyBias = 1.15f, playBias = 1.0f),
    LEAF("Leaf", hungerBias = 0.85f, energyBias = 0.85f, playBias = 0.9f),
    VOLT("Volt", hungerBias = 1.1f, energyBias = 1.3f, playBias = 1.25f),
}

/** Life stages. The pet walks through all of them; every jump is an evolution scene. */
@Serializable
enum class LifeStage(val displayName: String, val order: Int) {
    EGG("Egg", 0),
    BABY("Baby", 1),
    CHILD("Child", 2),
    TEEN("Teen", 3),
    ADULT("Adult", 4),
    ELDER("Elder", 5);

    val isHatched: Boolean get() = this != EGG
}

/**
 * Evolution branch, decided when the pet leaves [LifeStage.CHILD] and refined again at
 * [LifeStage.ADULT]. Derived from how the pet was raised, never picked by the player.
 */
@Serializable
enum class EvolutionBranch(val displayName: String) {
    BALANCED("Balanced"),
    ATHLETIC("Athletic"),
    GOURMAND("Gourmand"),
    SCHOLAR("Scholar"),
    FERAL("Feral"),
}

/** Flavour trait rolled when the egg is created. Tweaks decay rates and dialogue. */
@Serializable
enum class Personality(val displayName: String) {
    PLAYFUL("Playful"),
    SHY("Shy"),
    GREEDY("Greedy"),
    BRAVE("Brave"),
    CALM("Calm"),
}

/** What the pet is showing on screen right now. Drives art, particles and dialogue. */
@Serializable
enum class Mood {
    EGG, HAPPY, NEUTRAL, SAD, HUNGRY, DIRTY, TIRED, SICK, SLEEPING, DEAD
}

/** Reason the run ended. Shown on the memorial screen. */
@Serializable
enum class DeathReason(val displayName: String) {
    STARVATION("Starvation"),
    ILLNESS("Untreated illness"),
    NEGLECT("Neglect"),
    OLD_AGE("Old age"),
}

/** All continuous stats live on a 0..100 scale. */
@Serializable
data class Stats(
    val satiety: Float = 70f,
    val happiness: Float = 70f,
    val energy: Float = 80f,
    val hygiene: Float = 90f,
    val health: Float = 100f,
    val discipline: Float = 20f,
    val bond: Float = 10f,
) {
    fun coerced(): Stats = Stats(
        satiety = satiety.coerceIn(0f, 100f),
        happiness = happiness.coerceIn(0f, 100f),
        energy = energy.coerceIn(0f, 100f),
        hygiene = hygiene.coerceIn(0f, 100f),
        health = health.coerceIn(0f, 100f),
        discipline = discipline.coerceIn(0f, 100f),
        bond = bond.coerceIn(0f, 100f),
    )

    /**
     * 0..1 summary used for the care grade. Only the five needs count: discipline and bond
     * start deliberately low and grow over a lifetime, so folding them in would grade a
     * perfectly-cared-for newborn a C for the crime of being new.
     */
    val careScore: Float
        get() = ((satiety + happiness + hygiene + health + energy) / 5f) / 100f
}

/** One picture in the album: a snapshot of the pet at a milestone. */
@Serializable
data class AlbumEntry(
    val id: String,
    val title: String,
    val species: Species,
    val stage: LifeStage,
    val branch: EvolutionBranch,
    val hatId: String?,
    val roomTheme: String,
    val petAgeSeconds: Long,
    val capturedAtMillis: Long,
)

/**
 * One finished run, sealed when the next generation starts and never touched again.
 *
 * The album could only ever confess how far a pet got, and only if it lived long enough to be
 * photographed. These are the numbers a player is actually asking about when they wonder whether
 * they are getting better at this: how long it lasted, how it was cared for on the way, and what
 * finally took it.
 */
@Serializable
data class RunRecord(
    val generation: Int = 0,
    val name: String = "",
    val species: Species = Species.AQUA,
    /** Stages never go backwards, so the stage it died in is the furthest it ever reached. */
    val stage: LifeStage = LifeStage.EGG,
    val branch: EvolutionBranch = EvolutionBranch.BALANCED,
    val personality: Personality = Personality.CALM,
    val lifespanSeconds: Long = 0L,
    /** Null when the run ended without dying; the comparison has to be able to say so. */
    val deathReason: DeathReason? = null,
    /** Time-weighted average of [Stats.careScore] across the whole life, 0..1. */
    val careScore: Float = 0f,
    val peakBond: Float = 0f,
    val careMistakes: Int = 0,
    val mealsEaten: Int = 0,
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    val cleanups: Int = 0,
    val medicineDoses: Int = 0,
    val level: Int = 1,
    val bestCareStreak: Int = 0,
    val endedAtMillis: Long = 0L,
) {
    /** The only ending that is not a failure. */
    val diedOfOldAge: Boolean get() = deathReason == DeathReason.OLD_AGE

    /**
     * Neglect per hour lived. The raw count rewards a pet that died young for dying young, so the
     * comparison between two runs of different lengths has to be a rate.
     */
    val mistakesPerHour: Float get() = careMistakes / (lifespanSeconds / 3600f).coerceAtLeast(1f)

    companion object {
        /** Seals [state] as it stood at the end of its run. */
        fun of(state: PetState, endedAtMillis: Long): RunRecord = RunRecord(
            generation = state.generation,
            name = state.name,
            species = state.species,
            stage = state.stage,
            branch = state.branch,
            personality = state.personality,
            lifespanSeconds = state.ageSeconds,
            deathReason = state.deathReason,
            careScore = state.lifetimeCareScore,
            peakBond = maxOf(state.peakBond, state.stats.bond),
            careMistakes = state.careMistakes,
            mealsEaten = state.mealsEaten,
            gamesPlayed = state.gamesPlayed,
            gamesWon = state.gamesWon,
            cleanups = state.cleanups,
            medicineDoses = state.medicineDoses,
            level = state.level,
            bestCareStreak = state.bestCareStreak,
            endedAtMillis = endedAtMillis,
        )
    }
}

/**
 * The full save state of one run. Serialized as a single JSON blob so the schema can grow
 * without a migration dance; unknown fields are ignored on read and defaults fill the gaps.
 */
@Serializable
data class PetState(
    val name: String = "Pip",
    val species: Species = Species.AQUA,
    val stage: LifeStage = LifeStage.EGG,
    val branch: EvolutionBranch = EvolutionBranch.BALANCED,
    val personality: Personality = Personality.CALM,
    val stats: Stats = Stats(),

    /** Wall clock of the last processed simulation tick. */
    val lastTickMillis: Long = 0L,
    val bornAtMillis: Long = 0L,
    /** Lifetime in *pet* seconds; the simulation advances this in real time. */
    val ageSeconds: Long = 0L,
    /** Age at which the current stage started, for evolution timers. */
    val stageStartedSeconds: Long = 0L,

    val weightGrams: Float = 12f,
    val poops: Int = 0,
    val isSleeping: Boolean = false,
    /** Player-controlled room light. Sleeping with the light on costs happiness. */
    val lightsOff: Boolean = false,

    val isSick: Boolean = false,
    val sickSinceSeconds: Long = 0L,
    val medicineDoses: Int = 0,

    val careMistakes: Int = 0,
    /** Highest bond ever reached. Bond decays, so the closing value undersells the run. */
    val peakBond: Float = 0f,
    /** Sum of `careScore × seconds` since hatching, and the seconds it was sampled over. */
    val careScoreSeconds: Double = 0.0,
    val careSampleSeconds: Long = 0L,
    val praises: Int = 0,
    val scolds: Int = 0,
    val mealsEaten: Int = 0,
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    val cleanups: Int = 0,

    val coins: Int = 50,
    val xp: Int = 0,
    val level: Int = 1,

    val inventory: Map<String, Int> = mapOf("snack_berry" to 3, "meal_bowl" to 2, "medicine" to 1),
    val equippedHat: String? = null,
    val roomTheme: String = "room_default",

    val isDead: Boolean = false,
    val deathReason: DeathReason? = null,
    val deathAtSeconds: Long = 0L,

    val generation: Int = 1,
    /**
     * Every earlier run this save still remembers, oldest first, capped at
     * [Simulation.MAX_REMEMBERED_GENERATIONS]. Empty on a save written before the log existed,
     * which is not the same as a run that did nothing — nobody may show a zero for it.
     */
    val previousGenerations: List<RunRecord> = emptyList(),
    /** Best score per minigame id, so a good run is remembered. */
    val highScores: Map<String, Int> = emptyMap(),
    val unlockedAchievements: Set<String> = emptySet(),
    val album: List<AlbumEntry> = emptyList(),
    /** The pet's own diary, written by the simulation as things happen to it. */
    val chronicle: List<ChronicleEntry> = emptyList(),

    /** Counters as they stood when the current pet day began; daily progress is the difference. */
    val dayLedger: DayLedger = DayLedger(),
    /** Today's missions whose reward has already been collected. Cleared at every rollover. */
    val claimedMissionIds: Set<String> = emptySet(),
    /** Consecutive pet days finishing every mission. Broken by a gap, not just by a bad day. */
    val careStreakDays: Int = 0,
    val bestCareStreak: Int = 0,

    // ---- mind, body and family ----------------------------------------------------------
    //
    // Everything below is inert on a save that predates it: autonomy defaults to OFF, the skill
    // set is empty, and the genome falls back to the neutral starter shape. An upgraded save is
    // therefore exactly the pet the player left, and the new systems only start once asked for.

    /** The heritable body and temperament. Seeded from the species when the egg is made. */
    val genome: Genome = Genome(),
    /** How much of its own day the pet is allowed to run. */
    val autonomy: Autonomy = Autonomy.OFF,
    /** What the brain committed to, and until when. Null means it is between decisions. */
    val activity: Activity? = null,
    /** Newest last, capped at [Simulation.MAX_DECISION_LOG]. The pet's own account of itself. */
    val decisions: List<Decision> = emptyList(),

    /** 0..100. Gates which skills can be learned and how well the brain weighs its options. */
    val intellect: Float = 5f,
    val skills: Set<Skill> = emptySet(),
    /** Study time banked toward the skill currently being learned. */
    val studySeconds: Long = 0L,
    /** The skill the pet is working on. Null means it picks one when it next studies. */
    val studying: Skill? = null,
    /** Lifetime totals, for achievements and the stats screen. */
    val studySessions: Int = 0,
    val selfCareActions: Int = 0,
    val socialActions: Int = 0,

    /** Everyone the pet has met, present or not. */
    val pals: List<Pal> = emptyList(),
    /** Eggs waiting to hatch. */
    val nest: List<NestEgg> = emptyList(),
    /** Names of this pet's own parents, for the family tree. Empty for a founder. */
    val parentNames: List<String> = emptyList(),

    val rngSeed: Long = 0L,
) {
    val isEgg: Boolean get() = stage == LifeStage.EGG

    /** Pet days, at [GameConfig.secondsPerPetDay] real seconds per day. */
    fun ageInPetDays(config: GameConfig): Int = (ageSeconds / config.secondsPerPetDay).toInt()

    /** Seconds spent in the current life stage. */
    val secondsInStage: Long get() = (ageSeconds - stageStartedSeconds).coerceAtLeast(0L)

    /**
     * Care across the whole life, 0..1. Grading the closing stats instead scores every neglected
     * pet at zero and flatters every pet that merely survived to old age.
     */
    val lifetimeCareScore: Float
        get() = if (careSampleSeconds > 0L) (careScoreSeconds / careSampleSeconds).toFloat() else stats.careScore

    /** XP needed to reach the next level; grows quadratically but stays reachable. */
    val xpForNextLevel: Int get() = 60 + (level - 1) * 45

    val mood: Mood
        get() = when {
            isDead -> Mood.DEAD
            stage == LifeStage.EGG -> Mood.EGG
            isSleeping -> Mood.SLEEPING
            isSick -> Mood.SICK
            stats.satiety < 25f -> Mood.HUNGRY
            hygieneCritical -> Mood.DIRTY
            stats.energy < 20f -> Mood.TIRED
            stats.happiness < 30f -> Mood.SAD
            stats.happiness > 70f && stats.satiety > 45f -> Mood.HAPPY
            else -> Mood.NEUTRAL
        }

    val hygieneCritical: Boolean get() = stats.hygiene < 30f || poops >= 3

    /** True when the pet is unhappy enough to refuse interactions. */
    val isSulking: Boolean get() = !isDead && !isSleeping && stats.happiness < 12f

    /** The expressed body: what the renderer draws, and what the breeding screen compares. */
    val morphology: Morphology get() = Morphology.of(genome, stage, branch, weightGrams)

    /** True when the pet both has the skill and is allowed to use it at this autonomy level. */
    fun canAct(skill: Skill): Boolean {
        if (skill !in skills) return false
        return when (autonomy) {
            Autonomy.OFF -> false
            // Assisted covers upkeep only. Anything that spends resources, leaves the room or
            // starts a family stays the player's call, because those are the decisions someone
            // who asked for "just handle the chores" would be annoyed to find already made.
            Autonomy.ASSIST -> skill in ASSIST_SKILLS
            Autonomy.FULL -> true
        }
    }

    /** Pals actually in the room right now. */
    val presentPals: List<Pal> get() = pals.filter { it.present }

    /** Whether the pet is old enough for any of this to apply. Eggs and babies are just babies. */
    val isMindAwake: Boolean
        get() = !isDead && stage.order >= LifeStage.CHILD.order

    companion object {
        /** What [Autonomy.ASSIST] is allowed to do on its own. */
        val ASSIST_SKILLS = setOf(Skill.TIDY_UP, Skill.SELF_GROOM, Skill.SELF_SETTLE)
    }
}

/** Tunables. Exposed in settings so a run can be sped up for testing or slowed for a long game. */
@Serializable
data class GameConfig(
    /**
     * Real seconds that make up one pet day. This drives the day/night clock and the day
     * counter only — how fast the pet grows up is [lifeSpeed].
     */
    val secondsPerPetDay: Long = 21_600L,
    /**
     * Multiplies how fast life stages advance. At 1.0 a pet lives about two real days, which
     * is the point: a life you can sleep through is not a life you can care for.
     */
    val lifeSpeed: Float = 1f,
    /** Offline progress is simulated at most this far back, so a week away is survivable. */
    val maxOfflineSeconds: Long = 12L * 3600L,
    /**
     * Time away drains needs at this fraction of the live rate. Without it a full pet starves
     * to death in under an hour, which means a night's sleep kills it every single time.
     */
    val offlineDecayMultiplier: Float = 0.60f,
    /** Absence alone can never take health below this; only illness left untreated can. */
    val offlineHealthFloor: Float = 12f,
    val soundEnabled: Boolean = true,
    /** 0..1 master volume for the synthesised effects. */
    val sfxVolume: Float = 0.8f,
    val hapticsEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val reducedMotion: Boolean = false,
    /**
     * Renders the whole scene into a low-resolution buffer and upscales it with nearest-neighbour
     * sampling, so the art reads as chunky pixel art instead of smooth vectors.
     */
    val pixelMode: Boolean = true,
    /**
     * Vertical resolution of that buffer. The real number used is derived from the screen so
     * the upscale stays a whole number, and this is the target it rounds to. 144 turned out to
     * be too coarse for this art — the eyes came out three pixels across and the eyebrows
     * disappeared entirely — so the default sits higher.
     */
    val pixelHeight: Int = 200,
    /**
     * How much the pixel blocks are allowed to bleed into each other, 0..1. Zero is a razor-hard
     * retro look; higher values round the light off the edges without blurring the art itself.
     */
    val softFinish: Float = 0.55f,
    /** Warm/cool colour grade and vignette over the finished frame, 0..1. */
    val atmosphere: Float = 0.7f,
    /** Cleared once the player has seen the first-run coach marks. */
    val tutorialSeen: Boolean = false,
) {
    companion object {
        val Default = GameConfig()
    }
}
