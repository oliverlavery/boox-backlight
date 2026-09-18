package dev.shadow.booxbacklight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the learning service after a device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            LearnService.start(context)
        }
    }
}
