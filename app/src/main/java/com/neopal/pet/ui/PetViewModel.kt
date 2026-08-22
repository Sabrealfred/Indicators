package com.neopal.pet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.neopal.pet.audio.ChiptuneEngine
import com.neopal.pet.audio.Sfx
import com.neopal.pet.data.PetRepository
import com.neopal.pet.data.RemoteMindClient
import com.neopal.pet.domain.Achievement
import com.neopal.pet.domain.ActionResult
import com.neopal.pet.domain.Autonomy
import com.neopal.pet.domain.Brain
import com.neopal.pet.domain.Cadence
import com.neopal.pet.domain.CareActions
import com.neopal.pet.domain.Colony
import com.neopal.pet.domain.Consideration
import com.neopal.pet.domain.Errands
import com.neopal.pet.domain.ToolId
import com.neopal.pet.domain.Chronicle
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.GameEvent
import com.neopal.pet.domain.ChatTurn
import com.neopal.pet.domain.Learning
import com.neopal.pet.domain.MindConfig
import com.neopal.pet.domain.MindProvider
import com.neopal.pet.domain.NoMind
import com.neopal.pet.domain.PetBrief
import com.neopal.pet.domain.MissionProgress
import com.neopal.pet.domain.Missions
import com.neopal.pet.domain.PetAnimation
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.Pal
import com.neopal.pet.domain.Relation
import com.neopal.pet.domain.Simulation
import com.neopal.pet.domain.Skill
import com.neopal.pet.domain.Species
import com.neopal.pet.domain.StatDelta
import com.neopal.pet.domain.statDeltas
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the running game: the foreground clock, the save file, and the reactions the UI plays.
 * Screens observe [ui] and call the action methods; nothing else touches the domain directly.
 */
class PetViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val pet: PetState? = null,
        val config: GameConfig = GameConfig.Default,
        val loading: Boolean = true,
        val animation: PetAnimation = PetAnimation.IDLE,
        /** Bumped on every reaction so repeating the same animation still restarts it. */
        val animationId: Long = 0L,
        val toast: String? = null,
        /** The "+12 MOOD" readouts for the most recent action. */
        val deltas: List<StatDelta> = emptyList(),
        /** The item currently being eaten, so the scene can show it disappearing. */
        val servedItemId: String? = null,
        val achievementBanner: Achievement? = null,
        /** Filled after a long absence so the player learns what they missed. */
        val offlineReport: OfflineReport? = null,
        val evolutionSplash: Boolean = false,
        /**
         * True while the creature is composing a reply.
         *
         * Lives here rather than as a field on the view model because Compose only recomposes on
         * state it can observe: a plain `var` flips, the screen never hears about it, and the
         * thinking line appears whenever the next unrelated emission happens to arrive.
         */
        val thinking: Boolean = false,
    )

    /** What happened while the app was closed, in plain sentences. */
    data class OfflineReport(val minutesAway: Long, val lines: List<String>, val petName: String)

    private val repository = PetRepository(application)

    /**
     * The remote brain, or the one that never answers.
     *
     * Held as the interface rather than the concrete client so the game is complete without one:
     * when nothing is configured every call returns null, the local [com.neopal.pet.domain.Brain]
     * answers instead, and nothing anywhere has to branch on whether a model is set up.
     *
     * The client reads the config through a lambda rather than being handed a copy, so a key
     * pasted in settings takes effect on the next call instead of on the next launch. [NoMind]
     * remains as the shape of "never answers", which is what the client already behaves like
     * until a route exists.
     */
    private var mind: MindProvider = RemoteMindClient { _ui.value.config.mind }

    /** One reply at a time; a second send would race the first. */
    private var chatJob: Job? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var saveJob: Job? = null
    /**
     * The foreground clock only runs while the screen is actually in front of someone. Left
     * running in the background it fed the simulation a stream of one-second ticks, which look
     * like presence, not absence — so the gentler offline rates and the health floor never
     * applied to a phone sitting in a pocket.
     */
    @Volatile
    private var inForeground: Boolean = true
    private var toastJob: Job? = null

    init {
        viewModelScope.launch {
            val config = repository.currentConfig()
            val saved = repository.currentState()
            ChiptuneEngine.enabled = config.soundEnabled
            ChiptuneEngine.volume = config.sfxVolume
            if (saved == null) {
                _ui.update { it.copy(loading = false, config = config, pet = null) }
            } else {
                val result = Simulation.advance(saved, System.currentTimeMillis(), config)
                val report = buildOfflineReport(saved, result.state, result.events)
                _ui.update { it.copy(loading = false, config = config, pet = result.state, offlineReport = report) }
                handleEvents(result.events, offline = true)
                persist(result.state)
            }
            runClock()
        }
    }

    /** One tick per second while the app is in the foreground. */
    private fun runClock() {
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                if (!inForeground) continue
                val current = _ui.value
                val pet = current.pet ?: continue
                val result = Simulation.advance(pet, System.currentTimeMillis(), current.config)
                if (result.state != pet) {
                    _ui.update { it.copy(pet = result.state) }
                    persist(result.state)
                }
                handleEvents(result.events, offline = false)
                maybeReconsider(result.events)
                maybePlan()
                maybeSpeakFirst(result.events)
            }
        }
    }

    /**
     * Offers the local brain's fresh decision to the remote one, and takes its answer if it has
     * a better idea.
     *
     * This is deliberately *after* the local brain has already chosen and acted, not instead of
     * it. A network call cannot live inside the simulation step: the step loop is pure and
     * synchronous, which is the property that makes the whole domain testable, and running up to
     * two thousand of them during a catch-up would be two thousand requests. So the local brain
     * decides, the creature acts, and only then is the model asked whether it would have done
     * something else. When it would, [Brain.adopt] re-checks that the option is still legal and
     * the creature changes its mind visibly, which is a thing creatures do.
     *
     * Throttled hard. A free model tier is a small number of calls, and a pet that thinks out
     * loud every fifteen seconds would spend the whole allowance before lunch.
     */
    private fun maybeReconsider(events: List<GameEvent>) {
        if (events.none { it is GameEvent.Decided }) return
        val current = _ui.value
        val pet = current.pet ?: return
        val config = current.config
        if (!config.mind.usable || !config.mind.decidesActions || !mind.isReady) return
        if (reconsidering) return
        if (!dueFor(Cadence.RECONSIDER_SECONDS, lastReconsideredAtSeconds, pet)) return

        val options = Brain.considerations(pet, config)
        // Nothing to reconsider when there is no real alternative to the thing it just did.
        if (options.count { it.available } < 2) return

        reconsidering = true
        lastReconsideredAtSeconds = pet.ageSeconds
        viewModelScope.launch {
            val choice = mind.choose(PetBrief.of(pet, config), options)
            reconsidering = false
            if (choice == null) return@launch
            val picked = options.getOrNull(choice.index) ?: return@launch
            // Agreeing with the local brain is the common case and is not worth a second entry
            // in the log; only a change of mind is. The target counts as part of the mind:
            // reaching for the cake instead of the berry is a different decision, not the same
            // one, and it is exactly the sort of difference worth showing the player.
            val busy = pet.activity
            if (picked.kind == busy?.kind && picked.target == busy.targetId) return@launch
            val now = _ui.value.pet ?: return@launch
            val adopted = mutableListOf<GameEvent>()
            val changed = Brain.adopt(
                state = now,
                kind = picked.kind,
                reason = choice.reason,
                config = _ui.value.config,
                random = kotlin.random.Random(now.rngSeed),
                events = adopted,
                // Re-checked against the pantry as it is now, not as it was when the question
                // went out. A target that has been eaten in the meantime refuses rather than
                // silently becoming a different meal.
                target = picked.target,
            ) ?: return@launch
            _ui.update { it.copy(pet = changed) }
            handleEvents(adopted, offline = false)
            persist(changed)
        }
    }

    /**
     * Sets the creature an errand when it has none and is not busy.
     *
     * This is the small agentic loop: it looks around with the read-only tools, all of which are
     * answered locally and for free, and only then spends one request asking what it should be
     * getting on with. Gathering the observations before the call rather than letting the mind ask
     * for them one at a time is the whole economy of it — a real tool conversation is several
     * round trips per plan, and on a free tier that buys about three thoughts a day.
     */
    private fun maybePlan() {
        val current = _ui.value
        val pet = current.pet ?: return
        val config = current.config
        if (!config.mind.usable || !config.mind.makesPlans || !mind.isReady) return
        if (planning || pet.plan != null) return
        if (!pet.isMindAwake || pet.isSleeping || pet.isDead) return
        if (pet.autonomy != Autonomy.FULL) return
        if (!dueFor(Cadence.PLAN_SECONDS, lastPlannedAtSeconds, pet)) return

        val options = Brain.considerations(pet, config).filter { it.available }
        if (options.size < 2) return

        planning = true
        lastPlannedAtSeconds = pet.ageSeconds
        viewModelScope.launch {
            val looked = ToolId.entries.associateWith { Errands.answer(it, pet, config) }
            val proposed = mind.plan(PetBrief.of(pet, config), looked, options)
            planning = false
            if (proposed == null) return@launch
            val now = _ui.value.pet ?: return@launch
            // Stamped against the creature as it is now, not as it was when the request went out,
            // so a plan is never born already halfway to expiring.
            val accepted = Errands.sanitise(proposed, now) ?: return@launch
            if (now.plan != null) return@launch
            val planned = now.copy(plan = accepted)
            _ui.update { it.copy(pet = planned) }
            handleEvents(listOf(GameEvent.PlanMade(accepted.goal, accepted.steps.size)), offline = false)
            persist(planned)
        }
    }

    /** True while a reconsideration is in flight, so they cannot pile up. */
    private var reconsidering = false
    private var lastReconsideredAtSeconds = Cadence.NEVER
    private var planning = false
    private var lastPlannedAtSeconds = Cadence.NEVER

    /** Whether enough pet time has passed, at this creature's own pace. See [Cadence]. */
    private fun dueFor(base: Long, lastAtSeconds: Long, pet: PetState): Boolean =
        Cadence.due(pet.ageSeconds, lastAtSeconds, Cadence.gapFor(base, pet.intellect))

    // ---------------------------------------------------------------- lifecycle

    fun startNewGame(name: String, species: Species) {
        forgetCadence()
        val state = Simulation.newGame(name, species, System.currentTimeMillis())
        _ui.update { it.copy(pet = state, loading = false) }
        play(Sfx.CONFIRM)
        persist(state, immediate = true)
    }

    /**
     * The creature's own children, offered as the next generation.
     *
     * Empty until it has actually bred, which is the point: the reward for courting, laying an
     * egg and raising a child is that the child is who continues.
     */
    fun heirs(): List<Pal> =
        _ui.value.pet?.pals.orEmpty().filter { it.relation == Relation.OFFSPRING }

    /**
     * Starts the next life, from [heirId] when one is named.
     *
     * The heir parameter is the whole of selective breeding. `Simulation.nextGeneration` has
     * always accepted one — the child's genome carries over, its species, its parents' names, the
     * skills it was taught — and nothing ever passed it, so every generation was an unrelated
     * founder and the child a player had bred simply vanished when its parent died. The genetics
     * worked perfectly right up to the moment they were supposed to pay off.
     */
    fun startNextGeneration(name: String, species: Species, heirId: String? = null) {
        val previous = _ui.value.pet ?: return startNewGame(name, species)
        val heir = heirId?.let { id -> previous.pals.firstOrNull { it.id == id && it.relation == Relation.OFFSPRING } }
        val state = Simulation.nextGeneration(
            previous = previous,
            name = name,
            // An heir keeps its own species; picking one is not a choice about species.
            species = heir?.species ?: species,
            nowMillis = System.currentTimeMillis(),
            heir = heir,
        )
        forgetCadence()
        _ui.update { it.copy(pet = state) }
        play(Sfx.CONFIRM)
        persist(state, immediate = true)
    }

    /**
     * Clears the remote-brain throttles at the start of a life.
     *
     * They are stamped in *pet* seconds, and a new generation starts back at zero. Carrying the
     * previous creature's stamps forward meant `age - last` stayed negative for as long as that
     * creature had lived — so a successor to a full life could not think, plan or speak for its
     * entire childhood. Silent, naturally: identical to a creature that was never given a brain.
     */
    private fun forgetCadence() {
        lastReconsideredAtSeconds = Cadence.NEVER
        lastPlannedAtSeconds = Cadence.NEVER
        lastSpokeFirstAtSeconds = Cadence.NEVER
    }

    fun resetEverything() {
        // Cancelled first, and that ordering is the whole fix. `persist` is a single-slot debounce
        // with an 800ms delay and the clock loop refreshes it every second, so at almost any
        // moment there is a write of the current pet already scheduled. Clearing the store
        // without cancelling it lets that write land *after* the removal and put the save back —
        // invisibly, because the screen has already moved on to the new-game screen.
        saveJob?.cancel()
        forgetCadence()
        viewModelScope.launch {
            repository.clear()
            _ui.update { it.copy(pet = null) }
        }
    }

    /** Called when the app leaves the screen, so the world is simulated as time away. */
    fun onPaused() {
        inForeground = false
        _ui.value.pet?.let { persist(it, immediate = true) }
    }

    /** Called when the app returns to the foreground so offline progress lands immediately. */
    fun onResumed() {
        inForeground = true
        val current = _ui.value
        val pet = current.pet ?: return
        val result = Simulation.advance(pet, System.currentTimeMillis(), current.config)
        val report = buildOfflineReport(pet, result.state, result.events)
        _ui.update { it.copy(pet = result.state, offlineReport = report ?: it.offlineReport) }
        handleEvents(result.events, offline = true)
        persist(result.state, immediate = true)
    }

    fun dismissOfflineReport() = _ui.update { it.copy(offlineReport = null) }

    /**
     * Only worth showing after a real absence. Coming back to a wall of text after a two-minute
     * phone call would be noise, so short gaps report nothing at all.
     */
    private fun buildOfflineReport(
        before: PetState,
        after: PetState,
        events: List<GameEvent>,
    ): OfflineReport? {
        val minutes = (after.lastTickMillis - before.lastTickMillis) / 60_000L
        if (minutes < 20 || before.lastTickMillis == 0L) return null

        val lines = mutableListOf<String>()
        events.filterIsInstance<GameEvent.Evolved>().lastOrNull()?.let {
            lines += "Evolved into a ${it.to.displayName} (${it.branch.displayName})."
        }
        if (events.any { it is GameEvent.Hatched }) lines += "The egg hatched while you were out."
        if (after.isDead) {
            lines += "${after.name} passed away: ${after.deathReason?.displayName ?: "unknown"}."
        } else {
            val messes = after.poops - before.poops
            if (messes > 0) lines += "Made $messes mess${if (messes > 1) "es" else ""} on the floor."
            if (after.isSick && !before.isSick) lines += "Caught something and needs medicine."
            val hunger = before.stats.satiety - after.stats.satiety
            if (hunger > 15f) lines += "Got hungry — satiety fell ${hunger.toInt()} points."
            if (after.isSleeping && !before.isSleeping) lines += "Fell asleep on its own."
            val mistakes = after.careMistakes - before.careMistakes
            if (mistakes > 0) lines += "Logged $mistakes care mistake${if (mistakes > 1) "s" else ""}."
        }
        events.filterIsInstance<GameEvent.LeveledUp>().lastOrNull()?.let {
            lines += "You reached keeper level ${it.level}."
        }
        if (lines.isEmpty()) lines += "Nothing eventful. ${after.name} held up fine."
        return OfflineReport(minutesAway = minutes, lines = lines, petName = after.name)
    }

    // ---------------------------------------------------------------- actions

    fun feed(itemId: String) {
        _ui.update { it.copy(servedItemId = itemId) }
        runAction(Sfx.EAT) { CareActions.feed(it, itemId) }
    }
    fun useMedicine(itemId: String = "medicine") = runAction(Sfx.HEAL) { CareActions.useMedicine(it, itemId) }
    fun cleanRoom() = runAction(Sfx.CLEAN) { CareActions.cleanRoom(it) }
    fun scoopPoop() = runAction(Sfx.CLEAN) { CareActions.scoopPoop(it) }
    fun tickle() = runAction(Sfx.HAPPY) { CareActions.tickle(it) }
    fun toss() = runAction(Sfx.HAPPY) { CareActions.toss(it) }
    fun bathe() = runAction(Sfx.CLEAN) { CareActions.bathe(it) }
    fun toggleLights() = runAction(Sfx.SELECT) { CareActions.toggleLights(it) }
    fun putToSleep() = runAction(Sfx.SLEEP) { CareActions.putToSleep(it) }
    fun wake() = runAction(Sfx.SELECT) { CareActions.wake(it) }
    fun petPet() = runAction(Sfx.HAPPY) { CareActions.pet(it) }
    fun praise() = runAction(Sfx.HAPPY) { CareActions.praise(it) }
    fun scold() = runAction(Sfx.DENY) { CareActions.scold(it) }
    fun buy(itemId: String) = runAction(Sfx.COIN) { CareActions.buy(it, itemId) }
    fun equipHat(hatId: String?) = runAction(Sfx.CONFIRM) { CareActions.equipHat(it, hatId) }
    fun setRoom(roomId: String) = runAction(Sfx.CONFIRM) { CareActions.setRoom(it, roomId) }
    fun rename(name: String) = runAction(Sfx.CONFIRM) { CareActions.rename(it, name) }
    fun snapshot(title: String) = runAction(Sfx.CONFIRM) { CareActions.snapshot(it, title, System.currentTimeMillis()) }

    /** Uses an item for what it is for; the routing lives in [CareActions.use], not here. */
    fun useItem(itemId: String) = runAction(Sfx.CONFIRM) { CareActions.use(it, itemId) }

    fun finishGame(won: Boolean, score: Float, gameName: String, gameId: String, points: Int) =
        runAction(if (won) Sfx.LEVEL_UP else Sfx.GAME_MISS) {
            CareActions.finishGame(it, won, score, gameName, gameId, points)
        }

    /**
     * Today's missions with progress attached. Derived rather than stored, because progress is
     * itself a derivation — see [com.neopal.pet.domain.DayLedger].
     */
    fun missions(): List<MissionProgress> =
        _ui.value.pet?.let { Missions.today(it) } ?: emptyList()

    /**
     * Collects every finished mission. This is not a [CareActions] action: it moves no stat and
     * the pet is not the one doing it, so it does not belong in the same list as feeding.
     */
    fun claimMissions() {
        val pet = _ui.value.pet ?: return
        val events = mutableListOf<GameEvent>()
        val (updated, reward) = Missions.claim(pet, events)
        if (reward.missions.isEmpty()) {
            play(Sfx.DENY)
            return
        }
        play(Sfx.COIN)
        _ui.update {
            it.copy(
                pet = updated,
                animation = PetAnimation.HAPPY,
                animationId = it.animationId + 1,
                deltas = statDeltas(pet, updated),
            )
        }
        val label = if (reward.missions.size == 1) reward.missions.first().title else "${reward.missions.size} missions"
        showToast("$label complete — +${reward.coins} coins")
        handleEvents(events, offline = false)
        persist(updated)
    }

    // ---------------------------------------------------------------- the autonomous half

    /**
     * Hands the creature more or less of its own day.
     *
     * Not a [CareActions] action: it changes nothing about the pet, only about who is allowed to
     * decide for it. Persisted immediately, because a player who sets this and closes the app has
     * made exactly the kind of choice that must survive being closed.
     */
    fun setAutonomy(autonomy: Autonomy) {
        val pet = _ui.value.pet ?: return
        if (pet.autonomy == autonomy) return
        val updated = pet.copy(
            autonomy = autonomy,
            // Dropping to a level that no longer permits what it is doing would otherwise leave
            // the pet stuck mid-activity with nothing able to end it.
            activity = if (autonomy == Autonomy.OFF) null else pet.activity,
        )
        play(Sfx.CONFIRM)
        _ui.update { it.copy(pet = updated) }
        showToast(autonomy.description)
        persist(updated, immediate = true)
    }

    /** What the brain is weighing right now, winners and blocked options alike. */
    fun considerations(): List<Consideration> =
        _ui.value.pet?.let { Brain.considerations(it, _ui.value.config) } ?: emptyList()

    /** The skill the pet is working towards, and how far along it is. */
    fun studyFraction(): Float = _ui.value.pet?.let { Learning.studyFraction(it) } ?: 0f

    /** Why [skill] is out of reach, or null when it is not. */
    fun skillBlocker(skill: Skill): String? =
        _ui.value.pet?.let { Learning.whyBlocked(it, skill) }

    /**
     * Sitting down with the pet for one lesson. This one *is* the player doing something to the
     * creature — it spends the pet's energy and pays bond — so it runs through the same path as
     * feeding and shows the same stat deltas.
     */
    fun teach() {
        val pet = _ui.value.pet ?: return
        Learning.teachBlockedReason(pet)?.let {
            play(Sfx.DENY)
            showToast(it)
            return
        }
        val events = mutableListOf<GameEvent>()
        val updated = Learning.teach(pet, events)
        play(Sfx.CONFIRM)
        _ui.update {
            it.copy(
                pet = updated,
                animation = PetAnimation.HAPPY,
                animationId = it.animationId + 1,
                deltas = statDeltas(pet, updated),
            )
        }
        handleEvents(events, offline = false)
        persist(updated)
    }

    /** Why the pet and [palId] cannot pair off, phrased for the player, or null when they can. */
    fun pairingBlocker(palId: String): String? =
        _ui.value.pet?.let { Colony.pairingBlocker(it, palId) }

    /** Puts the pet and [palId] together. Refused, with a reason, when the pairing is not allowed. */
    fun pair(palId: String) {
        val pet = _ui.value.pet ?: return
        Colony.pairingBlocker(pet, palId)?.let {
            play(Sfx.DENY)
            showToast(it)
            return
        }
        val events = mutableListOf<GameEvent>()
        // Seeded from the save rather than the clock, so the child a player is shown in the
        // preview is the child they get.
        val updated = Colony.pair(pet, palId, _ui.value.config, kotlin.random.Random(pet.rngSeed), events)
        _ui.update { it.copy(pet = updated) }
        handleEvents(events, offline = false)
        persist(updated, immediate = true)
    }

    /**
     * Everyone the pet knows, family first and then the closest.
     *
     * Sorted here rather than in the domain because this is a presentation order, not a fact
     * about the world: the colony keeps its own list stable so a visitor never jumps around the
     * screen, and reordering it there would undo that for everybody.
     */
    fun pals(): List<Pal> {
        fun rank(pal: Pal): Int = when (pal.relation) {
            Relation.OFFSPRING -> 0
            Relation.PARENT -> 1
            Relation.MATE -> 2
            Relation.FRIEND -> 3
            Relation.VISITOR -> 4
        }
        return _ui.value.pet?.pals.orEmpty()
            .sortedWith(compareBy<Pal> { rank(it) }.thenByDescending { it.affinity })
    }

    // ---------------------------------------------------------------- talking to it

    /** True while the creature is composing a reply. */
    fun isThinking(): Boolean = _ui.value.thinking

    /**
     * Says something to the creature.
     *
     * The player's line is stored immediately and the reply arrives later or not at all. That
     * ordering matters: a message that only appears once the network answers looks like the app
     * dropped it, and on a free model tier "does not answer" is a normal outcome rather than an
     * exceptional one.
     */
    fun say(message: String) {
        val text = message.trim().take(MAX_MESSAGE_CHARS)
        if (text.isEmpty() || _ui.value.thinking) return
        val pet = _ui.value.pet ?: return
        val config = _ui.value.config

        val asked = pet.copy(
            chat = (pet.chat + ChatTurn(fromPet = false, text = text, atSeconds = pet.ageSeconds))
                .takeLast(Simulation.MAX_CHAT_TURNS),
        )
        _ui.update { it.copy(pet = asked) }
        persist(asked)

        if (!config.mind.usable || !config.mind.conversation || !mind.isReady) {
            play(Sfx.DENY)
            showToast("${pet.name} has nowhere to think yet. Connect a brain in Settings.")
            return
        }

        _ui.update { it.copy(thinking = true) }
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            val brief = PetBrief.of(asked, config)
            val reply = mind.speak(brief, asked.chat, text)
            _ui.update { it.copy(thinking = false) }
            // Read the pet again rather than closing over `asked`: the simulation ticks once a
            // second and the state that went into the request is stale by the time it returns.
            val now = _ui.value.pet ?: return@launch
            if (reply == null) {
                play(Sfx.DENY)
                showToast("${now.name} did not answer.")
                return@launch
            }
            val answered = now.copy(
                chat = (now.chat + ChatTurn(fromPet = true, text = reply.text, atSeconds = now.ageSeconds))
                    .takeLast(Simulation.MAX_CHAT_TURNS),
                // A model may nudge the mood a little, and only a little: warmth is clamped at the
                // source and applied to happiness and bond alone. Letting a reply move satiety or
                // health would put the simulation's rules behind a text box.
                stats = now.stats.copy(
                    happiness = now.stats.happiness + reply.warmth.coerceIn(-1f, 1f) * 3f,
                    bond = now.stats.bond + reply.warmth.coerceIn(0f, 1f) * 1.5f,
                ).coerced(),
            )
            play(Sfx.SELECT)
            _ui.update { it.copy(pet = answered) }
            persist(answered)
        }
    }

    /**
     * Lets the creature say something first, when something worth mentioning has just happened.
     *
     * Everything else in this app waits to be poked. This is the one place the creature starts
     * the exchange, and it is the difference between a thing that answers and a thing that lives
     * with you — but it is also the fastest way to make an app annoying, so the bar is high and
     * the throttle is long.
     *
     * Deliberately reuses [MindProvider.speak] rather than adding a method. The event goes in as
     * the prompt but is *not* stored as a player turn: the player did not say it, and a chat log
     * that claims otherwise is a log that lies about who spoke.
     */
    private fun maybeSpeakFirst(events: List<GameEvent>) {
        val worth = events.firstNotNullOfOrNull { unpromptedLine(it) } ?: return
        val current = _ui.value
        val pet = current.pet ?: return
        val config = current.config
        if (!config.mind.usable || !config.mind.conversation || !mind.isReady) return
        if (_ui.value.thinking || pet.isSleeping || pet.isDead || !pet.isMindAwake) return
        if (!Cadence.due(pet.ageSeconds, lastSpokeFirstAtSeconds, Cadence.SPEAK_FIRST_SECONDS)) return

        lastSpokeFirstAtSeconds = pet.ageSeconds
        viewModelScope.launch {
            val reply = mind.speak(PetBrief.of(pet, config), pet.chat, worth) ?: return@launch
            val now = _ui.value.pet ?: return@launch
            val spoken = now.copy(
                chat = (now.chat + ChatTurn(fromPet = true, text = reply.text, atSeconds = now.ageSeconds))
                    .takeLast(Simulation.MAX_CHAT_TURNS),
            )
            _ui.update { it.copy(pet = spoken) }
            showToast("${now.name} said something.")
            persist(spoken)
        }
    }

    /**
     * What the creature would bring up on its own, or null for the vast majority of events.
     *
     * Only firsts and turning points. A creature that remarked on every meal would be a
     * notification, and people turn notifications off.
     */
    private fun unpromptedLine(event: GameEvent): String? = when (event) {
        is GameEvent.Evolved -> "You have just grown into a ${event.to.displayName}. Say something about it."
        is GameEvent.LearnedSkill -> "You have just worked out how to ${event.skill.displayName.lowercase()}. Mention it."
        is GameEvent.Befriended -> "${event.pal.name} has just become your friend. Say something about that."
        is GameEvent.Paired -> "You and ${event.pal.name} have just paired off. Say something."
        is GameEvent.ChildHatched -> "${event.child.name} has just hatched. Say something to your keeper about it."
        is GameEvent.LearnedFromExperience -> "You have just worked something out for yourself. Mention what."
        is GameEvent.Recovered -> "You have just got over being ill. Say something."
        else -> null
    }

    private var lastSpokeFirstAtSeconds = Cadence.NEVER

    /** Forgets the conversation. The creature's diary is untouched; this is only the talking. */
    fun clearChat() {
        val pet = _ui.value.pet ?: return
        if (pet.chat.isEmpty()) return
        chatJob?.cancel()
        _ui.update { it.copy(thinking = false) }
        val cleared = pet.copy(chat = emptyList())
        play(Sfx.BACK)
        _ui.update { it.copy(pet = cleared) }
        persist(cleared, immediate = true)
    }

    /** Longest thing the player can say in one go. Free models charge for every token of it. */
    private val MAX_MESSAGE_CHARS = 400

    /** Points the creature's brain somewhere, or nowhere. Persisted immediately. */
    fun updateMind(transform: (MindConfig) -> MindConfig) {
        updateConfig { it.copy(mind = transform(it.mind)) }
    }

    /** Runs one pure action against the current state and folds the result into the UI. */
    private fun runAction(sfx: Sfx, block: (PetState) -> ActionResult) {
        val pet = _ui.value.pet ?: return
        val raw = block(pet)
        // Milestones the player caused belong in the diary too, not only simulation events.
        val result = if (raw.accepted) {
            raw.copy(state = Chronicle.forPlayerMilestone(raw.state, _ui.value.config))
        } else raw
        if (!result.accepted) play(Sfx.DENY) else play(sfx)
        _ui.update {
            it.copy(
                pet = result.state,
                animation = result.animation,
                animationId = it.animationId + 1,
                deltas = if (result.accepted) statDeltas(pet, result.state) else emptyList(),
            )
        }
        result.toast?.let { showToast(it) }
        handleEvents(result.events, offline = false)
        persist(result.state)
    }

    // ---------------------------------------------------------------- settings

    /** One-shot: remembers that the first-run coach marks have been shown. */
    fun markTutorialSeen() = updateConfig { it.copy(tutorialSeen = true) }

    fun updateConfig(transform: (GameConfig) -> GameConfig) {
        val updated = transform(_ui.value.config)
        ChiptuneEngine.enabled = updated.soundEnabled
        ChiptuneEngine.volume = updated.sfxVolume
        _ui.update { it.copy(config = updated) }
        viewModelScope.launch { repository.saveConfig(updated) }
    }

    suspend fun exportSave(): String = repository.exportSave()

    fun importSave(raw: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = repository.importSave(raw)
            if (ok) {
                val imported = repository.currentState()
                _ui.update { it.copy(pet = imported) }
            }
            onResult(ok)
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun handleEvents(events: List<GameEvent>, offline: Boolean) {
        events.forEach { event ->
            when (event) {
                is GameEvent.Hatched -> {
                    triggerAnimation(PetAnimation.HATCH)
                    play(Sfx.HATCH)
                    showToast("The egg hatched!")
                }
                is GameEvent.Evolved -> {
                    triggerAnimation(PetAnimation.EVOLVE)
                    play(Sfx.EVOLVE)
                    _ui.update { it.copy(evolutionSplash = true) }
                    showToast("Evolved into ${event.to.displayName} (${event.branch.displayName})!")
                }
                is GameEvent.LeveledUp -> {
                    if (!offline) triggerAnimation(PetAnimation.LEVEL_UP)
                    play(Sfx.LEVEL_UP)
                    showToast("Keeper level ${event.level}!")
                }
                is GameEvent.Died -> {
                    triggerAnimation(PetAnimation.DEAD)
                    play(Sfx.DEATH)
                    showToast("${_ui.value.pet?.name ?: "Your pet"} passed away: ${event.reason.displayName}.")
                }
                is GameEvent.GotSick -> showToast("Your pet caught something.")
                is GameEvent.Recovered -> showToast("Fully recovered!")
                is GameEvent.Pooped -> if (!offline) play(Sfx.BACK)
                is GameEvent.Unlocked -> {
                    _ui.update { it.copy(achievementBanner = event.achievement) }
                    play(Sfx.COIN)
                }
                is GameEvent.Message -> showToast(event.text)

                // The autonomous half is loud by nature — it acts every few minutes, all day. Only
                // the firsts get a toast; the running commentary belongs in the decision log, where
                // the player goes looking for it rather than having it thrown at them.
                is GameEvent.LearnedSkill -> {
                    play(Sfx.LEVEL_UP)
                    showToast("Learned to ${event.skill.displayName.lowercase()} without being asked.")
                }
                is GameEvent.MetPal -> {
                    if (!offline) play(Sfx.SELECT)
                    showToast("${event.pal.name} came by.")
                }
                is GameEvent.Befriended -> {
                    play(Sfx.HAPPY)
                    showToast("${event.pal.name} is a friend now.")
                }
                is GameEvent.Paired -> {
                    play(Sfx.LEVEL_UP)
                    showToast("${_ui.value.pet?.name ?: "Your pet"} and ${event.pal.name} paired off.")
                }
                is GameEvent.EggLaid -> {
                    play(Sfx.CONFIRM)
                    showToast("There's an egg in the nest.")
                }
                is GameEvent.ChildHatched -> {
                    if (!offline) triggerAnimation(PetAnimation.HATCH)
                    play(Sfx.HATCH)
                    showToast("${event.child.name} hatched.")
                }

                is GameEvent.LearnedFromExperience -> {
                    play(Sfx.LEVEL_UP)
                    showToast("Worked something out on its own.")
                }

                is GameEvent.Decided,
                is GameEvent.Finished,
                is GameEvent.IntellectGrew,
                is GameEvent.PalLeft,
                is GameEvent.PlanAbandoned,
                is GameEvent.PlanMade,
                is GameEvent.PlanExtended,
                -> Unit

                is GameEvent.CareMistake, is GameEvent.FellAsleep, is GameEvent.WokeUp -> Unit
            }
        }
    }

    fun dismissAchievementBanner() = _ui.update { it.copy(achievementBanner = null) }
    fun dismissEvolutionSplash() = _ui.update { it.copy(evolutionSplash = false) }

    private fun triggerAnimation(animation: PetAnimation) {
        _ui.update { it.copy(animation = animation, animationId = it.animationId + 1, deltas = emptyList()) }
    }

    private fun showToast(message: String) {
        toastJob?.cancel()
        _ui.update { it.copy(toast = message) }
        toastJob = viewModelScope.launch {
            delay(2_600)
            _ui.update { it.copy(toast = null) }
        }
    }

    private fun play(sfx: Sfx) {
        if (_ui.value.config.soundEnabled) ChiptuneEngine.play(sfx)
    }

    /** Debounced write so a burst of taps does not hammer the disk. */
    private fun persist(state: PetState, immediate: Boolean = false) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (!immediate) delay(800)
            repository.save(state)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                PetViewModel(app)
            }
        }
    }
}
