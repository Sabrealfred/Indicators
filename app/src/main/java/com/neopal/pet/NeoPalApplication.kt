package com.neopal.pet

import android.app.Application
import com.neopal.pet.data.Notifier
import com.neopal.pet.work.CareWorker

/** Sets up the notification channels and the background care worker exactly once. */
class NeoPalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Two channels now, not one, and the old one is deleted here rather than left behind.
        // The single "care reminders" channel promised nudges about hunger and mess that this
        // app no longer sends; leaving it in place would leave a switch in system settings that
        // controls nothing, which is a worse lie than no switch at all.
        Notifier.createChannels(this)
        CareWorker.schedule(this)
    }
}
