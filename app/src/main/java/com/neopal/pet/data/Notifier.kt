package com.neopal.pet.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.neopal.pet.MainActivity
import com.neopal.pet.R
import com.neopal.pet.domain.CareActions
import com.neopal.pet.domain.Chronicle
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.GameEvent
import com.neopal.pet.domain.Nudge
import com.neopal.pet.domain.NudgeAction
import com.neopal.pet.domain.NudgeChannel
import com.neopal.pet.domain.NudgeInput
import com.neopal.pet.domain.NudgeLedger
import com.neopal.pet.domain.NudgePermission
import com.neopal.pet.domain.NudgeSettings
import com.neopal.pet.domain.Nudges
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.Simulation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * The Android half of the notification layer: channels, permission, posting, and the one button.
 *
 * All of the judgement lives in [Nudges], which is pure and tested. What is left here is the part
 * that cannot be tested on this machine at all — there is no Android SDK in this checkout and
 * Google Maven is unreachable, so **this file has never been compiled and no notification it
 * describes has ever been seen on a screen.** Everything below is written to be as boring as
 * possible for that reason: no arithmetic, no conditions that are not either a platform version
 * check or a direct read of a decision made somewhere that is under test.
 *
 * The one call the rest of the app needs is [consider].
 */
object Notifier {

    /**
     * Two channels, because Android's per-channel controls are the honest version of the setting
     * the player actually wants. Somebody who is happy to be told their creature is dying and
     * bored of being told it has grown can have exactly that, from the system settings, without
     * the app having to offer it — and, crucially, without reaching for the one switch that turns
     * everything off for ever.
     */
    const val CHANNEL_URGENT = "neopal_urgent"
    const val CHANNEL_LIFE = "neopal_life"

    /** Superseded by the two above. Left here only so it can be removed from a device. */
    private const val CHANNEL_LEGACY = "care_reminders"

    /**
     * One notification, one id. A second entry in the shade from the same creature is how the
     * first one gets ignored, so a new nudge replaces its predecessor rather than joining it.
     */
    private const val ID_NUDGE = 1001

    private const val PREFS = "neopal_nudges"
    private const val KEY_LEDGER = "ledger"

    /**
     * Whether the system permission dialog has been put in front of this player once.
     *
     * It exists because Android answers the second refusal for you, permanently and silently.
     * An app that re-asks on every cold start therefore spends the player's two chances on
     * launches they were not thinking about notifications at all, and the switch is then dead
     * for the life of the install with nothing on screen to say so.
     */
    private const val KEY_ASKED = "permission_asked"

