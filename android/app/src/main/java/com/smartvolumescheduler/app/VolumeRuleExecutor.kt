package com.smartvolumescheduler.app

import android.content.Context
import java.util.UUID

data class RuleExecutionOutcome(
    val updatedSchedule: VolumeSchedule,
    val allSucceeded: Boolean,
    val streamResults: List<StreamExecutionDetail>,
    val aliasedNote: String,
    val summaryMessage: String
)

object VolumeRuleExecutor {

    fun executeRule(
        context: Context,
        schedule: VolumeSchedule,
        source: String,
        slotMillis: Long
    ): RuleExecutionOutcome {
        val am = VolumeController.getAudioManager(context)
        val enabledEntries = AudioStreamType.entries.mapNotNull { st ->
            val cfg = schedule.streams[st.key]
            if (cfg != null && cfg.enabled) st to cfg else null
        }

        val ruleDisplayName = schedule.label.ifBlank { "Rule ${schedule.time}" }
        val now = System.currentTimeMillis()

        if (enabledEntries.isEmpty()) {
            val reason = "No audio streams enabled in rule."
            VolumeSchedulerStorage.appendLog(
                context,
                ExecutionLogEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    source = source,
                    ruleId = schedule.id,
                    ruleName = ruleDisplayName,
                    scheduledTime = schedule.time,
                    stream = "none",
                    requestedPercentage = 0,
                    indexBefore = 0,
                    actualIndex = 0,
                    maxIndex = 0,
                    actualPercentage = 0,
                    success = false,
                    failureReason = reason,
                    message = reason
                )
            )
            val updated = schedule.copy(
                lastExecutedAt = now,
                lastExecutedSlotMillis = if (slotMillis > 0L) slotMillis else schedule.lastExecutedSlotMillis,
                lastExecutionSuccess = false,
                lastExecutionMessage = reason
            )
            VolumeSchedulerStorage.upsertSchedule(context, updated)
            return RuleExecutionOutcome(updated, false, emptyList(), "", reason)
        }

        // Snapshot indexBefore for all enabled streams prior to applying any changes
        val indexBeforeMap = linkedMapOf<AudioStreamType, Int>()
        enabledEntries.forEach { (st, _) ->
            val before = try {
                am.getStreamVolume(st.androidStream)
            } catch (_: Exception) {
                0
            }
            indexBeforeMap[st] = before
        }

        // Apply each enabled stream independently
        val applyResults = linkedMapOf<AudioStreamType, VolumeApplyResult>()
        enabledEntries.forEach { (st, cfg) ->
            applyResults[st] = VolumeController.applyVolume(
                context = context,
                streamKey = st.key,
                requestedPercentage = cfg.percentage,
                showSystemUi = false
            )
        }

        // Re-read final hardware indices after applying all enabled streams to detect coupling/aliasing
        val details = mutableListOf<StreamExecutionDetail>()
        var allSucceeded = true
        val summaryParts = mutableListOf<String>()
        var aliasedNote = ""

        val ringResult = applyResults[AudioStreamType.RINGTONE]
        val notifResult = applyResults[AudioStreamType.NOTIFICATION]
        val ringCfg = schedule.streams[AudioStreamType.RINGTONE.key]
        val notifCfg = schedule.streams[AudioStreamType.NOTIFICATION.key]

        enabledEntries.forEach { (st, cfg) ->
            val initRes = applyResults[st]!!
            val beforeIdx = indexBeforeMap[st] ?: initRes.indexBefore
            val minIdx = initRes.minIndex
            val maxIdx = initRes.maxIndex
            val finalIdx = try {
                if (!initRes.streamUnavailable) am.getStreamVolume(st.androidStream) else 0
            } catch (_: Exception) {
                initRes.actualIndex
            }
            val finalPct = if (maxIdx > 0) {
                VolumeController.computeAchievablePercentage(finalIdx, minIdx, maxIdx)
            } else {
                0
            }

            // Detect if Ringtone and Notification coupled after applying both with different targets
            val coupledOverwrite =
                (st == AudioStreamType.RINGTONE && ringResult != null && notifResult != null &&
                    ringResult.success && finalIdx != ringResult.targetIndex &&
                    ringCfg?.percentage != notifCfg?.percentage) ||
                    (st == AudioStreamType.NOTIFICATION && ringResult != null && notifResult != null &&
                        notifResult.success && finalIdx != notifResult.targetIndex &&
                        ringCfg?.percentage != notifCfg?.percentage)

            if (coupledOverwrite) {
                aliasedNote = "Android links Ringtone and Notification on this device; final hardware level ($finalIdx/$maxIdx, $finalPct%) reflects the coupled stream."
                VolumeSchedulerStorage.setStreamsAliased(context, true, aliasedNote)
            }

            val streamOk = initRes.success && (finalIdx == initRes.targetIndex)
            val failureReason = when {
                !initRes.success -> initRes.failureReason
                coupledOverwrite -> aliasedNote
                finalIdx != initRes.targetIndex ->
                    "Expected step ${initRes.targetIndex}/$maxIdx, actual hardware step is $finalIdx/$maxIdx ($finalPct%)."
                else -> ""
            }

            if (!streamOk) {
                allSucceeded = false
            }

            details.add(
                StreamExecutionDetail(
                    stream = st.key,
                    requestedPercentage = cfg.percentage,
                    indexBefore = beforeIdx,
                    targetIndex = initRes.targetIndex,
                    indexAfter = finalIdx,
                    minIndex = minIdx,
                    maxIndex = maxIdx,
                    actualPercentage = finalPct,
                    success = streamOk,
                    failureReason = failureReason
                )
            )

            summaryParts.add(
                if (streamOk) {
                    "${st.label}: $beforeIdx->$finalIdx/$maxIdx ($finalPct% for ${cfg.percentage}%)"
                } else {
                    "${st.label} failed: $failureReason"
                }
            )
        }

        val overallFailureReason = details
            .filter { !it.success && it.failureReason.isNotBlank() }
            .joinToString(" | ") { "${it.stream}: ${it.failureReason}" }

        val summaryMessage = summaryParts.joinToString(" · ")
        val primaryDetail = details.first()

        VolumeSchedulerStorage.appendLog(
            context,
            ExecutionLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                source = source,
                ruleId = schedule.id,
                ruleName = ruleDisplayName,
                scheduledTime = schedule.time,
                stream = details.joinToString(",") { it.stream },
                requestedPercentage = primaryDetail.requestedPercentage,
                indexBefore = primaryDetail.indexBefore,
                actualIndex = primaryDetail.indexAfter,
                maxIndex = primaryDetail.maxIndex,
                actualPercentage = primaryDetail.actualPercentage,
                success = allSucceeded,
                failureReason = overallFailureReason,
                streamsAliasedNote = aliasedNote,
                streamResults = details,
                message = summaryMessage
            )
        )

        val updated = schedule.copy(
            lastExecutedAt = now,
            lastExecutedSlotMillis = if (slotMillis > 0L) slotMillis else schedule.lastExecutedSlotMillis,
            lastExecutionSuccess = allSucceeded,
            lastExecutionMessage = summaryMessage
        )
        VolumeSchedulerStorage.upsertSchedule(context, updated)

        return RuleExecutionOutcome(
            updatedSchedule = updated,
            allSucceeded = allSucceeded,
            streamResults = details,
            aliasedNote = aliasedNote,
            summaryMessage = summaryMessage
        )
    }
}
