package com.neopal.pet.domain

/**
 * What the update screen says and offers, given where the updater got to.
 *
 * ## Why this is not in the composable
 *
 * `AppVersion` decides what "newer" means. This file decides the other half — *what the player is
 * shown about it* — and it is the half that is easiest to get quietly wrong. Every one of these
 * states is reached rarely, on somebody else's phone, on a network this machine has never seen;
 * a screen that offers "Install" when Android has not been given permission to install, or that
 * greets a broken publish with "you are up to date", fails in a way nobody will ever report as a
 * bug. So the branching lives here, as pure functions of a phase and two booleans, and the
 * composable is left with nothing to decide.
 *
 * The rule this follows is the project's: an `if` about what the player should see is domain
 * logic. The composable maps `UpdateStatus` onto [UpdatePhase] with one exhaustive `when` — a
 * translation the compiler checks — and then draws whatever [UpdatePlanner.plan] hands back.
 *
 * ## Why the phase is a separate enum from `UpdateStatus`
 *
 * `UpdateStatus` lives next to the service, in a file that imports `android.content.Intent` and
 * `androidx.core.content.FileProvider`. Depending on it from here would drag the whole Android
 * half into the pure source set and this file would stop being testable on a plain JVM, which is
 * the only reason it is worth having. [UpdatePhase] is that dependency cut down to the
 * distinctions the screen actually draws differently — which is fewer than the states the service
 * distinguishes, and deliberately so: `Idle` and a failed check both offer one button that says
 * "check", and pretending otherwise would be extra screen for no extra truth.
 */
object UpdatePlanner {

