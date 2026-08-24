package com.neopal.pet.domain

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * A snapshot of the lifetime counters taken when a pet day begins.
 *
 * Daily progress is the difference between the counters *now* and the counters in here, which
 * is why nothing in [CareActions] had to learn about missions: feeding already increments
 * `mealsEaten`, so feeding already advances "serve three meals". The alternative — emitting a
 * mission event from every action — would have meant one more thing to forget at every new
 * action site, forever.
 */
@Serializable
data class DayLedger(
    val dayIndex: Int = 0,
    val meals: Int = 0,
    val games: Int = 0,
    val wins: Int = 0,
    val cleanups: Int = 0,
    val praises: Int = 0,
    val medicine: Int = 0,
    val mistakes: Int = 0,

    /**
     * The best each need has been today.
     *
     * Counters can only ever go up, so a goal counted off them is safe: three meals served at
     * noon are still three meals served at midnight. A *stat* goal is not, because satiety loses
     * about twenty points an hour — "get satiety above 90" was therefore a goal that un-finished
     * itself within the quarter hour, and the day it belonged to was graded on whatever the
     * needle happened to read at roll-over rather than on how the day was lived. These four
     * marks are what makes a stat goal behave like the counters: reached once is reached.
     *
     * Zero on a save written before this existed, which costs nothing — every stat goal reads
     * the larger of the mark and the live value, so an unrecorded day grades exactly as it
     * always did.
     */
    val peakSatiety: Float = 0f,
    val peakHappiness: Float = 0f,
    val peakEnergy: Float = 0f,
    val peakHygiene: Float = 0f,
) {
    companion object {
        fun of(state: PetState, dayIndex: Int) = DayLedger(
            dayIndex = dayIndex,
            meals = state.mealsEaten,
            games = state.gamesPlayed,
            wins = state.gamesWon,
            cleanups = state.cleanups,
            praises = state.praises,
            medicine = state.medicineDoses,
            mistakes = state.careMistakes,
            // A day opens on the needs it inherits: waking up already clean is a day that has
            // been clean, and pretending otherwise would only re-ask for something already true.
            peakSatiety = state.stats.satiety,
            peakHappiness = state.stats.happiness,
            peakEnergy = state.stats.energy,
            peakHygiene = state.stats.hygiene,
        )
    }
}

/**
 * One day's goal. [progressOf] reads the counters rather than a stored tally, so progress
 * survives a crash, a reinstall from backup, or the clock jumping — there is no separate
 * number that can drift out of step with what the pet actually did.
 */
data class Mission(
    val id: String,
    val title: String,
    val description: String,
    val target: Int,
    val rewardCoins: Int,
    val rewardXp: Int,
    val progressOf: (PetState, DayLedger) -> Int,
)

/** A mission with today's progress attached, ready to render. */
data class MissionProgress(
    val mission: Mission,
    val done: Int,
    val claimed: Boolean,
) {
    val complete: Boolean get() = done >= mission.target
    /** 0..1, for a meter. */
    val fraction: Float get() = (done.toFloat() / mission.target.coerceAtLeast(1)).coerceIn(0f, 1f)
    val claimable: Boolean get() = complete && !claimed
}

/** What a day's worth of missions paid out, so the UI can show one banner instead of three. */
data class MissionReward(val coins: Int, val xp: Int, val missions: List<Mission>)

/**
 * Daily missions and the care streak.
 *
 * The point of both is to give a player who opens the app for ninety seconds something to
 * *finish*. A tamagotchi's needs never resolve — satiety starts falling the moment you feed —
 * so without a goal that closes, a short visit feels like bailing water. Three small goals a
 * day close.
 *
 * The streak is the counterweight. Missions reward the visit; the streak rewards the habit,
 * and it only survives consecutive days, which is the one thing this game actually asks of you.
 */
object Missions {

