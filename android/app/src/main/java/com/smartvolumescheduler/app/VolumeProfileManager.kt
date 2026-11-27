package com.smartvolumescheduler.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

data class ProfileOverlapReport(
    val hasAnyOverlap: Boolean,
    val hasAmbiguousOverlap: Boolean,
    val overlappingProfileIds: Set<String>,
    val ambiguousProfileIds: Set<String>,
    val warnings: List<String>
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("hasAnyOverlap", hasAnyOverlap)
            put("hasAmbiguousOverlap", hasAmbiguousOverlap)
            put("overlappingProfileIds", JSONArray(overlappingProfileIds.toList()))
            put("ambiguousProfileIds", JSONArray(ambiguousProfileIds.toList()))
            put("warnings", JSONArray(warnings))
        }
    }
}

data class ProfileResolution(
    val status: String, // active | fallback | none | conflict | paused
    val activeProfile: VolumeProfile?,
    val isFallback: Boolean,
    val conflictingProfiles: List<VolumeProfile>,
    val activeSlotMillis: Long,
    val message: String
) {
    fun toJson(): JSONObject {
        val conflictsArr = JSONArray()
        conflictingProfiles.forEach { conflictsArr.put(it.toJson()) }
        return JSONObject().apply {
            put("status", status)
            if (activeProfile != null) {
                put("activeProfile", activeProfile.toJson())
            } else {
                put("activeProfile", JSONObject.NULL)
            }
            put("isFallback", isFallback)
            put("conflictingProfiles", conflictsArr)
            put("activeSlotMillis", activeSlotMillis)
            put("message", message)
        }
    }
}

data class ProfileExecutionOutcome(
    val updatedProfile: VolumeProfile,
    val allSucceeded: Boolean,
    val streamResults: List<StreamExecutionDetail>,
    val aliasedNote: String,
    val summaryMessage: String
)

object VolumeProfileManager {

    fun parseHourMinute(time: String): Pair<Int, Int> {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 6
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return hour to minute
    }

    fun toMinuteOfDay(time: String): Int {
        val (h, m) = parseHourMinute(time)
        return h * 60 + m
    }