    /**
     * The full plan for a screen.
     *
     * [status] is the service's own sentence for the state and is always shown verbatim: every
     * failure this app can produce carries a message written where the failure happened, and this
     * layer's job is to frame it, never to replace it with something vaguer.
     */
    fun plan(
        phase: UpdatePhase,
        /** Whether Android will let this app ask the package installer to run. */
        canInstallPackages: Boolean = true,
        /** Whether this device has a per-app "install unknown apps" screen to send the player to. */
        hasPermissionScreen: Boolean = true,
        /** Which honest answer was impossible, when [phase] is [UpdatePhase.UNDECIDABLE]. */
        unknown: UpdateUnknown? = null,
        /** Whether the downloaded build carries something the player must hear before installing. */
        cautioned: Boolean = false,
    ): UpdatePlan = when (phase) {
        UpdatePhase.IDLE -> UpdatePlan(
            headline = "Check for a new build",
            tone = UpdateTone.NEUTRAL,
            primary = UpdateAction.CHECK,
        )

        UpdatePhase.CHECKING -> UpdatePlan(
            headline = "Checking…",
            tone = UpdateTone.NEUTRAL,
            primary = null,
            busy = true,
        )

        UpdatePhase.UP_TO_DATE -> UpdatePlan(
            headline = "Up to date",
            tone = UpdateTone.GOOD,
            primary = UpdateAction.CHECK,
        )

        // Not a failure and not a warning: a local build is the normal state of the machine this
        // app is written on. What it must not do is look like an error, and it must not offer an
        // install -- Android would refuse the downgrade and the refusal would be the player's
        // first hint that anything was odd.
        UpdatePhase.AHEAD_OF_PUBLISHED -> UpdatePlan(
            headline = "Yours is the newer one",
            tone = UpdateTone.NEUTRAL,
            primary = UpdateAction.CHECK,
            note = "This copy did not come from the release page, or the release page has gone " +
                "backwards. Either way there is nothing to install: Android will not replace a " +
                "build with an older one.",
        )

        // The state this screen exists to stop being silent about. "Cannot tell" is not "no
        // update" -- for most of these reasons it is a broken publish, and saying "you are up to
        // date" would hide a CI fault behind a reassuring sentence for as long as it lasts.
        UpdatePhase.UNDECIDABLE -> UpdatePlan(
            headline = when (faultFor(unknown)) {
                UpdateFault.PUBLISHER -> "The published build is broken"
                UpdateFault.THIS_APP -> "This copy cannot describe itself"
                UpdateFault.NOBODY -> "Cannot tell"
            },
            tone = when (faultFor(unknown)) {
                UpdateFault.NOBODY -> UpdateTone.WARN
                else -> UpdateTone.BAD
            },
            primary = UpdateAction.CHECK,
            note = when (faultFor(unknown)) {
                UpdateFault.PUBLISHER ->
                    "Nothing is wrong with the copy in your hand, and nothing will be installed " +
                        "over it. This is a fault in the build job that published the release, " +
                        "and it has to be fixed there — checking again will keep saying this " +
                        "until a build publishes a release that describes itself properly."
                UpdateFault.THIS_APP ->
                    "The updater compares build numbers, and it could not read its own. Install " +
                        "the APK from the release page by hand this once."
                UpdateFault.NOBODY ->
                    "The release predates the in-app updater, or the step that stamps the build " +
                        "number into the release notes did not run. There may or may not be a " +
                        "newer build behind it; there is no honest way to say from here."
            },
        )

        UpdatePhase.CHECK_FAILED -> UpdatePlan(
            headline = "The check did not get through",
            tone = UpdateTone.BAD,
            primary = UpdateAction.CHECK,
        )

        UpdatePhase.OFFER -> UpdatePlan(
            headline = "A newer build is published",
            tone = UpdateTone.GOOD,
            primary = UpdateAction.DOWNLOAD,
        )

        UpdatePhase.METERED_CONSENT -> UpdatePlan(
            headline = "This would use mobile data",
            tone = UpdateTone.WARN,
            primary = UpdateAction.DOWNLOAD_ON_METERED,
            secondary = listOf(UpdateAction.DISCARD),
        )

        UpdatePhase.DOWNLOADING -> UpdatePlan(
            headline = "Downloading",
            tone = UpdateTone.NEUTRAL,
            primary = null,
            secondary = listOf(UpdateAction.CANCEL_DOWNLOAD),
            busy = true,
            showsProgress = true,
        )

        UpdatePhase.DOWNLOAD_FAILED -> UpdatePlan(
            headline = "The download did not finish",
            tone = UpdateTone.BAD,
            primary = UpdateAction.DOWNLOAD,
            secondary = listOf(UpdateAction.DISCARD),
        )

        // Three different screens wear this one phase, and which one shows is the single most
        // consequential decision in this file.
        UpdatePhase.READY_TO_INSTALL -> when {
            // Android will not even let the installer be asked for. Offering "Install" here
            // produces a tap that does nothing visible, which reads as a broken app rather than
            // as a permission the player has never been asked for.
            !canInstallPackages && hasPermissionScreen -> UpdatePlan(
                headline = "Android needs your permission first",
                tone = UpdateTone.WARN,
                primary = UpdateAction.ALLOW_INSTALLS,
                secondary = listOf(UpdateAction.DISCARD),
                note = "NeoPal does not come from a store, so Android will not let it hand a " +
                    "package to the installer until you allow it. The button below opens the " +
                    "system screen with the switch on it. Come straight back — the update is " +
                    "downloaded and waiting, and nothing else on this device is affected.",
            )
            // No per-app screen exists to send them to (older Android has one device-wide
            // switch). Handing them a button that opens nothing would be worse than letting the
            // installer itself have the conversation, which on those versions it does.
            !canInstallPackages -> UpdatePlan(
                headline = "Downloaded — tap to install",
                tone = UpdateTone.WARN,
                primary = UpdateAction.INSTALL,
                secondary = listOf(UpdateAction.DISCARD),
                note = "This version of Android may refuse the install until \"unknown sources\" " +
                    "is switched on in system settings. It will say so itself if it does.",
            )
            else -> UpdatePlan(
                headline = "Downloaded — tap to install",
                // A caution is the different-signing-key case, which costs the player their save
                // if they go ahead without reading it. It gets the colour that makes people read.
                tone = if (cautioned) UpdateTone.WARN else UpdateTone.GOOD,
                primary = UpdateAction.INSTALL,
                secondary = listOf(UpdateAction.DISCARD),
            )
        }
    }

