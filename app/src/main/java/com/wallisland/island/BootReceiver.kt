package com.wallisland.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the island back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IslandService.start(context)
    }
}
