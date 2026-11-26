package com.smartvolumescheduler.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val supportedActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
        if (!supportedActions.contains(action)) {
            return
        }

        val pendingResult = goAsync()
        try {
            VolumeSchedulerStorage.ensureDefaults(context)
            val (restoredCount, _) = VolumeAlarmManager.rescheduleAll(context)
            val reason = when (action) {
                Intent.ACTION_BOOT_COMPLETED -> "DEVICE_BOOT"
                "android.intent.action.QUICKBOOT_POWERON" -> "QUICKBOOT"
                Intent.ACTION_MY_PACKAGE_REPLACED -> "APP_UPDATE"
                Intent.ACTION_TIME_CHANGED -> "CLOCK_CHANGED"
                Intent.ACTION_TIMEZONE_CHANGED -> "TIMEZONE_CHANGED"
                else -> action
            }
            VolumeSchedulerStorage.recordBootRestore(context, reason, restoredCount)
        } finally {
            pendingResult.finish()
        }
    }
}