    /**
     * True while the player is looking at the creature. Deliberately in memory only: if the
     * process died, the player is not looking at anything.
     */
    @Volatile
    private var foreground: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------------------------------------------------------- setup

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val urgent = NotificationChannel(
            CHANNEL_URGENT,
            "Illness and danger",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "The rare occasions when coming back now changes what happens to your creature."
            enableVibration(true)
        }
        val life = NotificationChannel(
            CHANNEL_LIFE,
            "Milestones",
            // Low: shows in the shade, makes no sound. Hatching, growing up, a child, and the
            // end of a life all belong here. None of them is an emergency and all of them are
            // worth finding later.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Hatching, growing up, a child, and the end of a life."
            enableVibration(false)
        }
        manager.createNotificationChannel(urgent)
        manager.createNotificationChannel(life)
        // The old channel promised reminders about hunger and mess, which this layer no longer
        // sends. Leaving it would leave a switch in system settings that controls nothing.
        runCatching { manager.deleteNotificationChannel(CHANNEL_LEGACY) }
    }

    /**
     * Whether anything posted would actually arrive.
     *
     * Both halves matter: from API 33 the runtime permission can be denied, and on every version
     * the player can switch the app's notifications off in system settings. A caller that only
     * checked the first would happily believe it had told somebody something.
     */
    fun permission(context: Context): NudgePermission {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return NudgePermission.DENIED
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NudgePermission.NOT_REQUIRED
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) NudgePermission.GRANTED else NudgePermission.DENIED
    }

    /**
     * True when the player has never been asked and asking is possible, so the caller knows the
     * difference between "not yet asked" and "said no". A player who said no is never asked
     * again by this app: the game is complete without any of this.
     */
    fun shouldRequestPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            permission(context) == NudgePermission.DENIED &&
            !prefs(context).getBoolean(KEY_ASKED, false)

    /**
     * Records that the dialog was shown, whatever the player answered.
     *
     * Called on the way *in* to the request rather than on the way out, because the outcome we
     * must not repeat is "shown", not "granted": a dialog the system silently swallowed still
     * consumed a chance.
     */
    fun markPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    // ---------------------------------------------------------------- the one call

    /**
     * Looks at the creature and says at most one thing about it.
     *
     * [state] must already have been advanced to now: the jeopardy half of [Nudges] is a claim
     * about the present tense, and handing it a stale save would make it one about the past.
     */
    fun consider(
        context: Context,
        state: PetState,
        events: List<GameEvent>,
        config: GameConfig,
        settings: NudgeSettings = NudgeSettings(),
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val before = loadLedger(context)
        val outcome = Nudges.decide(
            NudgeInput(
                state = state,
                config = config,
                events = events,
                nowMillis = nowMillis,
                localMinuteOfDay = localMinuteOfDay(nowMillis),
                ledger = before,
                permission = permission(context),
                enabled = config.notificationsEnabled,
                settings = settings,
                appInForeground = foreground,
            ),
        )
        if (outcome.withdraw) withdraw(context)
        val nudge = outcome.post
        // Nothing is written down about a notification that did not go out. The permission can be
        // revoked between the decision and the post, and a ledger that recorded the illness as
        // said would leave the player with silence about a creature that is dying.
        val delivered = nudge == null || post(context, nudge)
        saveLedger(context, if (delivered) outcome.ledger else before)
    }

    /**
     * The player came back. Clears the budget and takes down whatever was pinned, because an
     * entry in the shade about a creature the player is currently looking at is clutter.
     */
    fun onAppOpened(context: Context) {
        foreground = true
        withdraw(context)
        // Off the main thread: this is called from an activity callback and the ledger write
        // touches the disk. Nothing waits on it — the flag above is what silences the worker,
        // and it took effect a line ago.
        val app = context.applicationContext
        scope.launch { saveLedger(app, Nudges.markOpened(loadLedger(app))) }
    }

    fun onAppBackgrounded() {
        foreground = false
    }

    // ---------------------------------------------------------------- posting

    private fun post(context: Context, nudge: Nudge): Boolean {
        // Re-checked here as well as in the decision, because the two are not the same instant
        // and because this is the call site the platform holds to the permission.
        if (permission(context) == NudgePermission.DENIED) return false
        val builder = NotificationCompat.Builder(context, channelId(nudge.channel))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(nudge.title)
            .setContentText(nudge.body)
            // The lines are one sentence long, but a narrow screen still truncates some of them,
            // and half of one of these lines is worse than none of it.
            .setStyle(NotificationCompat.BigTextStyle().bigText(nudge.body))
            .setAutoCancel(true)
            .setCategory(
                if (nudge.channel == NudgeChannel.URGENT) NotificationCompat.CATEGORY_REMINDER
                else NotificationCompat.CATEGORY_STATUS,
            )
            .setPriority(
                if (nudge.channel == NudgeChannel.URGENT) NotificationCompat.PRIORITY_DEFAULT
                else NotificationCompat.PRIORITY_LOW,
            )
            // The worker re-posts the same id whenever a decision survives its filters. Without
            // this, a replaced notification buzzes again, which is the exact behaviour the whole
            // dedupe layer exists to prevent — one line of it living down here instead.
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))

        nudge.actions.forEach { action ->
            builder.addAction(R.drawable.ic_notification, action.label, actionIntent(context, action))
        }
        val notification: Notification = builder.build()
        // Throws on a device where the permission went away inside the last two statements.
        return runCatching { NotificationManagerCompat.from(context).notify(ID_NUDGE, notification) }.isSuccess
    }

    /** Takes down what is pinned. Used when it stops being true, and when the player comes back. */
    fun withdraw(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_NUDGE) }
    }

    private fun channelId(channel: NudgeChannel): String =
        if (channel == NudgeChannel.URGENT) CHANNEL_URGENT else CHANNEL_LIFE

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            // Immutable is required from API 31 and correct everywhere: nobody else fills these in.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, action: NudgeAction): PendingIntent {
        val intent = Intent(context, NudgeActionReceiver::class.java).setAction(action.id)
        return PendingIntent.getBroadcast(
            context,
            // A request code per action, so two buttons never collide on one PendingIntent.
            action.ordinal + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---------------------------------------------------------------- the ledger

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Kept out of the save on purpose.
     *
     * What has already been said is a fact about this installation and not about the creature, so
     * it has no business travelling in an exported save or being restored onto a second device
     * alongside one. It is also written from a background worker that must not race the view
     * model's writes to [PetRepository], and a separate file is the cheapest way to be sure of
     * that.
     */
    private fun loadLedger(context: Context): NudgeLedger =
        Nudges.decode(runCatching { prefs(context).getString(KEY_LEDGER, null) }.getOrNull())

    /**
     * `commit` rather than `apply`, and therefore never on the main thread: every caller of this
     * is a background worker or a receiver whose process may not exist a second from now, and an
     * `apply` that never reached the disk costs the player a repeated notification.
     */
    private fun saveLedger(context: Context, ledger: NudgeLedger) {
        runCatching { prefs(context).edit().putString(KEY_LEDGER, Nudges.encode(ledger)).commit() }
    }

    /**
     * Minutes since the player's local midnight.
     *
     * [Calendar] rather than `java.time`, because `minSdk` is 24 and core library desugaring is
     * off in `app/build.gradle.kts`. Reading it fresh each time is also what makes quiet hours
     * behave over a time-zone change or the end of summer time: this is the player's own clock,
     * whatever it currently says, and the answer is not cached anywhere.
     */
    private fun localMinuteOfDay(nowMillis: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMillis
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    // ---------------------------------------------------------------- the button

    /**
     * Applies the shade's one action. Called from [NudgeActionReceiver], off the main thread.
     *
     * The save is advanced to now *before* the medicine is given, in one call, exactly as it is
     * when the app opens. Medicating the stale save on disk would hand back health the creature
     * has not had for hours, and advancing in fifteen-minute chops is the mistake `CareWorker`
     * documents at length — one absence, one catch-up.
     */
    internal suspend fun giveMedicine(context: Context) {
        val repo = PetRepository(context)
        val saved = repo.currentState() ?: return
        val config = repo.currentConfig()
        val advanced = Simulation.advance(saved, System.currentTimeMillis(), config)
        // Re-validated on arrival, and it has to be: the notification may have sat in the shade
        // for hours, and CareActions is where "too late for medicine" and "you are out of it"
        // are decided. This path gets no say in either.
        val result = CareActions.useMedicine(advanced.state)
        if (!result.accepted) return
        val recorded = Chronicle.record(result.state, result.events, config)
        repo.save(Chronicle.forPlayerMilestone(recorded, config))
    }

    /** Runs a suspending job from a receiver, holding the process up until it finishes. */
    internal fun runDetached(pendingResult: BroadcastReceiver.PendingResult?, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } finally {
                pendingResult?.finish()
            }
        }
    }
}

/**
 * Receives the notification's action buttons.
 *
 * Declared in the manifest and not exported: the only sender is a [PendingIntent] this app
 * created. Everything it does is re-validated in the domain on arrival, because a notification
 * can sit in the shade for hours and the creature it was about does not wait.
 */
class NudgeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            NudgeAction.MEDICINE.id -> {
                // The claim in the shade has been answered, whatever the outcome. Taking it down
                // first means a player who taps twice does not see it linger and tap again.
                Notifier.withdraw(app)
                Notifier.runDetached(goAsync()) { Notifier.giveMedicine(app) }
            }
        }
    }
}
