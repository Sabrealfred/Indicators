package com.neopal.pet.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The animation the creature should play as a reaction to an action. */
enum class PetAnimation {
    IDLE, EAT, HAPPY, PLAY, SLEEP, WAKE, CLEAN, HEAL, REFUSE, SCOLD, PRAISE, EVOLVE, HATCH, LEVEL_UP, DEAD
}

/**
 * Whose hands did it.
 *
 * A care action changes two separate things and they were treated as one: the *world* (the tin
 * empties, the mess is gone, the pet is no longer ill) and the *keeper's ledger* (experience, a
 * level, a badge and its coins, a tick on today's mission). The world is a fact about what
 * happened and belongs to whoever caused it. The ledger is a record of what the **player** did,
 * and a creature that lets itself in at the pantry has not added a line to it.
 *
 * [SoloPlay] already says this for the minigames — a creature playing alone posts no score,
 * because the high scores are the player's record of their own afternoons. This is the same rule
 * with the same reason, applied to the rest of what a creature can do for itself.
 *
 * Only the actions [Brain] can reach take this parameter. Anything new that the brain learns to
 * commit has to take it too, or the ledger leaks again. [Learning] is the other user: a skill pays
 * experience when a lesson finished it and not when the creature finished it alone, for this
 * reason and by this enum, rather than by a second mechanism of its own.
 */
enum class Actor { KEEPER, CREATURE }

/** Result of one player interaction: the new state, what to animate, and what to say. */
data class ActionResult(
    val state: PetState,
    val animation: PetAnimation = PetAnimation.IDLE,
    val toast: String? = null,
    val events: List<GameEvent> = emptyList(),
    val accepted: Boolean = true,
)

/**
 * Every direct player interaction. Each function is pure: give it a state, get a new one.
 * Nothing here touches storage, time or Android APIs, which keeps it unit-testable.
 */
object CareActions {

    private fun finish(
        state: PetState,
        animation: PetAnimation,
        toast: String?,
        events: MutableList<GameEvent>,
        accepted: Boolean = true,
        by: Actor = Actor.KEEPER,
    ): ActionResult {
        // The moment a player scrubs a pet clean is the moment "get hygiene above 90" is true,
        // and it stops being true minutes later. Recording it here rather than waiting for the
        // next tick means the credit is never a race against the drain.
        //
        // Recorded for a creature's own care as well, and deliberately so. This is a high-water
        // mark of what the pet's condition actually reached, and the tick loop raises it from the
        // live stats a moment later whoever caused them — so gating it on the actor would not
        // withhold the credit, it would only make it depend on which of the two ran first. What
        // the gate below governs is the keeper's *ledger*: the repetition counters, the
        // experience, and the awards.
        val observed = Missions.observe(state)

        // Awards are the keeper's. Nothing is lost by not evaluating them here for a creature:
        // [Simulation.advance] evaluates the whole list every time the world moves, so the ones
        // that read the pet's *condition* — bonded, well raised, every stat above eighty — still
        // fire for a creature that looked after itself well. It is only the ones that count the
        // player's own repetitions that stop advancing, which is the point.
        if (by != Actor.KEEPER) return ActionResult(observed, animation, toast, events.toList(), accepted)
        val (withAchievements, unlocked) = Achievements.evaluate(observed)
        unlocked.forEach { events += GameEvent.Unlocked(it) }
        return ActionResult(withAchievements, animation, toast, events.toList(), accepted)
    }

    /** Experience is the keeper's level, so only the keeper's own actions pay into it. */
    private fun credit(state: PetState, by: Actor, amount: Int, events: MutableList<GameEvent>): PetState =
        if (by == Actor.KEEPER) Simulation.applyXp(state, amount, events) else state

    private fun blocked(state: PetState, toast: String): ActionResult =
        ActionResult(state, PetAnimation.REFUSE, toast, emptyList(), accepted = false)

    /** Shared guard: eggs, sleeping pets and dead pets do not accept most interactions. */
    private fun guard(state: PetState, allowWhileAsleep: Boolean = false): String? = when {
        state.isDead -> "${state.name} is no longer with us."
        state.isEgg -> "The egg is still warming up..."
        state.isSleeping && !allowWhileAsleep -> "${state.name} is fast asleep."
        else -> null
    }

    // ---------------------------------------------------------------- feeding

