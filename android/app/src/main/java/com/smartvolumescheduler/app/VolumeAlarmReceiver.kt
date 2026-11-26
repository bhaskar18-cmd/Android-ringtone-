package com.smartvolumescheduler.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.util.UUID
import kotlin.math.roundToInt

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
                        scheduleId = "test-alarm",
                        stream = testResult.stream,
                        requestedPercentage = testResult.requestedPercentage,
                        actualIndex = testResult.actualIndex,
                        maxIndex = testResult.maxIndex,
                        actualPercentage = testResult.actualPercentage,
                        success = testResult.volumeApplied,
                        message = testResult.message
                    )
                )
                notifyForegroundUi(context)
                return
            }

            val scheduleId = intent.getStringExtra(VolumeAlarmManager.EXTRA_SCHEDULE_ID)
            if (scheduleId.isNullOrBlank()) {
                completeAsync()
                return
            }

            val schedulerEnabled = VolumeSchedulerStorage.isSchedulerEnabled(context)
            val schedule = VolumeSchedulerStorage.getScheduleById(context, scheduleId)
            if (schedule == null || !schedulerEnabled || !schedule.enabled) {
                completeAsync()
                return
            }

            val enabledEntries = AudioStreamType.entries.mapNotNull { st ->
                val cfg = schedule.streams[st.key]
                if (cfg != null && cfg.enabled) st to cfg else null
            }

            if (enabledEntries.isEmpty()) {
                completeAsync()
                return
            }

            val initialResults = linkedMapOf<AudioStreamType, VolumeApplyResult>()
            enabledEntries.forEach { (st, cfg) ->
                initialResults[st] = VolumeController.applyVolume(
                    context = context,
                    streamKey = st.key,
                    requestedPercentage = cfg.percentage,
                    showSystemUi = false
                )
            }

            // Re-read final hardware state for every enabled stream so linked/clamped hardware levels are reported accurately
            val am = VolumeController.getAudioManager(context)
            val now = System.currentTimeMillis()
            var allSucceeded = true
            val summaryParts = mutableListOf<String>()

            enabledEntries.forEach { (st, cfg) ->
                val initRes = initialResults[st]!!
                val finalIndex = am.getStreamVolume(st.androidStream)
                val finalMax = initRes.maxIndex.coerceAtLeast(1)
                val finalPct = ((finalIndex.toDouble() / finalMax.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                val streamOk = initRes.success && (finalIndex == initRes.targetIndex)
                if (!streamOk) {
                    allSucceeded = false
                }

                val streamMessage = if (streamOk) {
                    "${st.label} $finalIndex/$finalMax ($finalPct% for ${cfg.percentage}%)"
                } else {
                    "${st.label} failed (${initRes.message})"
                }
                summaryParts.add(streamMessage)

                VolumeSchedulerStorage.appendLog(
                    context,
                    ExecutionLogEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = now,
                        source = "SCHEDULE",
                        scheduleId = schedule.id,
                        stream = st.key,
                        requestedPercentage = cfg.percentage,
                        actualIndex = finalIndex,
                        maxIndex = finalMax,
                        actualPercentage = finalPct,
                        success = streamOk,
                        message = if (streamOk) {
                            "Scheduled ${st.label}: set $finalIndex/$finalMax ($finalPct% achieved for ${cfg.percentage}% requested)."
                        } else {
                            initRes.message
                        }
                    )
                )
            }

            val updatedSchedule = schedule.copy(
                lastExecutedAt = now,
                lastExecutionSuccess = allSucceeded,
                lastExecutionMessage = summaryParts.joinToString(" · ")
            )

            VolumeSchedulerStorage.upsertSchedule(context, updatedSchedule)
            VolumeAlarmManager.scheduleSingle(context, updatedSchedule)
            notifyForegroundUi(context)
            completeAsync()
        } catch (_: Exception) {
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
