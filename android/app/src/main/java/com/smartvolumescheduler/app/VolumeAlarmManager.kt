package com.smartvolumescheduler.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Calendar
import java.util.UUID

data class AlarmDispatchResult(
    val scheduled: Boolean,
    val exact: Boolean,
    val nextTriggerAt: Long,
    val mode: String,
    val executedImmediately: Boolean = false,
    val executionOutcome: RuleExecutionOutcome? = null,
    val message: String
)

data class ProfileRecalcResult(
    val resolution: ProfileResolution,
    val overlapReport: ProfileOverlapReport,
    val dispatch: AlarmDispatchResult,
    val executedImmediately: Boolean,
    val executionOutcome: ProfileExecutionOutcome?
)

object VolumeAlarmManager {

    const val ACTION_EXECUTE_SCHEDULE = "com.smartvolumescheduler.app.ACTION_EXECUTE_SCHEDULE"
    const val ACTION_EXECUTE_TEST_ALARM = "com.smartvolumescheduler.app.ACTION_EXECUTE_TEST_ALARM"
    const val ACTION_EXECUTE_PROFILE_TRANSITION = "com.smartvolumescheduler.app.ACTION_EXECUTE_PROFILE_TRANSITION"
    const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
    const val EXTRA_SLOT_MILLIS = "extra_slot_millis"
    const val EXTRA_IS_TEST = "extra_is_test"
    const val EXTRA_IS_PROFILE_TRANSITION = "extra_is_profile_transition"
    const val EXTRA_TEST_STREAM = "extra_test_stream"
    const val EXTRA_TEST_PERCENTAGE = "extra_test_percentage"
    private const val REQUEST_CODE_PROFILE_TRANSITION = 91001

    fun canScheduleExactAlarms(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }

    private fun parseHourMinute(time: String): Pair<Int, Int> {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return hour to minute
    }

    fun getPassedTodaySlotMillis(
        time: String,
        days: List<Int>,
        nowMillis: Long = System.currentTimeMillis()
    ): Long? {
        val (hour, minute) = parseHourMinute(time)
        val activeDays = if (days.isEmpty()) setOf(0, 1, 2, 3, 4, 5, 6) else days.toSet()
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val todayDay = nowCal.get(Calendar.DAY_OF_WEEK) - 1 // Sun=0 .. Sat=6
        if (!activeDays.contains(todayDay)) {
            return null
        }

        val todaySlot = (nowCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return if (todaySlot <= nowMillis) todaySlot else null
    }

    fun computeNextTriggerMillis(
        time: String,
        days: List<Int>,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val (hour, minute) = parseHourMinute(time)
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

    private fun requestCodeForRule(scheduleId: String): Int {
        return scheduleId.hashCode() and 0x7FFFFFFF
    }

    private fun buildSchedulePendingIntent(
        context: Context,
        scheduleId: String,
        flags: Int,
        slotMillis: Long = 0L
    ): PendingIntent? {
        val intent = Intent(context, VolumeAlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_SCHEDULE
            data = Uri.parse("smartvolume://schedule/$scheduleId")
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_SLOT_MILLIS, slotMillis)
            putExtra(EXTRA_IS_TEST, false)
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            requestCodeForRule(scheduleId),
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
        // Always cancel any existing alarm for this rule ID first to prevent duplicates
        cancelSchedule(context, schedule.id)

        if (!VolumeSchedulerStorage.isSchedulerEnabled(context) || !schedule.enabled) {
            val updated = schedule.copy(nextTriggerAt = 0L)
            VolumeSchedulerStorage.upsertSchedule(context, updated)
            return AlarmDispatchResult(
                scheduled = false,
                exact = false,
                nextTriggerAt = 0L,
                mode = "disabled",
                message = if (!schedule.enabled) "Rule disabled; alarm canceled." else "Scheduler paused; alarm canceled."
            )
        }

        val afterMillis = maxOf(System.currentTimeMillis(), schedule.lastExecutedSlotMillis + 1000L)
        val triggerAt = computeNextTriggerMillis(schedule.time, schedule.days, afterMillis)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildSchedulePendingIntent(
            context = context,
            scheduleId = schedule.id,
            flags = PendingIntent.FLAG_UPDATE_CURRENT,
            slotMillis = triggerAt
        ) ?: return AlarmDispatchResult(
            scheduled = false,
            exact = false,
            nextTriggerAt = 0L,
            mode = "error",
            message = "Failed to create PendingIntent for schedule ${schedule.id}."
        )

        val exactAllowed = canScheduleExactAlarms(context)
        val (usedExact, mode) = if (exactAllowed) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                true to "exact"
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                false to "inexact_fallback"
            }
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            false to "inexact_fallback"
        }

        val updated = schedule.copy(nextTriggerAt = triggerAt)
        VolumeSchedulerStorage.upsertSchedule(context, updated)

        val msg = if (usedExact) {
            "Scheduled exact alarm via AlarmManager."
        } else {
            "Exact alarm permission missing; scheduled inexact Doze-safe fallback alarm."
        }

        return AlarmDispatchResult(
            scheduled = true,
            exact = usedExact,
            nextTriggerAt = triggerAt,
            mode = mode,
            message = msg
        )
    }

