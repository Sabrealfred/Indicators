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
import com.neopal.pet.domain.Achievement
import com.neopal.pet.domain.ActionResult
import com.neopal.pet.domain.CareActions
import com.neopal.pet.domain.Chronicle
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.GameEvent
import com.neopal.pet.domain.PetAnimation
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.Simulation
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
        val achievementBanner: Achievement? = null,
        /** Filled after a long absence so the player learns what they missed. */
        val offlineReport: OfflineReport? = null,
        val evolutionSplash: Boolean = false,
    )

    /** What happened while the app was closed, in plain sentences. */
    data class OfflineReport(val minutesAway: Long, val lines: List<String>, val petName: String)

    private val repository = PetRepository(application)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var saveJob: Job? = null
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
                val current = _ui.value
                val pet = current.pet ?: continue
                val result = Simulation.advance(pet, System.currentTimeMillis(), current.config)
                if (result.state != pet) {
                    _ui.update { it.copy(pet = result.state) }
                    persist(result.state)
                }
                handleEvents(result.events, offline = false)
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun startNewGame(name: String, species: Species) {
        val state = Simulation.newGame(name, species, System.currentTimeMillis())
        _ui.update { it.copy(pet = state, loading = false) }
        play(Sfx.CONFIRM)
        persist(state, immediate = true)
    }

    fun startNextGeneration(name: String, species: Species) {
        val previous = _ui.value.pet ?: return startNewGame(name, species)
        val state = Simulation.nextGeneration(previous, name, species, System.currentTimeMillis())
        _ui.update { it.copy(pet = state) }
        play(Sfx.CONFIRM)
        persist(state, immediate = true)
    }

    fun resetEverything() {
        viewModelScope.launch {
            repository.clear()
            _ui.update { it.copy(pet = null) }
        }
    }

    /** Called when the app returns to the foreground so offline progress lands immediately. */
    fun onResumed() {
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

    fun feed(itemId: String) = runAction(Sfx.EAT) { CareActions.feed(it, itemId) }
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

    fun finishGame(won: Boolean, score: Float, gameName: String, gameId: String, points: Int) =
        runAction(if (won) Sfx.LEVEL_UP else Sfx.GAME_MISS) {
            CareActions.finishGame(it, won, score, gameName, gameId, points)
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