    fun feed(state: PetState, itemId: String, by: Actor = Actor.KEEPER): ActionResult {
        guard(state)?.let { return blocked(state, it) }
        val item = ItemCatalog[itemId] ?: return blocked(state, "Nothing to serve.")
        if (!item.isConsumable) return blocked(state, "${item.name} is not food.")
        if ((state.inventory[itemId] ?: 0) <= 0) return blocked(state, "You are out of ${item.name}.")
        if (item.kind != ItemKind.MEDICINE && state.stats.satiety >= 96f) {
            return blocked(state, "${state.name} is completely full.")
        }
        // Sulking only refuses food it does not need; hunger always wins over mood.
        if (state.isSulking && state.stats.satiety > 40f) {
            return blocked(state, "${state.name} is too miserable for treats. Try petting or playing first.")
        }

        val events = mutableListOf<GameEvent>()
        var s = consume(state, itemId)
        s = s.copy(
            stats = s.stats.copy(
                satiety = s.stats.satiety + item.satiety,
                happiness = s.stats.happiness + item.happiness,
                energy = s.stats.energy + item.energy,
                hygiene = s.stats.hygiene + item.hygiene,
                health = s.stats.health + item.health,
                bond = s.stats.bond + item.bond + 1f,
            ).coerced(),
            weightGrams = (s.weightGrams + item.weight).coerceIn(6f, 120f),
            // "Meals served" — the record, the mission and the chef badge all read this. A meal
            // the creature helped itself to still shows up on the scales, which is where a life
            // of self-service ought to show up; it does not show up as something you did.
            mealsEaten = s.mealsEaten + if (by == Actor.KEEPER && item.kind == ItemKind.MEAL) 1 else 0,
        )
        s = credit(s, by, if (item.kind == ItemKind.MEAL) 6 else 3, events)
        return finish(s, PetAnimation.EAT, "${state.name} ate the ${item.name}.", events, by = by)
    }

    fun useMedicine(state: PetState, itemId: String = "medicine", by: Actor = Actor.KEEPER): ActionResult {
        if (state.isDead) return blocked(state, "Too late for medicine.")
        val item = ItemCatalog[itemId] ?: return blocked(state, "Unknown medicine.")
        if ((state.inventory[itemId] ?: 0) <= 0) return blocked(state, "You are out of ${item.name}.")
        if (!state.isSick && state.stats.health > 95f) return blocked(state, "${state.name} feels fine.")

        val events = mutableListOf<GameEvent>()
        var s = consume(state, itemId)
        val cured = state.isSick && (itemId == "medicine_super" || s.stats.health + item.health >= 60f)
        s = s.copy(
            stats = s.stats.copy(
                health = s.stats.health + item.health,
                happiness = s.stats.happiness + item.happiness,
            ).coerced(),
            isSick = if (cured) false else s.isSick,
            // Getting better is the world; "cures" is the nurse badge, and nursing is a thing one
            // party does to another.
            medicineDoses = s.medicineDoses + if (by == Actor.KEEPER && cured) 1 else 0,
        )
        // The recovery itself is announced whoever swallowed the dose: it is news about the pet.
        if (cured) events += GameEvent.Recovered
        s = credit(s, by, 10, events)
        val message = if (cured) "${state.name} is feeling better!" else "${state.name} needs another dose."
        return finish(s, PetAnimation.HEAL, message, events, by = by)
    }

    // ---------------------------------------------------------------- hygiene

    fun cleanRoom(state: PetState): ActionResult {
        if (state.isDead) return blocked(state, "Nothing to clean up.")
        if (state.poops == 0 && state.stats.hygiene > 92f) return blocked(state, "Everything is already spotless.")
        val events = mutableListOf<GameEvent>()
        var s = state.copy(
            poops = 0,
            cleanups = state.cleanups + 1,
            stats = state.stats.copy(
                hygiene = state.stats.hygiene + 40f + state.poops * 8f,
                happiness = state.stats.happiness + 6f,
            ).coerced(),
        )
        s = Simulation.applyXp(s, 5, events)
        return finish(s, PetAnimation.CLEAN, "Room cleaned.", events)
    }

    /**
     * Removes one mess. Tapping a pile directly is more tactile than a menu button, and
     * scooping each one keeps the reward proportional to the effort.
     */
    fun scoopPoop(state: PetState, by: Actor = Actor.KEEPER): ActionResult {
        if (state.poops <= 0) return blocked(state, "Nothing to scoop.")
        val events = mutableListOf<GameEvent>()
        var s = state.copy(
            poops = state.poops - 1,
            cleanups = state.cleanups + if (by == Actor.KEEPER) 1 else 0,
            stats = state.stats.copy(
                hygiene = state.stats.hygiene + 14f,
                happiness = state.stats.happiness + 1.5f,
            ).coerced(),
        )
        s = credit(s, by, 2, events)
        val left = s.poops
        val message = if (left == 0) "All clean!" else "$left left to scoop."
        return finish(s, PetAnimation.CLEAN, message, events, by = by)
    }