    fun isMinuteInRange(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean {
        return if (startMin <= endMin) {
            minuteOfDay in startMin..endMin
        } else {
            minuteOfDay >= startMin || minuteOfDay <= endMin
        }
    }

    fun doProfilesOverlap(a: VolumeProfile, b: VolumeProfile): Boolean {
        val aStart = toMinuteOfDay(a.startTime)
        val aEnd = toMinuteOfDay(a.endTime)
        val bStart = toMinuteOfDay(b.startTime)
        val bEnd = toMinuteOfDay(b.endTime)
        for (m in 0 until 1440) {
            if (isMinuteInRange(m, aStart, aEnd) && isMinuteInRange(m, bStart, bEnd)) {
                return true
            }
        }
        return false
    }

    fun detectOverlaps(profiles: List<VolumeProfile>): ProfileOverlapReport {
        val enabled = profiles.filter { it.enabled }
        val overlappingIds = linkedSetOf<String>()
        val ambiguousIds = linkedSetOf<String>()
        val warnings = mutableListOf<String>()

        for (i in enabled.indices) {
            for (j in (i + 1) until enabled.size) {
                val a = enabled[i]
                val b = enabled[j]
                if (doProfilesOverlap(a, b)) {
                    overlappingIds.add(a.id)
                    overlappingIds.add(b.id)
                    if (a.priority == b.priority) {
                        ambiguousIds.add(a.id)
                        ambiguousIds.add(b.id)
                        warnings.add(
                            "Overlap conflict: \"${a.name}\" (${a.startTime}–${a.endTime}) overlaps with \"${b.name}\" (${b.startTime}–${b.endTime}) at equal priority (${a.priority}). Adjust times or assign explicit priority."
                        )
                    } else {
                        val higher = if (a.priority > b.priority) a else b
                        val lower = if (a.priority > b.priority) b else a
                        warnings.add(
                            "Priority overlap: \"${higher.name}\" (Priority ${higher.priority}) takes precedence over \"${lower.name}\" (Priority ${lower.priority}) during shared hours."
                        )
                    }
                }
            }
        }

        return ProfileOverlapReport(
            hasAnyOverlap = overlappingIds.isNotEmpty(),
            hasAmbiguousOverlap = ambiguousIds.isNotEmpty(),
            overlappingProfileIds = overlappingIds,
            ambiguousProfileIds = ambiguousIds,
            warnings = warnings
        )
    }

    fun getActivePeriodStartMillis(
        profile: VolumeProfile,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val (startHour, startMinute) = parseHourMinute(profile.startTime)
        val startMin = startHour * 60 + startMinute
        val endMin = toMinuteOfDay(profile.endTime)

        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val slotCal = (cal.clone() as Calendar).apply {
            if (startMin > endMin && nowMin <= endMin) {
                add(Calendar.DAY_OF_YEAR, -1)
            }
            set(Calendar.HOUR_OF_DAY, startHour)
            set(Calendar.MINUTE, startMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return slotCal.timeInMillis
    }

    fun computeNextStartMillis(
        profile: VolumeProfile,
        afterMillis: Long = System.currentTimeMillis()
    ): Long {
        val (hour, minute) = parseHourMinute(profile.startTime)
        val base = Calendar.getInstance().apply { timeInMillis = afterMillis }
        for (dayOffset in 0..2) {
            val candidate = (base.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis > afterMillis + 500L) {
                return candidate.timeInMillis
            }
        }
        return afterMillis + 24 * 60 * 60 * 1000L
    }

    fun computeNextTransitionMillis(
        profiles: List<VolumeProfile>,
        afterMillis: Long = System.currentTimeMillis()
    ): Long? {
        val enabled = profiles.filter { it.enabled }
        if (enabled.isEmpty()) return null

        val boundaryMinutes = linkedSetOf<Int>()
        enabled.forEach { p ->
            val startMin = toMinuteOfDay(p.startTime)
            val endAfterMin = (toMinuteOfDay(p.endTime) + 1) % 1440
            boundaryMinutes.add(startMin)
            boundaryMinutes.add(endAfterMin)
        }

        val base = Calendar.getInstance().apply { timeInMillis = afterMillis }
        val candidates = mutableListOf<Long>()
        for (dayOffset in 0..2) {
            boundaryMinutes.forEach { minOfDay ->
                val hour = minOfDay / 60
                val minute = minOfDay % 60
                val candidate = (base.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (candidate.timeInMillis > afterMillis + 500L) {
                    candidates.add(candidate.timeInMillis)
                }
            }
        }
        return candidates.minOrNull()
    }

    private fun getMostRecentBoundaryMillis(
        profiles: List<VolumeProfile>,
        nowMillis: Long
    ): Long {
        val base = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val candidates = mutableListOf<Long>()
        profiles.filter { it.enabled }.forEach { p ->
            val endAfterMin = (toMinuteOfDay(p.endTime) + 1) % 1440
            val hour = endAfterMin / 60
            val minute = endAfterMin % 60
            for (dayOffset in -1..0) {
                val c = (base.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (c.timeInMillis <= nowMillis) {
                    candidates.add(c.timeInMillis)
                }
            }
        }
        return candidates.maxOrNull() ?: (base.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis)
    }

    fun resolveProfileAt(
        context: Context,
        nowMillis: Long = System.currentTimeMillis()
    ): ProfileResolution {
        val schedulerEnabled = VolumeSchedulerStorage.isSchedulerEnabled(context)
        if (!schedulerEnabled) {
            return ProfileResolution(
                status = "paused",
                activeProfile = null,
                isFallback = false,
                conflictingProfiles = emptyList(),
                activeSlotMillis = 0L,
                message = "Master scheduler is paused."
            )
        }

        val profiles = VolumeSchedulerStorage.getProfiles(context)
        val enabled = profiles.filter { it.enabled }
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val matching = enabled.filter {
            isMinuteInRange(nowMin, toMinuteOfDay(it.startTime), toMinuteOfDay(it.endTime))
        }

        if (matching.isNotEmpty()) {
            val maxPriority = matching.maxOf { it.priority }
            val topMatches = matching.filter { it.priority == maxPriority }
            if (topMatches.size > 1) {
                val names = topMatches.joinToString(" & ") { "\"${it.name}\"" }
                return ProfileResolution(
                    status = "conflict",
                    activeProfile = null,
                    isFallback = false,
                    conflictingProfiles = topMatches,
                    activeSlotMillis = 0L,
                    message = "Ambiguous overlap: $names match current time with equal priority ($maxPriority). Volume left unchanged."
                )
            }

            val winner = topMatches.first()
            val slotMillis = getActivePeriodStartMillis(winner, nowMillis)
            return ProfileResolution(
                status = "active",
                activeProfile = winner,
                isFallback = false,
                conflictingProfiles = emptyList(),
                activeSlotMillis = slotMillis,
                message = "${winner.name} (${winner.startTime}–${winner.endTime}) is active."
            )
        }

        val defaultId = VolumeSchedulerStorage.getDefaultProfileId(context)
        val fallback = defaultId?.let { id -> enabled.firstOrNull { it.id == id } }
        if (fallback != null) {
            val slotMillis = getMostRecentBoundaryMillis(enabled, nowMillis)
            return ProfileResolution(
                status = "fallback",
                activeProfile = fallback,
                isFallback = true,
                conflictingProfiles = emptyList(),
                activeSlotMillis = slotMillis,
                message = "No timed profile active; fallback default \"${fallback.name}\" is active."
            )
        }

        return ProfileResolution(
            status = "none",
            activeProfile = null,
            isFallback = false,
            conflictingProfiles = emptyList(),
            activeSlotMillis = 0L,
            message = "No profile active for the current time. System volume remains unchanged."
        )
    }

    fun executeProfile(
        context: Context,
        profile: VolumeProfile,
        source: String,
        scheduledTimeLabel: String,
        slotMillis: Long,
        isFallback: Boolean
    ): ProfileExecutionOutcome {
        val am = VolumeController.getAudioManager(context)
        val enabledEntries = AudioStreamType.entries.mapNotNull { st ->
            val cfg = profile.streams[st.key]
            if (cfg != null && cfg.enabled) st to cfg else null
        }

        val now = System.currentTimeMillis()
        if (enabledEntries.isEmpty()) {
            val reason = "No audio streams enabled in profile \"${profile.name}\"."
            VolumeSchedulerStorage.appendLog(
                context,
                ExecutionLogEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    source = source,
                    ruleId = profile.id,
                    ruleName = profile.name,
                    activeProfileName = profile.name,
                    scheduledTime = scheduledTimeLabel,
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
            val updated = profile.copy(
                lastActivatedAt = now,
                lastActivatedSlotMillis = if (!isFallback && slotMillis > 0L) slotMillis else profile.lastActivatedSlotMillis,
                lastExecutionSuccess = false,
                lastExecutionMessage = reason
            )
            VolumeSchedulerStorage.upsertProfile(context, updated)
            return ProfileExecutionOutcome(updated, false, emptyList(), "", reason)
        }

        val indexBeforeMap = linkedMapOf<AudioStreamType, Int>()
        enabledEntries.forEach { (st, _) ->
            indexBeforeMap[st] = try {
                am.getStreamVolume(st.androidStream)
            } catch (_: Exception) {
                0
            }
        }

        val applyResults = linkedMapOf<AudioStreamType, VolumeApplyResult>()
        enabledEntries.forEach { (st, cfg) ->
            applyResults[st] = VolumeController.applyVolume(
                context = context,
                streamKey = st.key,
                requestedPercentage = cfg.percentage,
                showSystemUi = false
            )
        }

        val details = mutableListOf<StreamExecutionDetail>()
        var allSucceeded = true
        val summaryParts = mutableListOf<String>()
        var aliasedNote = ""

        val ringResult = applyResults[AudioStreamType.RINGTONE]
        val notifResult = applyResults[AudioStreamType.NOTIFICATION]
        val ringCfg = profile.streams[AudioStreamType.RINGTONE.key]
        val notifCfg = profile.streams[AudioStreamType.NOTIFICATION.key]

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
                ruleId = profile.id,
                ruleName = profile.name,
                activeProfileName = profile.name,
                scheduledTime = scheduledTimeLabel,
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

        val updated = profile.copy(
            lastActivatedAt = now,
            lastActivatedSlotMillis = if (!isFallback && slotMillis > 0L) slotMillis else profile.lastActivatedSlotMillis,
            lastExecutionSuccess = allSucceeded,
            lastExecutionMessage = summaryMessage
        )
        VolumeSchedulerStorage.upsertProfile(context, updated)
        VolumeSchedulerStorage.setRuntimeActiveProfileId(context, profile.id)
        VolumeSchedulerStorage.setLastAppliedProfileKey(context, "${profile.id}:$slotMillis")

        return ProfileExecutionOutcome(
            updatedProfile = updated,
            allSucceeded = allSucceeded,
            streamResults = details,
            aliasedNote = aliasedNote,
            summaryMessage = summaryMessage
        )
    }
}