    fun applyPastTimeIfNeededAndSchedule(
        context: Context,
        schedule: VolumeSchedule,
        forceImmediateIfPassed: Boolean
    ): AlarmDispatchResult {
        cancelSchedule(context, schedule.id)

        if (!VolumeSchedulerStorage.isSchedulerEnabled(context) || !schedule.enabled) {
            val updated = schedule.copy(nextTriggerAt = 0L)
            VolumeSchedulerStorage.upsertSchedule(context, updated)
            return AlarmDispatchResult(
                scheduled = false,
                exact = false,
                nextTriggerAt = 0L,
                mode = "disabled",
                message = if (!schedule.enabled) "Rule saved in disabled state." else "Rule saved while master scheduler is paused."
            )
        }

        val now = System.currentTimeMillis()
        val passedSlotMillis = getPassedTodaySlotMillis(schedule.time, schedule.days, now)
        val shouldExecuteNow = passedSlotMillis != null &&
            (forceImmediateIfPassed || schedule.lastExecutedSlotMillis != passedSlotMillis)

        var workingSchedule = schedule
        var outcome: RuleExecutionOutcome? = null

        if (shouldExecuteNow && passedSlotMillis != null) {
            outcome = VolumeRuleExecutor.executeRule(
                context = context,
                schedule = workingSchedule,
                source = "IMMEDIATE_PAST_TIME",
                slotMillis = passedSlotMillis
            )
            workingSchedule = outcome.updatedSchedule
        }

        val nextDispatch = scheduleSingle(context, workingSchedule)
        val combinedMessage = if (outcome != null) {
            val immediateStatus = if (outcome.allSucceeded) {
                "Rule time (${schedule.time}) already passed today — applied immediately (${outcome.summaryMessage})."
            } else {
                "Rule time (${schedule.time}) already passed today — attempted immediate execution with errors (${outcome.summaryMessage})."
            }
            "$immediateStatus Next occurrence: ${nextDispatch.message}"
        } else {
            nextDispatch.message
        }

        return nextDispatch.copy(
            executedImmediately = outcome != null,
            executionOutcome = outcome,
            message = combinedMessage
        )
    }