    /** Missions are drawn from here, three per day, chosen by the day index. */
    val pool: List<Mission> = listOf(
        Mission("m_feed", "Three square meals", "Feed your pet 3 times.", 3, 18, 12) { s, d ->
            s.mealsEaten - d.meals
        },
        Mission("m_feast", "Second helpings", "Feed your pet 6 times.", 6, 32, 22) { s, d ->
            s.mealsEaten - d.meals
        },
        Mission("m_play", "Playtime", "Play 2 minigames.", 2, 20, 15) { s, d ->
            s.gamesPlayed - d.games
        },
        Mission("m_win", "On a roll", "Win 2 minigames.", 2, 30, 25) { s, d ->
            s.gamesWon - d.wins
        },
        Mission("m_clean", "Tidy up", "Clean up 3 times.", 3, 18, 12) { s, d ->
            s.cleanups - d.cleanups
        },
        Mission("m_praise", "Kind words", "Praise your pet 2 times.", 2, 14, 10) { s, d ->
            s.praises - d.praises
        },
        // The four stat goals ask what the day *reached*, not what it ends on. See [DayLedger].
        Mission("m_bath", "Squeaky clean", "Get hygiene above 90.", 1, 16, 12) { s, d ->
            if (maxOf(s.stats.hygiene, d.peakHygiene) > 90f) 1 else 0
        },
        Mission("m_full", "Well fed", "Get satiety above 90.", 1, 16, 12) { s, d ->
            if (maxOf(s.stats.satiety, d.peakSatiety) > 90f) 1 else 0
        },
        Mission("m_happy", "Beaming", "Get happiness above 90.", 1, 18, 14) { s, d ->
            if (maxOf(s.stats.happiness, d.peakHappiness) > 90f) 1 else 0
        },
        Mission("m_rested", "Well rested", "Get energy above 90.", 1, 16, 12) { s, d ->
            if (maxOf(s.stats.energy, d.peakEnergy) > 90f) 1 else 0
        },
        Mission("m_flawless", "Not a single slip", "Get through the day with no care mistakes.", 1, 34, 26) { s, d ->
            if (s.careMistakes == d.mistakes) 1 else 0
        },
    )

    private val byId = pool.associateBy { it.id }

    /** How many missions a day offers. Three is short enough to finish on one commute. */
    const val PER_DAY = 3

    /** A streak this long pays the maximum bonus; past it the number is its own reward. */
    private const val STREAK_BONUS_CAP = 7
    private const val STREAK_BONUS_PER_DAY = 8

    /**
     * Today's three, chosen from [pool] by the day index.
     *
     * Seeded by the day rather than the save's RNG so the same day always offers the same
     * goals: a player who closes the app mid-mission comes back to the mission they were
     * doing, not to a fresh set that quietly threw away their progress.
     */
    fun forDay(dayIndex: Int): List<Mission> =
        pool.shuffled(Random(dayIndex * 7919L + 13L)).take(PER_DAY)

    /** Today's missions with progress and claim state filled in. */
    fun today(state: PetState): List<MissionProgress> {
        val ledger = state.dayLedger
        return forDay(ledger.dayIndex).map { mission ->
            MissionProgress(
                mission = mission,
                done = mission.progressOf(state, ledger).coerceIn(0, mission.target),
                claimed = mission.id in state.claimedMissionIds,
            )
        }
    }

    /**
     * Writes today's high-water marks into the ledger.
     *
     * Cheap enough to call from the tick loop: it compares four floats and returns the same
     * instance when nothing has improved. It has to run *during* the day rather than at
     * roll-over, because at roll-over the evidence is already gone — that is precisely the
     * failure it exists to stop.
     */
    fun observe(state: PetState): PetState {
        val l = state.dayLedger
        val s = state.stats
        if (s.satiety <= l.peakSatiety && s.happiness <= l.peakHappiness &&
            s.energy <= l.peakEnergy && s.hygiene <= l.peakHygiene
        ) {
            return state
        }
        return state.copy(
            dayLedger = l.copy(
                peakSatiety = maxOf(l.peakSatiety, s.satiety),
                peakHappiness = maxOf(l.peakHappiness, s.happiness),
                peakEnergy = maxOf(l.peakEnergy, s.energy),
                peakHygiene = maxOf(l.peakHygiene, s.hygiene),
            ),
        )
    }

    /** True when every one of today's missions is finished, claimed or not. */
    fun allComplete(state: PetState): Boolean =
        today(state).all { it.complete }