    /** Swipe-up reaction: a little toss in the air. Costs a sliver of energy, pays in mood. */
    fun toss(state: PetState): ActionResult {
        guard(state)?.let { return blocked(state, it) }
        if (state.stats.energy < 6f) return blocked(state, "${state.name} is too tired for that.")
        val events = mutableListOf<GameEvent>()
        var s = state.copy(
            stats = state.stats.copy(
                happiness = state.stats.happiness + 6f,
                energy = state.stats.energy - 2f,
                bond = state.stats.bond + 1f,
            ).coerced(),
        )
        s = Simulation.applyXp(s, 2, events)
        return finish(s, PetAnimation.PLAY, "Wheee!", events)
    }

    /** Double-tap reaction: pure delight, no stat cost, capped so it cannot be farmed. */
    fun tickle(state: PetState): ActionResult {
        guard(state)?.let { return blocked(state, it) }
        if (state.stats.happiness >= 99f) return blocked(state, "${state.name} is already over the moon.")
        val events = mutableListOf<GameEvent>()
        var s = state.copy(
            stats = state.stats.copy(
                happiness = state.stats.happiness + 3.5f,
                bond = state.stats.bond + 1.5f,
                energy = state.stats.energy - 0.4f,
            ).coerced(),
        )
        s = Simulation.applyXp(s, 1, events)
        return finish(s, PetAnimation.HAPPY, null, events)
    }

    fun bathe(state: PetState): ActionResult {
        guard(state)?.let { return blocked(state, it) }
        if ((state.inventory["soap"] ?: 0) <= 0) return blocked(state, "You need Bubble Soap.")
        val events = mutableListOf<GameEvent>()
        var s = consume(state, "soap").let {
            it.copy(
                cleanups = it.cleanups + 1,
                stats = it.stats.copy(hygiene = 100f, happiness = it.stats.happiness + 6f, bond = it.stats.bond + 2f).coerced(),
            )
        }
        s = Simulation.applyXp(s, 6, events)
        return finish(s, PetAnimation.CLEAN, "${state.name} is squeaky clean!", events)
    }

    // ---------------------------------------------------------------- rest

    fun toggleLights(state: PetState): ActionResult {
        if (state.isDead || state.isEgg) return blocked(state, "Nothing happens.")
        val lightsOff = !state.lightsOff
        val s = state.copy(lightsOff = lightsOff)
        val toast = if (lightsOff) "Lights out. Good night." else "Lights on."
        return ActionResult(s, if (lightsOff) PetAnimation.SLEEP else PetAnimation.WAKE, toast)
    }

    /** Manual nap. Refused if the pet is wide awake — it will not sleep on demand. */
    fun putToSleep(state: PetState): ActionResult {
        guard(state)?.let { return blocked(state, it) }
        if (state.stats.energy > 60f) return blocked(state, "${state.name} is not sleepy at all.")
        val s = state.copy(isSleeping = true, lightsOff = true)
        return ActionResult(s, PetAnimation.SLEEP, "${state.name} curls up and dozes off.")
    }

    fun wake(state: PetState): ActionResult {
        if (!state.isSleeping) return blocked(state, "${state.name} is already awake.")
        val s = state.copy(
            isSleeping = false,
            lightsOff = false,
            stats = state.stats.copy(happiness = state.stats.happiness - 6f).coerced(),
        )
        return ActionResult(s, PetAnimation.WAKE, "You woke ${state.name} up early.")
    }

    // ---------------------------------------------------------------- affection & discipline

    fun pet(state: PetState): ActionResult {
        guard(state)?.let { return blocked(state, it) }
        val events = mutableListOf<GameEvent>()
        var s = state.copy(
            stats = state.stats.copy(
                happiness = state.stats.happiness + 7f,
                bond = state.stats.bond + 2.5f,
            ).coerced(),
        )
        s = Simulation.applyXp(s, 2, events)
        return finish(s, PetAnimation.HAPPY, null, events)
    }

    fun praise(state: PetState): ActionResult {
        guard(state)?.let { return blocked(state, it) }
        val events = mutableListOf<GameEvent>()
        var s = state.copy(
            praises = state.praises + 1,
            stats = state.stats.copy(
                happiness = state.stats.happiness + 10f,
                bond = state.stats.bond + 4f,
                // Consistent encouragement teaches as well as telling off does, and costs nothing.
                discipline = state.stats.discipline + 6f,
            ).coerced(),
        )
        s = Simulation.applyXp(s, 4, events)
        return finish(s, PetAnimation.PRAISE, "${state.name} beams with pride!", events)
    }

