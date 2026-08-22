package com.neopal.pet

import android.app.Application
import com.neopal.pet.work.CareWorker
import com.neopal.pet.work.Notifications

/** Sets up the notification channel and the background care worker exactly once. */
class NeoPalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        CareWorker.schedule(this)
    }
}
