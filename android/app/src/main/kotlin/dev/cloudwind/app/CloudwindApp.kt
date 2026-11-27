package dev.cloudwind.app

import android.app.Application

class CloudwindApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        if (SecureStore(this).loadPairing() != null) Scheduler.schedule(this)
    }
}
