package com.smartvolumescheduler.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class VolumeAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TEST_CHANNEL_ID = "smart_volume_test_alerts"
        const val ACTION_INTERNAL_VOLUME_UPDATED = "com.smartvolumescheduler.app.INTERNAL_VOLUME_UPDATED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmartVolumeScheduler::AlarmWakeLock"
        )
        wakeLock.setReferenceCounted(false)

        try {
            wakeLock.acquire(5000L)
            val isTest = intent.getBooleanExtra(VolumeAlarmManager.EXTRA_IS_TEST, false)

            if (isTest) {
                val stream = intent.getStringExtra(VolumeAlarmManager.EXTRA_TEST_STREAM)
                    ?: AudioStreamType.RINGTONE.key
                val pct = intent.getIntExtra(VolumeAlarmManager.EXTRA_TEST_PERCENTAGE, 70)
                val result = VolumeController.applyVolume(context, stream, pct, showSystemUi = false)
                val now = System.currentTimeMillis()
                VolumeSchedulerStorage.appendLog(
                    context,
                    ExecutionLogEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = now,
                        source = "TEST_ALARM",
                        scheduleId = "test-alarm",
                        stream = result.stream,
                        requestedPercentage = result.requestedPercentage,
                        actualIndex = result.actualIndex,
                        maxIndex = result.maxIndex,
                        actualPercentage = result.actualPercentage,
                        success = result.success,
                        message = result.message
                    )
                )
                postSilentTestNotification(context, result)
                notifyForegroundUi(context)
                return
            }

            val scheduleId = intent.getStringExtra(VolumeAlarmManager.EXTRA_SCHEDULE_ID) ?: return
            val schedulerEnabled = VolumeSchedulerStorage.isSchedulerEnabled(context)
            val schedule = VolumeSchedulerStorage.getScheduleById(context, scheduleId) ?: return

            if (!schedulerEnabled || !schedule.enabled) {
                return
            }

            val result = VolumeController.applyVolume(
                context = context,
                streamKey = schedule.stream,
                requestedPercentage = schedule.percentage,
                showSystemUi = false
            )

            val now = System.currentTimeMillis()
            val updatedSchedule = schedule.copy(
                lastExecutedAt = now,
                lastExecutionSuccess = result.success,
                lastExecutionMessage = result.message
            )

            VolumeSchedulerStorage.appendLog(
                context,
                ExecutionLogEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    source = "SCHEDULE",
                    scheduleId = schedule.id,
                    stream = result.stream,
                    requestedPercentage = result.requestedPercentage,
                    actualIndex = result.actualIndex,
                    maxIndex = result.maxIndex,
                    actualPercentage = result.actualPercentage,
                    success = result.success,
                    message = result.message
                )
            )

            // Persist execution outcome and immediately schedule the next occurrence
            VolumeSchedulerStorage.upsertSchedule(context, updatedSchedule)
            VolumeAlarmManager.scheduleSingle(context, updatedSchedule)
            notifyForegroundUi(context)
        } finally {
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            } catch (_: Exception) {
            }
            pendingResult.finish()
        }
    }

    private fun notifyForegroundUi(context: Context) {
        val localIntent = Intent(ACTION_INTERNAL_VOLUME_UPDATED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(localIntent)
    }

    private fun postSilentTestNotification(context: Context, result: VolumeApplyResult) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TEST_CHANNEL_ID,
                "Scheduled Test Diagnostics",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent confirmation when a test alarm executes in the background."
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }

        val title = if (result.success) {
            "Test alarm executed"
        } else {
            "Test alarm blocked"
        }

        val notification = NotificationCompat.Builder(context, TEST_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(title)
            .setContentText(result.message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .build()

        nm.notify(44102, notification)
    }
}
