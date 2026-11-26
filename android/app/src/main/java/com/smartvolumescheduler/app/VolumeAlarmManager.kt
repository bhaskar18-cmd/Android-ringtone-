package com.smartvolumescheduler.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Calendar

data class AlarmDispatchResult(
    val scheduled: Boolean,
    val exact: Boolean,
    val nextTriggerAt: Long,
    val mode: String,
    val message: String
)

object VolumeAlarmManager {

    const val ACTION_EXECUTE_SCHEDULE = "com.smartvolumescheduler.app.ACTION_EXECUTE_SCHEDULE"
    const val ACTION_EXECUTE_TEST_ALARM = "com.smartvolumescheduler.app.ACTION_EXECUTE_TEST_ALARM"
    const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
    const val EXTRA_IS_TEST = "extra_is_test"
    const val EXTRA_TEST_STREAM = "extra_test_stream"
    const val EXTRA_TEST_PERCENTAGE = "extra_test_percentage"

    fun canScheduleExactAlarms(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun computeNextTriggerMillis(
        time: String,
        days: List<Int>,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val activeDays = if (days.isEmpty()) setOf(0, 1, 2, 3, 4, 5, 6) else days.toSet()

        val base = Calendar.getInstance().apply {
            timeInMillis = nowMillis
        }

        for (dayOffset in 0..7) {
            val candidate = (base.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dayZeroIndexed = candidate.get(Calendar.DAY_OF_WEEK) - 1 // Sun=0 .. Sat=6
            if (activeDays.contains(dayZeroIndexed) && candidate.timeInMillis > nowMillis + 500L) {
                return candidate.timeInMillis
            }
        }
        return nowMillis + 24 * 60 * 60 * 1000L
    }

    private fun buildSchedulePendingIntent(
        context: Context,
        scheduleId: String,
        flags: Int
    ): PendingIntent {
        val intent = Intent(context, VolumeAlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_SCHEDULE
            data = Uri.parse("smartvolume://schedule/$scheduleId")
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_IS_TEST, false)
        }
        val requestCode = scheduleId.hashCode()
        return PendingIntent.getBroadcast(
            context.applicationContext,
            requestCode,
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelSchedule(context: Context, scheduleId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val existing = buildSchedulePendingIntent(
            context,
            scheduleId,
            PendingIntent.FLAG_NO_CREATE
        )
        if (existing != null) {
            am.cancel(existing)
            existing.cancel()
        }
    }

    fun scheduleSingle(context: Context, schedule: VolumeSchedule): AlarmDispatchResult {
        if (!VolumeSchedulerStorage.isSchedulerEnabled(context) || !schedule.enabled) {
            cancelSchedule(context, schedule.id)
            val updated = schedule.copy(nextTriggerAt = 0L)
            VolumeSchedulerStorage.upsertSchedule(context, updated)
            return AlarmDispatchResult(
                scheduled = false,
                exact = false,
                nextTriggerAt = 0L,
                mode = "disabled",
                message = "Schedule saved in disabled state."
            )
        }

        val triggerAt = computeNextTriggerMillis(schedule.time, schedule.days)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildSchedulePendingIntent(
            context,
            schedule.id,
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        val exactAllowed = canScheduleExactAlarms(context)
        val usedExact: Boolean
        val mode: String

        if (exactAllowed) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                usedExact = true
                mode = "exact"
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                usedExact = false
                mode = "inexact_fallback"
            }
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            usedExact = false
            mode = "inexact_fallback"
        }

        val updated = schedule.copy(nextTriggerAt = triggerAt)
        VolumeSchedulerStorage.upsertSchedule(context, updated)

        val msg = if (usedExact) {
            "Scheduled exact alarm via AlarmManager."
        } else {
            "Exact alarm permission not granted; scheduled inexact Doze-safe fallback alarm."
        }

        return AlarmDispatchResult(
            scheduled = true,
            exact = usedExact,
            nextTriggerAt = triggerAt,
            mode = mode,
            message = msg
        )
    }

    fun rescheduleAll(context: Context): Pair<Int, Boolean> {
        val schedulerEnabled = VolumeSchedulerStorage.isSchedulerEnabled(context)
        val schedules = VolumeSchedulerStorage.getSchedules(context)
        if (!schedulerEnabled) {
            schedules.forEach { cancelSchedule(context, it.id) }
            return 0 to canScheduleExactAlarms(context)
        }

        var count = 0
        var allExact = true
        schedules.forEach { schedule ->
            if (schedule.enabled) {
                val res = scheduleSingle(context, schedule)
                if (res.scheduled) count++
                if (!res.exact) allExact = false
            } else {
                cancelSchedule(context, schedule.id)
            }
        }
        return count to allExact
    }

    fun cancelAll(context: Context) {
        val schedules = VolumeSchedulerStorage.getSchedules(context)
        schedules.forEach { schedule ->
            cancelSchedule(context, schedule.id)
        }
    }

    fun scheduleTestAlarm(
        context: Context,
        secondsFromNow: Int,
        streamKey: String,
        percentage: Int
    ): AlarmDispatchResult {
        val delaySec = secondsFromNow.coerceIn(3, 300)
        val triggerAt = System.currentTimeMillis() + delaySec * 1000L
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, VolumeAlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_TEST_ALARM
            data = Uri.parse("smartvolume://test/$streamKey")
            putExtra(EXTRA_IS_TEST, true)
            putExtra(EXTRA_TEST_STREAM, streamKey)
            putExtra(EXTRA_TEST_PERCENTAGE, percentage.coerceIn(0, 100))
        }

        val pi = PendingIntent.getBroadcast(
            context.applicationContext,
            90421,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val exactAllowed = canScheduleExactAlarms(context)
        val usedExact = if (exactAllowed) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                true
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                false
            }
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            false
        }

        return AlarmDispatchResult(
            scheduled = true,
            exact = usedExact,
            nextTriggerAt = triggerAt,
            mode = if (usedExact) "exact" else "inexact_fallback",
            message = if (usedExact) {
                "Test alarm scheduled in ${delaySec}s via exact AlarmManager."
            } else {
                "Test alarm scheduled in ${delaySec}s using inexact AlarmManager fallback (OS may delay delivery slightly)."
            }
        )
    }
}
