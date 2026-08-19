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
    /** Best score per minigame id, so a good run is remembered. */
    val highScores: Map<String, Int> = emptyMap(),
    val unlockedAchievements: Set<String> = emptySet(),
    val album: List<AlbumEntry> = emptyList(),
    /** The pet's own diary, written by the simulation as things happen to it. */
    val chronicle: List<ChronicleEntry> = emptyList(),
    val rngSeed: Long = 0L,
) {
    val isEgg: Boolean get() = stage == LifeStage.EGG

    /** Pet days, at [GameConfig.secondsPerPetDay] real seconds per day. */
    fun ageInPetDays(config: GameConfig): Int = (ageSeconds / config.secondsPerPetDay).toInt()

    /** Seconds spent in the current life stage. */
    val secondsInStage: Long get() = (ageSeconds - stageStartedSeconds).coerceAtLeast(0L)

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
    /** Cleared once the player has seen the first-run coach marks. */
    val tutorialSeen: Boolean = false,
) {
    companion object {
        val Default = GameConfig()
    }
}
