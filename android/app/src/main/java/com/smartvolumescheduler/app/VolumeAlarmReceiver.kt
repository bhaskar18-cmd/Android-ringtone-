package com.smartvolumescheduler.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.util.UUID

class VolumeAlarmReceiver : BroadcastReceiver() {

    companion object {
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

        var asyncCompleted = false
        val completeAsync = {
            if (!asyncCompleted) {
                asyncCompleted = true
                try {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                } catch (_: Exception) {
                }
                try {
                    pendingResult.finish()
                } catch (_: Exception) {
                }
            }
        }

        try {
            wakeLock.acquire(5000L)
            val isTest = intent.getBooleanExtra(VolumeAlarmManager.EXTRA_IS_TEST, false)

            if (isTest) {
                val streamKey = intent.getStringExtra(VolumeAlarmManager.EXTRA_TEST_STREAM)
                    ?: AudioStreamType.RINGTONE.key
                val pct = intent.getIntExtra(VolumeAlarmManager.EXTRA_TEST_PERCENTAGE, 70)
                val streamType = AudioStreamType.fromKey(streamKey)

                if (streamType == null) {
                    val invalidResult = StreamTestResult(
                        stream = streamKey,
                        requestedPercentage = pct,
                        indexBefore = 0,
                        targetIndex = 0,
                        actualIndex = 0,
                        minIndex = 0,
                        maxIndex = 0,
                        actualPercentage = 0,
                        volumeApplied = false,
                        playbackStarted = false,
                        streamVerified = false,
                        callSessionActive = false,
                        audioMode = VolumeController.getAudioModeString(
                            VolumeController.getAudioManager(context)
                        ),
                        ringerMode = VolumeController.getRingerModeString(
                            VolumeController.getAudioManager(context)
                        ),
                        dndBlocked = false,
                        fixedVolume = false,
                        failureReason = "Unsupported audio stream: $streamKey",
                        source = "BACKGROUND_TEST_ALARM",
                        timestamp = System.currentTimeMillis(),
                        message = "Unsupported audio stream: $streamKey"
                    )
                    VolumeSchedulerStorage.saveLastTestAlarmResult(context, invalidResult)
                    notifyForegroundUi(context)
                    completeAsync()
                    return
                }

                val testResult = VolumeController.runStreamTest(
                    context = context,
                    stream = streamType,
                    requestedPercentage = pct,
                    source = "BACKGROUND_TEST_ALARM",
                    onPlaybackComplete = { completeAsync() }
                )

                VolumeSchedulerStorage.saveLastTestAlarmResult(context, testResult)
                VolumeSchedulerStorage.appendLog(
                    context,
                    ExecutionLogEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = testResult.timestamp,
                        source = "BACKGROUND_TEST_ALARM",
                        ruleId = "test-alarm",
                        ruleName = "Background Test (${streamType.label})",
                        scheduledTime = "",
                        stream = testResult.stream,
                        requestedPercentage = testResult.requestedPercentage,
                        indexBefore = testResult.indexBefore,
                        actualIndex = testResult.actualIndex,
                        maxIndex = testResult.maxIndex,
                        actualPercentage = testResult.actualPercentage,
                        success = testResult.volumeApplied,
                        failureReason = testResult.failureReason,
                        message = testResult.message
                    )
                )
                notifyForegroundUi(context)
                return
            }

            val isProfileTransition = intent.action == VolumeAlarmManager.ACTION_EXECUTE_PROFILE_TRANSITION ||
                intent.getBooleanExtra(VolumeAlarmManager.EXTRA_IS_PROFILE_TRANSITION, false)

            if (isProfileTransition) {
                if (!VolumeSchedulerStorage.isSchedulerEnabled(context)) {
                    VolumeAlarmManager.cancelProfileTransition(context)
                    VolumeSchedulerStorage.appendLog(
                        context,
                        ExecutionLogEntry(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            source = "PROFILE_SKIPPED",
                            ruleId = "",
                            ruleName = "Profile Transition",
                            activeProfileName = "",
                            scheduledTime = "",
                            stream = "none",
                            requestedPercentage = 0,
                            indexBefore = 0,
                            actualIndex = 0,
                            maxIndex = 0,
                            actualPercentage = 0,
                            success = false,
                            failureReason = "Master scheduler disabled when profile transition fired.",
                            message = "Profile transition skipped because master scheduler is paused."
                        )
                    )
                    completeAsync()
                    return
                }

                VolumeAlarmManager.recalculateProfilesAndSchedule(
                    context = context,
                    activateIfCoversNow = true,
                    forceApplyActiveIfChanged = false,
                    source = "PROFILE_SCHEDULE"
                )
                notifyForegroundUi(context)
                completeAsync()
                return
            }

            val scheduleId = intent.getStringExtra(VolumeAlarmManager.EXTRA_SCHEDULE_ID)
            if (scheduleId.isNullOrBlank()) {
                VolumeSchedulerStorage.appendLog(
                    context,
                    ExecutionLogEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        source = "RULE_SKIPPED",
                        ruleId = "",
                        ruleName = "Unknown Rule",
                        scheduledTime = "",
                        stream = "none",
                        requestedPercentage = 0,
                        indexBefore = 0,
                        actualIndex = 0,
                        maxIndex = 0,
                        actualPercentage = 0,
                        success = false,
                        failureReason = "Native receiver failure: missing rule ID in broadcast intent.",
                        message = "Alarm fired without a rule ID."
                    )
                )
                completeAsync()
                return
            }