    /**
     * Scolding raises discipline but costs happiness. It only works when the pet actually
     * misbehaved (sulking, refusing food, or calling for attention).
     */
    fun scold(state: PetState): ActionResult {
        guard(state)?.let { return blocked(state, it) }
        val deserved = state.isSulking || state.stats.discipline < 60f
        if (!deserved) return blocked(state, "${state.name} did nothing wrong.")
        val events = mutableListOf<GameEvent>()
        var s = state.copy(
            scolds = state.scolds + 1,
            stats = state.stats.copy(
                discipline = state.stats.discipline + 7f,
                happiness = state.stats.happiness - 6f,
                bond = state.stats.bond - 2f,
            ).coerced(),
        )
        s = Simulation.applyXp(s, 3, events)
        return finish(s, PetAnimation.SCOLD, "${state.name} looks down, then nods.", events)
    }

    // ---------------------------------------------------------------- play

    /** Can the pet start a minigame right now? */
    fun canPlay(state: PetState): String? = when {
        state.isDead -> "${state.name} is no longer with us."
        state.isEgg -> "Wait for the egg to hatch."
        state.isSleeping -> "${state.name} is asleep."
        state.stats.energy < 12f -> "${state.name} is too tired to play."
        state.isSick -> "${state.name} is too sick to play."
        else -> null
    }

    /**
     * Applies the outcome of a minigame. [score] is normalised 0..1 by each game so the
     * rewards stay comparable no matter which one was played.
     */
    fun finishGame(
        state: PetState,
        won: Boolean,
        score: Float,
        gameName: String,
        gameId: String = gameName,
        points: Int = 0,
    ): ActionResult {
        val normalized = score.coerceIn(0f, 1f)
        val events = mutableListOf<GameEvent>()
        val coins = (8 + normalized * 30f * state.species.playBias).roundToInt()
        val previousBest = state.highScores[gameId] ?: 0
        val isRecord = points > previousBest
        var s = state.copy(
            highScores = if (isRecord) state.highScores + (gameId to points) else state.highScores,
            gamesPlayed = state.gamesPlayed + 1,
            gamesWon = state.gamesWon + if (won) 1 else 0,
            coins = state.coins + coins,
            stats = state.stats.copy(
                happiness = state.stats.happiness + (if (won) 18f else 8f) * state.species.playBias,
                energy = state.stats.energy - (6f + normalized * 6f),
                bond = state.stats.bond + if (won) 3f else 1.5f,
                satiety = state.stats.satiety - 3f,
            ).coerced(),
            weightGrams = max(6f, state.weightGrams - 0.6f),
        )
        s = Simulation.applyXp(s, if (won) 22 else 10, events)
        val toast = when {
            isRecord && points > 0 -> "New record: $points! +$coins coins"
            won -> "$gameName cleared! +$coins coins"
            else -> "$gameName over. +$coins coins"
        }
        return finish(s, PetAnimation.PLAY, toast, events)
    }

    // ---------------------------------------------------------------- economy

    fun buy(state: PetState, itemId: String): ActionResult {
        val item = ItemCatalog[itemId] ?: return blocked(state, "Unknown item.")
        // Durable, not cosmetic: a toy is never used up either, so a second copy is a coin sink
        // that buys the player nothing at all.
        if (item.isDurable && (state.inventory[itemId] ?: 0) > 0) {
            return blocked(state, "You already own ${item.name}.")
        }
        if (state.coins < item.price) return blocked(state, "Not enough coins.")
        val events = mutableListOf<GameEvent>()
        val s = state.copy(
            coins = state.coins - item.price,
            inventory = state.inventory + (itemId to (state.inventory[itemId] ?: 0) + 1),
        )
        return finish(s, PetAnimation.HAPPY, "Bought ${item.name}.", events)
    }

