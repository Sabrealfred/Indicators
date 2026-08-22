package com.neopal.pet.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.neopal.pet.data.CheckTrigger
import com.neopal.pet.data.InstallLaunch
import com.neopal.pet.data.UpdateService
import com.neopal.pet.data.UpdateStatus
import com.neopal.pet.domain.UpdateAction
import com.neopal.pet.domain.UpdatePhase
import com.neopal.pet.domain.UpdatePlan
import com.neopal.pet.domain.UpdatePlanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The updater's own view model. One [UpdateService], one screen, nothing about the pet.
 *
 * ## Why this is not part of [PetViewModel]
 *
 * `PetViewModel` is nine hundred lines and owns the running game: the foreground clock, the save
 * file, the chat job, the widget bridge. Three arguments say the updater does not belong in it,
 * and one says it might — the one that loses.
 *
 * 1. **Nothing is shared.** The updater reads no pet, writes no save, and changes no config. Put
 *    inside `PetViewModel` it would be a second, unrelated state machine living in the same
 *    object as the game clock, and the next person reading `PetViewModel` to understand the pet
 *    would have to read past it.
 * 2. **Its failure mode is different.** Every path in `PetViewModel` ends in the game continuing;
 *    every path here ends in a sentence and a stopped process. Merging them would put a
 *    `UpdateStatus` field in `UiState`, which is emitted on every simulation tick — a download's
 *    progress and a creature's hunger would recompose each other for no reason.
 * 3. **It can be created late and destroyed early.** `PetViewModel` lives for the whole app;
 *    nothing about the updater has to. Holding an `UpdateService` — which holds a
 *    `MutableStateFlow`, a download job and a handle to a cache directory — for the entire life
 *    of the game to serve one screen that is opened perhaps twice would be the wrong shape.
 *
 * The argument for folding it in is that `PetViewModel` already owns the other Android-facing
 * clients ([com.neopal.pet.data.PetRepository], [com.neopal.pet.data.RemoteMindClient]), so this
 * is a second place to look. But both of those exist to serve the pet, which is exactly what this
 * does not do. A second view model is cheaper than a ninth responsibility.
 *
 * ## Why it is nevertheless hoisted above the NavHost
 *
 * It is created at the app root rather than inside the update route, so it is scoped to the
 * activity. Scoped to the route instead, backing out of the screen would clear it, and clearing
 * it cancels an in-flight download — a player who taps back for ten seconds to look at their pet
 * would come back to zero bytes. It also makes the service's own auto-check throttle real: a
 * throttle on an object that is rebuilt every time the screen opens throttles nothing.
 *
 * Construction is cheap enough to pay for on every launch: the service takes a context and
 * allocates one flow, and resolves its cache directory lazily on first use.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val service = UpdateService(application)

    /**
     * Whether this device has a per-app "install unknown apps" screen at all.
     *
     * A property of the Android version and so constant for the life of the process, unlike
     * [canInstall], which is a permission the player can change while the app is running.
     */
    private val hasPermissionScreen: Boolean = service.unknownSourcesSettingsIntent() != null

    private var canInstall: Boolean = service.canInstallPackages()

    /** The download, so it can be stopped. Held rather than left anonymous for exactly that. */
    private var downloadJob: Job? = null

    /** A check in flight, so a second tap does not stack another one behind it. */
    private var checkJob: Job? = null

    /**
     * Everything the screen draws.
     *
     * [status] is the service's own account of where things are and carries the sentence to show;
     * [plan] is the pure layer's account of what to offer about it. Both are kept, because the
     * plan deliberately does not repeat the status' message and the screen shows them together.
     */
    data class State(
        val status: UpdateStatus = UpdateStatus.Idle,
        val plan: UpdatePlan = UpdatePlanner.plan(UpdatePhase.IDLE),
        /**
         * What happened the last time the installer was asked for, or null.
         *
         * Separate from [status] because handing an APK to the system installer does not change
         * what the updater knows: the service says plainly that the outcome cannot be observed,
         * so this is a note about the attempt, never a claim about the install.
         */
        val installNote: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            service.status.collect { status ->
                // installNote is deliberately preserved across a status change: `startInstall`
                // can settle the status *and* return a message about the same event, and the
                // message is the half that says what to do about it.
                _state.value = _state.value.copy(status = status, plan = planFor(status))
            }
        }
    }

    // ------------------------------------------------------------------ what the screen calls

    /**
     * Runs one check when the screen opens.
     *
     * [CheckTrigger.AUTOMATIC], so it is refused on a metered connection and throttled to the
     * service's own interval. Nobody opening this screen wants to press a button before anything
     * happens, and nobody wants their mobile data spent by opening a screen either.
     */
    fun checkOnOpen() = check(CheckTrigger.AUTOMATIC)

    /**
     * Carries out a button.
     *
     * The screen does not decide what any button does — the pure layer decided which buttons
     * exist, and this decides what each one calls. [openSettings] is the caller's activity-result
     * launcher, which only a composable can own; it is passed in rather than reached for so that
     * the whole mapping stays in one `when` instead of leaking one case back into the UI.
     */
    fun perform(action: UpdateAction, openSettings: (Intent) -> Unit) {
        when (action) {
            UpdateAction.CHECK -> check(CheckTrigger.MANUAL)
            UpdateAction.DOWNLOAD -> download(allowMetered = false)
            UpdateAction.DOWNLOAD_ON_METERED -> download(allowMetered = true)
            UpdateAction.CANCEL_DOWNLOAD -> cancelDownload()
            UpdateAction.DISCARD -> discard()
            UpdateAction.INSTALL -> install()
            UpdateAction.ALLOW_INSTALLS -> openPermissionScreen(openSettings)
        }
    }

    /**
     * Re-reads the install permission and redraws the plan around it.
     *
     * Called when the player comes back from system settings, and again whenever the screen is
     * resumed: the switch lives outside this app and can be thrown at any time, including from
     * the notification shade while this screen is still on top.
     */
    fun refreshInstallPermission() {
        val now = service.canInstallPackages()
        if (now == canInstall) return
        canInstall = now
        _state.value = _state.value.copy(plan = planFor(_state.value.status))
    }

    // ------------------------------------------------------------------ the service, one call each

    private fun check(trigger: CheckTrigger) {
        if (checkJob?.isActive == true) return
        _state.value = _state.value.copy(installNote = null)
        checkJob = viewModelScope.launch { service.checkForUpdate(trigger) }
    }

    private fun download(allowMetered: Boolean) {
        if (downloadJob?.isActive == true) return
        _state.value = _state.value.copy(installNote = null)
        downloadJob = viewModelScope.launch { service.download(allowMetered = allowMetered) }
    }

    /**
     * Stops a download in flight.
     *
     * Cancelling the job is the whole mechanism: the service treats cancellation as a first-class
     * outcome — it deletes the part file and puts the offer back — so there is nothing to undo
     * here afterwards.
     */
    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    private fun discard() {
        cancelDownload()
        _state.value = _state.value.copy(installNote = null)
        service.discardDownload()
    }

    private fun install() {
        // Named `outcome` rather than `launch`, which is a coroutine builder in scope here.
        when (val outcome = service.startInstall()) {
            InstallLaunch.Started -> note(
                "The system installer is open. If you confirm, NeoPal closes and comes back as " +
                    "the new build; if you back out, the downloaded file is still here.",
            )
            // The permission was revoked between the screen being drawn and the button being
            // pressed. Re-reading it swings the plan over to the permission gate, which is the
            // screen that explains what to do.
            InstallLaunch.PermissionNeeded -> refreshInstallPermission()
            is InstallLaunch.Refused -> note(outcome.message)
        }
    }

    private fun openPermissionScreen(openSettings: (Intent) -> Unit) {
        val intent = service.unknownSourcesSettingsIntent()
        if (intent == null) {
            note(
                "This version of Android has no per-app screen for this. The switch is under " +
                    "Settings, Security, \"unknown sources\".",
            )
            return
        }
        note(null)
        openSettings(intent)
    }

    // ------------------------------------------------------------------ internals

    private fun note(text: String?) {
        _state.value = _state.value.copy(installNote = text)
    }

    private fun planFor(status: UpdateStatus): UpdatePlan = UpdatePlanner.plan(
        phase = phaseOf(status),
        canInstallPackages = canInstall,
        hasPermissionScreen = hasPermissionScreen,
        // The service flattens the reason to its sentence on the way out; the pure layer knows
        // how to put the enum back, and needs it to tell a broken publish from an old one.
        unknown = (status as? UpdateStatus.Undecidable)?.let { UpdatePlanner.reasonFor(it.reason) },
        cautioned = (status as? UpdateStatus.ReadyToInstall)?.caution != null,
    )

    /**
     * Translation, not decision.
     *
     * Exhaustive on purpose: a status added to the service later stops this compiling rather than
     * falling into an `else` that would quietly show the wrong screen for it.
     */
    private fun phaseOf(status: UpdateStatus): UpdatePhase = when (status) {
        UpdateStatus.Idle -> UpdatePhase.IDLE
        UpdateStatus.Checking -> UpdatePhase.CHECKING
        is UpdateStatus.UpToDate -> UpdatePhase.UP_TO_DATE
        is UpdateStatus.AheadOfPublished -> UpdatePhase.AHEAD_OF_PUBLISHED
        is UpdateStatus.Undecidable -> UpdatePhase.UNDECIDABLE
        is UpdateStatus.CheckFailed -> UpdatePhase.CHECK_FAILED
        is UpdateStatus.Available -> UpdatePhase.OFFER
        is UpdateStatus.NeedsMeteredConsent -> UpdatePhase.METERED_CONSENT
        is UpdateStatus.Downloading -> UpdatePhase.DOWNLOADING
        is UpdateStatus.DownloadFailed -> UpdatePhase.DOWNLOAD_FAILED
        is UpdateStatus.ReadyToInstall -> UpdatePhase.READY_TO_INSTALL
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                UpdateViewModel(app)
            }
        }
    }
}
