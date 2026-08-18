package com.neopal.pet.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.neopal.pet.MainActivity
import com.neopal.pet.R
import com.neopal.pet.domain.PetState

/** Push notifications for care reminders. One channel, low importance, never spammy. */
object Notifications {

    const val CHANNEL_CARE = "care_reminders"
    private const val ID_CARE = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_CARE,
            context.getString(R.string.channel_care_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_care_description)
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Picks the single most urgent thing to say. Returns null when the pet is fine, so the
     * worker stays quiet instead of nagging.
     */
    fun careMessage(state: PetState): Pair<String, String>? {
        if (state.isDead) return state.name to "Your pet has passed away. Open the app to start a new generation."
        if (state.isEgg) return null
        return when {
            state.isSick -> state.name to "${state.name} is sick and needs medicine."
            state.stats.satiety < 18f -> state.name to "${state.name} is starving!"
            state.poops >= 3 -> state.name to "The room needs cleaning."
            state.stats.hygiene < 20f -> state.name to "${state.name} needs a bath."
            state.stats.happiness < 22f -> state.name to "${state.name} is lonely and wants to play."
            else -> null
        }
    }

    fun notifyCare(context: Context, title: String, text: String) {
        if (!hasPermission(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_CARE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_CARE, notification) }
    }
}