    /**
     * Uses [itemId] for whatever it is actually for.
     *
     * One place decides, because the screens were each deciding separately and two of them were
     * wrong. The pantry sheet listed every medicine the player owned and sent all of them to
     * [feed] — so tapping the Pill said "Pip ate the Pill", consumed the only dose, and left the
     * creature sick, on its way to dying of an illness the player believed they had treated. The
     * shop sent Bubble Soap to [useMedicine], which applies health and happiness and *not*
     * hygiene, so soap washed nothing and, because the cure test reduces to "is health already
     * over 60" when the item heals nothing, a ten-coin bar of soap cured illness instead.
     *
     * Routing on the item's own effects rather than on its `kind` is what fixes both: soap is
     * catalogued as MEDICINE and always will be, and a kind is a shelf category, not a verb.
     */
    fun use(state: PetState, itemId: String): ActionResult {
        val item = ItemCatalog[itemId] ?: return blocked(state, "There is no such thing.")
        return when {
            item.kind == ItemKind.HAT -> equipHat(state, if (state.equippedHat == itemId) null else itemId)
            item.kind == ItemKind.ROOM -> setRoom(state, itemId)
            // Washing before curing: soap heals nothing, so a rule that asked about health first
            // would send it down the medicine path again.
            item.hygiene > 0f && item.health <= 0f -> bathe(state)
            item.kind == ItemKind.MEDICINE -> useMedicine(state, itemId)
            item.kind == ItemKind.MEAL || item.kind == ItemKind.SNACK -> feed(state, itemId)
            else -> blocked(state, "${state.name} does not know what to do with the ${item.name}.")
        }
    }

    fun equipHat(state: PetState, hatId: String?): ActionResult {
        if (hatId != null && (state.inventory[hatId] ?: 0) <= 0) return blocked(state, "You do not own that yet.")
        val s = state.copy(equippedHat = hatId, stats = state.stats.copy(happiness = state.stats.happiness + 2f).coerced())
        return ActionResult(s, PetAnimation.HAPPY, hatId?.let { "Wearing ${ItemCatalog.require(it).name}." } ?: "Hat removed.")
    }

    fun setRoom(state: PetState, roomId: String): ActionResult {
        if ((state.inventory[roomId] ?: 0) <= 0 && roomId != "room_default") {
            return blocked(state, "You do not own that room.")
        }
        val s = state.copy(roomTheme = roomId, stats = state.stats.copy(happiness = state.stats.happiness + 3f).coerced())
        return ActionResult(s, PetAnimation.HAPPY, "Room changed.")
    }

    fun rename(state: PetState, name: String): ActionResult {
        val trimmed = name.trim().take(12)
        if (trimmed.isEmpty()) return blocked(state, "Pick a name first.")
        return ActionResult(state.copy(name = trimmed), PetAnimation.HAPPY, "Say hello to $trimmed!")
    }

    /** Photo mode: stores the current look in the album. */
    fun snapshot(state: PetState, title: String, nowMillis: Long): ActionResult {
        if (state.isEgg) return blocked(state, "There is nothing to photograph yet.")
        val entry = AlbumEntry(
            id = "snap_${nowMillis}",
            title = title.ifBlank { "${state.name}, ${state.stage.displayName}" },
            species = state.species,
            stage = state.stage,
            branch = state.branch,
            hatId = state.equippedHat,
            roomTheme = state.roomTheme,
            petAgeSeconds = state.ageSeconds,
            capturedAtMillis = nowMillis,
            genome = state.genome,
        )
        val events = mutableListOf<GameEvent>()
        val s = state.copy(album = trimAlbum(state.album + entry))
        return finish(s, PetAnimation.HAPPY, "Picture saved to the album.", events)
    }

    /** Evolutions are the album's spine; manual snapshots are what gets recycled when it fills. */
    private fun trimAlbum(album: List<AlbumEntry>): List<AlbumEntry> {
        if (album.size <= ALBUM_LIMIT) return album
        val evolutions = album.filter { it.id.startsWith("evo_") }
        val snapshots = album.filter { !it.id.startsWith("evo_") }
        val room = (ALBUM_LIMIT - evolutions.size).coerceAtLeast(0)
        return (evolutions + snapshots.takeLast(room)).sortedBy { it.petAgeSeconds }.takeLast(ALBUM_LIMIT)
    }

    private const val ALBUM_LIMIT = 60

    private fun consume(state: PetState, itemId: String): PetState {
        val left = (state.inventory[itemId] ?: 0) - 1
        val inventory = if (left <= 0) state.inventory - itemId else state.inventory + (itemId to left)
        return state.copy(inventory = inventory)
    }

    /** Convenience for the UI: how many of an item is left. */
    fun count(state: PetState, itemId: String): Int = state.inventory[itemId] ?: 0

    /** Highest-priority need, used for the "what does it want" hint bubble. */
    fun topNeed(state: PetState): String? {
        if (state.isDead || state.isEgg) return null
        val needs = listOf(
            "Hungry" to (100f - state.stats.satiety),
            "Bored" to (100f - state.stats.happiness),
            "Sleepy" to (100f - state.stats.energy),
            "Dirty" to (100f - state.stats.hygiene) + state.poops * 15f,
            "Sick" to if (state.isSick) 200f else 0f,
        )
        val (label, severity) = needs.maxBy { it.second }
        return if (severity >= min(65f, 100f)) label else null
    }
}