    /**
     * Pays out every finished mission that has not been collected yet.
     *
     * Claiming is explicit rather than automatic because the payout is the moment the visit
     * resolves — silently topping up the coin counter while the player is elsewhere spends the
     * only satisfying beat the feature has.
     */
    fun claim(state: PetState, events: MutableList<GameEvent>): Pair<PetState, MissionReward> {
        val claimable = today(state).filter { it.claimable }
        if (claimable.isEmpty()) return state to MissionReward(0, 0, emptyList())

        val coins = claimable.sumOf { it.mission.rewardCoins }
        val xp = claimable.sumOf { it.mission.rewardXp }
        // Routed through applyXp rather than added raw, so a mission can push the keeper over a
        // level boundary and the level-up is announced like any other.
        val paid = Simulation.applyXp(
            state.copy(
                coins = state.coins + coins,
                claimedMissionIds = state.claimedMissionIds + claimable.map { it.mission.id },
            ),
            xp,
            events,
        )
        return paid to MissionReward(coins, xp, claimable.map { it.mission })
    }

    /**
     * Bonus coins for keeping a streak alive, paid when the day rolls over.
     * Flat above [STREAK_BONUS_CAP] so a long streak stops inflating the economy.
     */
    fun streakBonus(streakDays: Int): Int =
        streakDays.coerceIn(0, STREAK_BONUS_CAP) * STREAK_BONUS_PER_DAY

    /**
     * Closes out the previous pet day and opens a new one, if the day has turned.
     *
     * The streak only continues on a day that directly follows the last one. Coming back after
     * a two-day gap breaks it — otherwise "streak" would measure nothing but how many times you
     * have ever opened the app.
     */
    fun rollOver(
        state: PetState,
        config: GameConfig,
        events: MutableList<GameEvent>,
    ): PetState {
        val today = state.ageInPetDays(config)
        val ledger = state.dayLedger
        if (today == ledger.dayIndex) return state

        val fresh = state.copy(dayLedger = DayLedger.of(state, today), claimedMissionIds = emptySet())

        // A forward jump of more than a day has no "yesterday" to grade: the player was not there
        // for those days, and grading them against a ledger that is stale by definition would
        // either hand out a perfect day for an absence or punish a save upgraded from a build
        // that had no ledger at all. That is a design decision, and the streak goes.
        if (today > ledger.dayIndex + 1) {
            if (state.careStreakDays > 0) {
                events += GameEvent.Message("Streak lost after ${state.careStreakDays} days.")
            }
            return fresh.copy(careStreakDays = 0)
        }

        // A jump *backwards* is not an absence, and cannot be: nobody is away for negative time.
        // It is the age counter starting over -- a new generation hatching at zero, or a day
        // length that grew under a pet that did not age. Both were being read as a skipped day,
        // so the player was told "Streak lost" at a funeral and the streak was taken.
        //
        // Which made this a fight between two places. `Simulation.nextGeneration` carries
        // `careStreakDays` over on purpose, because the streak counts days the *player* showed
        // up and a funeral is not a day skipped -- and then the next roll-over quietly undid it.
        // The message was the only part of that the player could see.
        //
        // The day is re-labelled onto the new clock and nothing is graded: no message, no reset,
        // and equally no bonus. Not grading a day has to mean not grading it in either direction,
        // or re-basing the ledger becomes a way to collect a perfect day that was never lived.
        if (today < ledger.dayIndex) return fresh

        val perfect = allComplete(state)
        val streak = if (perfect) state.careStreakDays + 1 else 0
        if (perfect) {
            events += GameEvent.Message(
                if (streak > 1) "All missions done — $streak day streak!" else "All missions done!",
            )
        } else if (state.careStreakDays > 0) {
            events += GameEvent.Message("Streak lost after ${state.careStreakDays} days.")
        }

        return fresh.copy(
            careStreakDays = streak,
            bestCareStreak = maxOf(state.bestCareStreak, streak),
            coins = state.coins + streakBonus(streak),
        )
    }

    fun get(id: String): Mission? = byId[id]
}