    /**
     * Recovers the reason behind an [UpdateUnknown] from the sentence it produced.
     *
     * The service flattens the enum to its message before the screen sees it, and the screen
     * needs the enum back to tell a broken publish from an old one. Matching on the message is
     * only sound while the messages are distinct, which is what [UpdatePlannerTest] pins.
     *
     * Null for a sentence no enum owns, which is treated as [UpdateFault.NOBODY] — the cautious
     * reading, since blaming a build job for a message this code does not recognise would be a
     * guess.
     */
    fun reasonFor(message: String): UpdateUnknown? =
        UpdateUnknown.entries.firstOrNull { it.message == message }

    /** Whose problem an undecidable answer is. Drives the headline, the colour and the advice. */
    fun faultFor(unknown: UpdateUnknown?): UpdateFault = when (unknown) {
        // Every one of these means the release and the APK attached to it disagree, or the
        // release describes itself in a way no reader can use. A build job produced that.
        UpdateUnknown.MARKER_MALFORMED,
        UpdateUnknown.ASSET_MISSING,
        UpdateUnknown.ASSET_SIZE_MISMATCH,
        UpdateUnknown.ASSET_DIGEST_MISMATCH,
        -> UpdateFault.PUBLISHER

        UpdateUnknown.INSTALLED_UNKNOWN -> UpdateFault.THIS_APP

        // A release with no marker at all is the one case that is nobody's fault by default: it
        // is what every release published before the updater existed looks like.
        UpdateUnknown.NO_MARKER, null -> UpdateFault.NOBODY
    }
}

/**
 * Where the updater is, reduced to the states the screen draws differently.
 *
 * Deliberately coarser than the service's own status: two states that produce the same headline,
 * the same colour and the same buttons are the same screen, and giving them separate phases would
 * invite them to drift apart for no reason a player could see.
 */
enum class UpdatePhase {
    IDLE,
    CHECKING,
    UP_TO_DATE,

    /** The installed build's number is higher than the published one's. */
    AHEAD_OF_PUBLISHED,

    /** No honest comparison was possible. See [UpdateUnknown] for which kind. */
    UNDECIDABLE,

    /** The release page could not be read at all. */
    CHECK_FAILED,

    /** A newer build exists and has not been fetched yet. */
    OFFER,

    /** Fetching it would spend mobile data and the player has not said yes. */
    METERED_CONSENT,
    DOWNLOADING,
    DOWNLOAD_FAILED,

    /** Fetched, checksummed and inspected. Nothing is installed until the player says so. */
    READY_TO_INSTALL,
}

/** A button the update screen can offer. Each one maps to exactly one call on the service. */
enum class UpdateAction(val label: String) {
    CHECK("Check for updates"),
    DOWNLOAD("Download"),
    DOWNLOAD_ON_METERED("Use mobile data"),
    CANCEL_DOWNLOAD("Stop"),
    INSTALL("Install"),
    ALLOW_INSTALLS("Open Android settings"),
    DISCARD("Discard"),
}

/**
 * How loudly a state should read.
 *
 * Not a colour — the screen picks those from its own palette. It is the *claim*: whether this is
 * fine, worth a second look, or broken. Keeping the claim here means the screen cannot decide on
 * its own that a broken publish looks reassuring.
 */
enum class UpdateTone { NEUTRAL, GOOD, WARN, BAD }

/** Whose problem an undecidable check is. */
enum class UpdateFault {
    /** The build job that published the release. Worth saying plainly; nobody else will fix it. */
    PUBLISHER,

    /** This installed copy. */
    THIS_APP,

    /** Neither: an old release, or something this code does not recognise. */
    NOBODY,
}

/** Everything the update screen needs beyond the service's own sentence for the state. */
data class UpdatePlan(
    val headline: String,
    val tone: UpdateTone,
    /** The one button the player most likely wants, or null when there is nothing to press. */
    val primary: UpdateAction?,
    val secondary: List<UpdateAction> = emptyList(),
    /** True while the service is working: buttons stay visible but stop responding. */
    val busy: Boolean = false,
    /** True when a progress meter is meaningful. */
    val showsProgress: Boolean = false,
    /** What the state's own message does not say, or null when it says enough. */
    val note: String? = null,
) {
    /** Every button on the screen, primary first. Saves the composable a null check. */
    val actions: List<UpdateAction> get() = listOfNotNull(primary) + secondary
}
