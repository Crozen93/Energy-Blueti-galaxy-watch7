package com.example.bluettiwatch

import android.app.Application
import com.example.bluettiwatch.alerts.AlertMonitor
import com.example.bluettiwatch.work.RefreshScheduler

class BluettiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RefreshScheduler.apply(this)
        AlertMonitor.ensureChannel(this)
    }
}
