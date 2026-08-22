package com.neopal.pet.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.neopal.pet.data.PetRepository
import com.neopal.pet.domain.WidgetAction
import com.neopal.pet.domain.WidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The creature on the home screen.
 *
 * ### What wakes it
 *
 * Nothing on a timer of its own. `updatePeriodMillis` is zero in the widget's info XML, because
 * the shortest period the system honours is half an hour and a half-hourly wake buys almost
 * nothing here: the thing that actually moves the pet on is [com.neopal.pet.work.CareWorker],
 * which already runs every fifteen minutes for its own reasons and is the only thing that writes
 * the save while the app is closed. So the widget is told when something happened rather than
 * asking whether anything has:
 *
 *  - the worker, after it saves;
 *  - the app, when it goes to the background and flushes what the player just did;
 *  - the host, when the widget is placed, resized, or the launcher restarts;
 *  - one alarm of its own, set for the moment the picture would stop being true.
 *
 * That last one is the interesting one. [WidgetSnapshot.nextLook] asks the simulation when the
 * *picture* changes — not when a stat changes, which is every second — and the alarm is set for
 * then, floored at ten minutes and capped at four hours. A settled pet asks to be left alone for
 * hours; one twenty minutes from being hungry asks for twenty minutes. The alarm is a plain
 * `RTC`, never `RTC_WAKEUP`: it is allowed to arrive late, on the back of some other wake, and
 * it never costs a device that is asleep anything at all.
 *
 * ### What it shows when it cannot refresh
 *
 * The truth, dated. Every refresh runs the same catch-up the app runs when it returns to the
 * foreground, so the numbers are current *at the moment of drawing*; the widget then says which
 * moment that was. A widget the system has not let refresh since breakfast reads "as of 08:14"
 * and is believed exactly as much as it deserves. The alternative — a bare figure that quietly
 * ages — is the thing this design set out not to do.
 */
class PetWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        redraw(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Resizing changes the bitmap the picture has to be drawn at, so it is a redraw and not
        // merely a re-layout.
        redraw(context, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> redraw(context, idsOf(context))
            ACTION_CARE -> care(context, intent)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onDisabled(context: Context) {
        // The last widget is gone; nothing should still be waking up on its behalf.
        runCatching {
            context.getSystemService(AlarmManager::class.java)?.cancel(refreshAlarm(context))
        }
        super.onDisabled(context)
    }

    /**
     * Reads the save, draws the widgets, and books the next look.
     *
     * A broadcast receiver is dead the moment [onReceive] returns, so the work is held open with
     * `goAsync`. It is a few hundred milliseconds of disk read and drawing, well inside the
     * budget, and it happens off the main thread because a launcher stuttering while a tamagotchi
     * decides whether it is hungry would be a poor trade.
     */
    private fun redraw(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                drawInto(app, ids)
            } catch (error: Throwable) {
                // A widget update that throws takes the whole app process with it and leaves the
                // launcher showing a permanent grey box that only a reinstall clears. Whatever
                // went wrong, the right answer is to leave the last good picture where it is.
                lastError = error
            } finally {
                pending.finish()
            }
        }
    }

    private fun care(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_ACTION) ?: return
        val action = WidgetAction.entries.firstOrNull { it.name == name } ?: return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                PetWidgetCare.perform(app, action)
                // Whether it worked or was refused, the widget is now out of date about
                // something and the honest thing is to look again.
                drawInto(app, idsOf(app))
            } catch (error: Throwable) {
                lastError = error
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun drawInto(context: Context, ids: IntArray) {
        val repository = PetRepository(context)
        val saved = repository.currentState()
        val config = repository.currentConfig()
        val now = System.currentTimeMillis()
        // One snapshot for every widget on every screen: they are all looking at one pet, and
        // two of them disagreeing about what it wants would be its own small lie.
        val snapshot = WidgetSnapshot.of(saved, config, now)
        val manager = AppWidgetManager.getInstance(context)
        ids.forEach { id ->
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val views = PetWidgetViews.build(context, snapshot, config, options, id)
            runCatching { manager.updateAppWidget(id, views) }
        }
        bookNextLook(context, WidgetSnapshot.nextLook(saved, config, now))
    }

    /** Books one inexact, non-waking alarm for the moment the picture stops being true. */
    private fun bookNextLook(context: Context, seconds: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + seconds.coerceAtLeast(60L) * 1000L
        // RTC, not RTC_WAKEUP: this is worth doing when the phone is already up and worth
        // nothing at all at three in the morning.
        runCatching { manager.set(AlarmManager.RTC, at, refreshAlarm(context)) }
    }

    companion object {
        const val ACTION_REFRESH = "com.neopal.pet.widget.REFRESH"
        const val ACTION_CARE = "com.neopal.pet.widget.CARE"
        const val EXTRA_ACTION = "com.neopal.pet.widget.action"

        /**
         * Survives the receiver, which is destroyed the moment it returns. Every job it holds is
         * bounded by a `goAsync` token, so nothing here outlives the broadcast that started it.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * The last thing that went wrong, kept for a debugger to find.
         *
         * A widget's failures are invisible: it simply keeps showing the picture from before.
         * That is the right behaviour and the wrong diagnostic, and this is the cheapest
         * possible middle — the same shape as the "silent failure the local path covers up"
         * already recorded in docs/PLAN.md.
         */
        @Volatile
        var lastError: Throwable? = null
            private set

        /**
         * Redraws every placed widget. This is the call site for anything that writes the save:
         * the worker after its tick, and the app when it goes to the background.
         *
         * Cheap when there is no widget on any screen, which is the common case: it asks the
         * host for the ids and sends nothing if there are none.
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            if (idsOf(app).isEmpty()) return
            runCatching {
                app.sendBroadcast(Intent(app, PetWidget::class.java).setAction(ACTION_REFRESH))
            }
        }

        private fun idsOf(context: Context): IntArray = runCatching {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, PetWidget::class.java))
        }.getOrDefault(IntArray(0))

        private fun refreshAlarm(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            Intent(context, PetWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Distinct from any widget id, which is what the care taps use. */
        private const val ALARM_REQUEST = 0x4E45
    }
}