    private fun buildProfileTransitionPendingIntent(
        context: Context,
        flags: Int,
        transitionAt: Long = 0L
    ): PendingIntent? {
        val intent = Intent(context, VolumeAlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_PROFILE_TRANSITION
            data = Uri.parse("smartvolume://profile/transition")
            putExtra(EXTRA_IS_PROFILE_TRANSITION, true)
            putExtra(EXTRA_IS_TEST, false)
            putExtra(EXTRA_SLOT_MILLIS, transitionAt)
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE_PROFILE_TRANSITION,
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelProfileTransition(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val existing = buildProfileTransitionPendingIntent(context, PendingIntent.FLAG_NO_CREATE)
        if (existing != null) {
            am.cancel(existing)
            existing.cancel()
        }
    }

    fun scheduleNextProfileTransition(
        context: Context,
        afterMillis: Long = System.currentTimeMillis()
    ): AlarmDispatchResult {
        cancelProfileTransition(context)

        val schedulerEnabled = VolumeSchedulerStorage.isSchedulerEnabled(context)
        val profiles = VolumeSchedulerStorage.getProfiles(context)
        val updatedProfiles = profiles.map { p ->
            if (schedulerEnabled && p.enabled) {
                p.copy(nextTransitionAt = VolumeProfileManager.computeNextStartMillis(p, afterMillis))
            } else {
                p.copy(nextTransitionAt = 0L)
            }
        }
        VolumeSchedulerStorage.saveProfiles(context, updatedProfiles)

        if (!schedulerEnabled || updatedProfiles.none { it.enabled }) {
            VolumeSchedulerStorage.setNextProfileTransitionAt(context, 0L)
            return AlarmDispatchResult(
                scheduled = false,
                exact = false,
                nextTriggerAt = 0L,
                mode = "disabled",
                message = if (!schedulerEnabled) {
                    "Master scheduler paused; profile transitions cleared."
                } else {
                    "No enabled volume profiles; no transition alarm queued."
                }
            )
        }

        val nextBoundary = VolumeProfileManager.computeNextTransitionMillis(updatedProfiles, afterMillis)
            ?: run {
                VolumeSchedulerStorage.setNextProfileTransitionAt(context, 0L)
                return AlarmDispatchResult(
                    scheduled = false,
                    exact = false,
                    nextTriggerAt = 0L,
                    mode = "disabled",
                    message = "No upcoming profile transition found."
                )
            }

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildProfileTransitionPendingIntent(
            context = context,
            flags = PendingIntent.FLAG_UPDATE_CURRENT,
            transitionAt = nextBoundary
        ) ?: return AlarmDispatchResult(
            scheduled = false,
            exact = false,
            nextTriggerAt = 0L,
            mode = "error",
            message = "Failed to create PendingIntent for profile transition."
        )

        val exactAllowed = canScheduleExactAlarms(context)
        val (usedExact, mode) = if (exactAllowed) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextBoundary, pendingIntent)
                true to "exact"
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextBoundary, pendingIntent)
                false to "inexact_fallback"
            }
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextBoundary, pendingIntent)
            false to "inexact_fallback"
        }

        VolumeSchedulerStorage.setNextProfileTransitionAt(context, nextBoundary)
        return AlarmDispatchResult(
            scheduled = true,
            exact = usedExact,
            nextTriggerAt = nextBoundary,
            mode = mode,
            message = if (usedExact) {
                "Next profile transition scheduled via exact AlarmManager."
            } else {
                "Exact alarm permission restricted; next profile transition scheduled via inexact fallback."
            }
        )
    }

    fun recalculateProfilesAndSchedule(
        context: Context,
        activateIfCoversNow: Boolean,
        forceApplyActiveIfChanged: Boolean,
        source: String,
        recordConflictLog: Boolean = true
    ): ProfileRecalcResult {
        cancelProfileTransition(context)

        val profiles = VolumeSchedulerStorage.getProfiles(context)
        val overlapReport = VolumeProfileManager.detectOverlaps(profiles)
        val resolution = VolumeProfileManager.resolveProfileAt(context)
        var outcome: ProfileExecutionOutcome? = null
        var appliedSlotMillis = 0L

        when (resolution.status) {
            "paused" -> {
                VolumeSchedulerStorage.setRuntimeActiveProfileId(context, null)
            }

            "conflict" -> {
                VolumeSchedulerStorage.setRuntimeActiveProfileId(context, null)
                if (activateIfCoversNow && recordConflictLog) {
                    val conflictNames = resolution.conflictingProfiles.joinToString(", ") { it.name }
                    VolumeSchedulerStorage.appendLog(
                        context,
                        ExecutionLogEntry(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            source = "PROFILE_CONFLICT_SKIPPED",
                            ruleId = resolution.conflictingProfiles.joinToString(",") { it.id },
                            ruleName = conflictNames,
                            activeProfileName = "Conflict (Unresolved Overlap)",
                            scheduledTime = resolution.conflictingProfiles.firstOrNull()?.startTime ?: "",
                            stream = "none",
                            requestedPercentage = 0,
                            indexBefore = 0,
                            actualIndex = 0,
                            maxIndex = 0,
                            actualPercentage = 0,
                            success = false,
                            failureReason = resolution.message,
                            message = resolution.message
                        )
                    )
                }
            }

            "none" -> {
                VolumeSchedulerStorage.setRuntimeActiveProfileId(context, null)
                VolumeSchedulerStorage.setLastAppliedProfileKey(context, "")
            }

            "active", "fallback" -> {
                val target = resolution.activeProfile
                if (target != null) {
                    val slotMillis = resolution.activeSlotMillis
                    val slotKey = "${target.id}:$slotMillis"
                    val lastKey = VolumeSchedulerStorage.getLastAppliedProfileKey(context)
                    val currentRuntimeId = VolumeSchedulerStorage.getRuntimeActiveProfileId(context)
                    val alreadyAppliedSlot = (lastKey == slotKey) ||
                        (!resolution.isFallback && slotMillis > 0L && target.lastActivatedSlotMillis == slotMillis && currentRuntimeId == target.id)

                    if (activateIfCoversNow && (forceApplyActiveIfChanged || !alreadyAppliedSlot)) {
                        val execSource = if (resolution.isFallback) "PROFILE_FALLBACK" else source
                        val schedLabel = if (resolution.isFallback) "Fallback" else target.startTime
                        outcome = VolumeProfileManager.executeProfile(
                            context = context,
                            profile = target,
                            source = execSource,
                            scheduledTimeLabel = schedLabel,
                            slotMillis = slotMillis,
                            isFallback = resolution.isFallback
                        )
                        appliedSlotMillis = slotMillis
                    } else {
                        VolumeSchedulerStorage.setRuntimeActiveProfileId(context, target.id)
                    }
                }
            }
        }

        val afterMillis = maxOf(System.currentTimeMillis(), appliedSlotMillis + 1000L)
        val dispatch = scheduleNextProfileTransition(context, afterMillis)
        val finalResolution = VolumeProfileManager.resolveProfileAt(context)

        return ProfileRecalcResult(
            resolution = finalResolution,
            overlapReport = overlapReport,
            dispatch = dispatch,
            executedImmediately = outcome != null,
            executionOutcome = outcome
        )
    }

    fun rescheduleAll(context: Context): Pair<Int, Boolean> {
        val schedulerEnabled = VolumeSchedulerStorage.isSchedulerEnabled(context)
        val schedules = VolumeSchedulerStorage.getSchedules(context)
        if (!schedulerEnabled) {
            schedules.forEach { cancelSchedule(context, it.id) }
            cancelProfileTransition(context)
            val clearedProfiles = VolumeSchedulerStorage.getProfiles(context).map {
                it.copy(nextTransitionAt = 0L)
            }
            VolumeSchedulerStorage.saveProfiles(context, clearedProfiles)
            VolumeSchedulerStorage.setNextProfileTransitionAt(context, 0L)
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

        val profileRes = scheduleNextProfileTransition(context)
        if (profileRes.scheduled) count++
        if (!profileRes.exact) allExact = false

        return count to allExact
    }

    fun cancelAll(context: Context) {
        val schedules = VolumeSchedulerStorage.getSchedules(context)
        schedules.forEach { schedule ->
            cancelSchedule(context, schedule.id)
        }
        cancelProfileTransition(context)
        VolumeSchedulerStorage.setNextProfileTransitionAt(context, 0L)
        val clearedProfiles = VolumeSchedulerStorage.getProfiles(context).map {
            it.copy(nextTransitionAt = 0L)
        }
        VolumeSchedulerStorage.saveProfiles(context, clearedProfiles)
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
                "Exact alarm permission missing; test alarm scheduled in ${delaySec}s using inexact AlarmManager fallback."
            }
        )
    }
}