            val schedulerEnabled = VolumeSchedulerStorage.isSchedulerEnabled(context)
            val schedule = VolumeSchedulerStorage.getScheduleById(context, scheduleId)
            if (schedule == null) {
                VolumeSchedulerStorage.appendLog(
                    context,
                    ExecutionLogEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        source = "RULE_SKIPPED",
                        ruleId = scheduleId,
                        ruleName = "Deleted Rule",
                        scheduledTime = "",
                        stream = "none",
                        requestedPercentage = 0,
                        indexBefore = 0,
                        actualIndex = 0,
                        maxIndex = 0,
                        actualPercentage = 0,
                        success = false,
                        failureReason = "Alarm canceled or rule removed before execution.",
                        message = "Rule $scheduleId no longer exists."
                    )
                )
                VolumeAlarmManager.cancelSchedule(context, scheduleId)
                completeAsync()
                return
            }

            if (!schedulerEnabled || !schedule.enabled) {
                val skipReason = if (!schedulerEnabled) {
                    "Master scheduler disabled when alarm fired."
                } else {
                    "Rule disabled when alarm fired."
                }
                VolumeSchedulerStorage.appendLog(
                    context,
                    ExecutionLogEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        source = "RULE_SKIPPED",
                        ruleId = schedule.id,
                        ruleName = schedule.label.ifBlank { "Rule ${schedule.time}" },
                        scheduledTime = schedule.time,
                        stream = "none",
                        requestedPercentage = 0,
                        indexBefore = 0,
                        actualIndex = 0,
                        maxIndex = 0,
                        actualPercentage = 0,
                        success = false,
                        failureReason = skipReason,
                        message = skipReason
                    )
                )
                VolumeAlarmManager.cancelSchedule(context, schedule.id)
                completeAsync()
                return
            }

            val rawSlotMillis = intent.getLongExtra(VolumeAlarmManager.EXTRA_SLOT_MILLIS, 0L)
            val slotMillis = rawSlotMillis
                .takeIf { it > 0L }
                ?: (VolumeAlarmManager.getPassedTodaySlotMillis(schedule.time, schedule.days) ?: System.currentTimeMillis())

            if (rawSlotMillis > 0L && schedule.lastExecutedSlotMillis == rawSlotMillis) {
                VolumeSchedulerStorage.appendLog(
                    context,
                    ExecutionLogEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        source = "RULE_SKIPPED",
                        ruleId = schedule.id,
                        ruleName = schedule.label.ifBlank { "Rule ${schedule.time}" },
                        scheduledTime = schedule.time,
                        stream = "none",
                        requestedPercentage = 0,
                        indexBefore = 0,
                        actualIndex = 0,
                        maxIndex = 0,
                        actualPercentage = 0,
                        success = true,
                        failureReason = "Duplicate alarm ignored: scheduled slot already executed.",
                        message = "Skipped duplicate broadcast for already-executed slot."
                    )
                )
                VolumeAlarmManager.scheduleSingle(context, schedule)
                notifyForegroundUi(context)
                completeAsync()
                return
            }

            val outcome = VolumeRuleExecutor.executeRule(
                context = context,
                schedule = schedule,
                source = "SCHEDULE",
                slotMillis = slotMillis
            )

            VolumeAlarmManager.scheduleSingle(context, outcome.updatedSchedule)
            notifyForegroundUi(context)
            completeAsync()
        } catch (ex: Exception) {
            VolumeSchedulerStorage.appendLog(
                context,
                ExecutionLogEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    source = "RULE_SKIPPED",
                    ruleId = intent.getStringExtra(VolumeAlarmManager.EXTRA_SCHEDULE_ID) ?: "",
                    ruleName = "Receiver Error",
                    scheduledTime = "",
                    stream = "none",
                    requestedPercentage = 0,
                    indexBefore = 0,
                    actualIndex = 0,
                    maxIndex = 0,
                    actualPercentage = 0,
                    success = false,
                    failureReason = "Native receiver failure: ${ex.message ?: "unexpected exception"}",
                    message = "Native alarm receiver failed: ${ex.message ?: "unknown error"}"
                )
            )
            completeAsync()
        }
    }

    private fun notifyForegroundUi(context: Context) {
        val localIntent = Intent(ACTION_INTERNAL_VOLUME_UPDATED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(localIntent)
    }
}
