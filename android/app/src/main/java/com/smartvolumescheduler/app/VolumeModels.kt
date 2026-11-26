package com.smartvolumescheduler.app

import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject

enum class AudioStreamType(
    val key: String,
    val label: String,
    val androidStream: Int
) {
    MEDIA("media", "Media", AudioManager.STREAM_MUSIC),
    RINGTONE("ringtone", "Ringtone", AudioManager.STREAM_RING),
    NOTIFICATION("notification", "Notification", AudioManager.STREAM_NOTIFICATION),
    ALARM("alarm", "Alarm", AudioManager.STREAM_ALARM),
    VOICE_CALL("voice_call", "Voice Call", AudioManager.STREAM_VOICE_CALL);

    companion object {
        fun fromKey(key: String?): AudioStreamType? {
            return entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
        }
    }
}

data class StreamRuleConfig(
    val enabled: Boolean,
    val percentage: Int
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("enabled", enabled)
            put("percentage", percentage.coerceIn(0, 100))
        }
    }

    companion object {
        fun fromJson(
            obj: JSONObject?,
            defaultEnabled: Boolean = false,
            defaultPct: Int = 70
        ): StreamRuleConfig {
            if (obj == null) {
                return StreamRuleConfig(defaultEnabled, defaultPct.coerceIn(0, 100))
            }
            return StreamRuleConfig(
                enabled = obj.optBoolean("enabled", defaultEnabled),
                percentage = obj.optInt("percentage", defaultPct).coerceIn(0, 100)
            )
        }
    }
}

data class VolumeSchedule(
    val id: String,
    val label: String,
    val time: String, // 24h HH:mm
    val streams: Map<String, StreamRuleConfig>,
    val days: List<Int>, // 0=Sun .. 6=Sat
    val enabled: Boolean,
    val nextTriggerAt: Long = 0L,
    val lastExecutedAt: Long = 0L,
    val lastExecutedSlotMillis: Long = 0L,
    val lastExecutionSuccess: Boolean = true,
    val lastExecutionMessage: String = ""
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("label", label)
        obj.put("time", time)

        val streamsObj = JSONObject()
        AudioStreamType.entries.forEach { st ->
            val cfg = streams[st.key] ?: StreamRuleConfig(false, 70)
            streamsObj.put(st.key, cfg.toJson())
        }
        obj.put("streams", streamsObj)

        val daysArray = JSONArray()
        days.forEach { daysArray.put(it) }
        obj.put("days", daysArray)
        obj.put("enabled", enabled)
        obj.put("nextTriggerAt", nextTriggerAt)
        obj.put("lastExecutedAt", lastExecutedAt)
        obj.put("lastExecutedSlotMillis", lastExecutedSlotMillis)
        obj.put("lastExecutionSuccess", lastExecutionSuccess)
        obj.put("lastExecutionMessage", lastExecutionMessage)
        return obj
    }

    companion object {
        fun defaultStreams(
            overrides: Map<String, StreamRuleConfig> = emptyMap()
        ): Map<String, StreamRuleConfig> {
            val result = linkedMapOf<String, StreamRuleConfig>()
            AudioStreamType.entries.forEach { st ->
                result[st.key] = overrides[st.key] ?: StreamRuleConfig(false, 70)
            }
            return result
        }

        fun fromJson(obj: JSONObject): VolumeSchedule {
            val daysList = mutableListOf<Int>()
            val daysArray = obj.optJSONArray("days")
            if (daysArray != null) {
                for (i in 0 until daysArray.length()) {
                    val d = daysArray.optInt(i, -1)
                    if (d in 0..6 && !daysList.contains(d)) {
                        daysList.add(d)
                    }
                }
            }
            if (daysList.isEmpty()) {
                daysList.addAll(listOf(0, 1, 2, 3, 4, 5, 6))
            }

            val streamsObj = obj.optJSONObject("streams")
            val parsedStreams = linkedMapOf<String, StreamRuleConfig>()
            if (streamsObj != null) {
                AudioStreamType.entries.forEach { st ->
                    parsedStreams[st.key] = StreamRuleConfig.fromJson(
                        streamsObj.optJSONObject(st.key),
                        defaultEnabled = false,
                        defaultPct = 70
                    )
                }
            } else {
                val legacyStream = obj.optString("stream", AudioStreamType.RINGTONE.key)
                val legacyPct = obj.optInt("percentage", 100).coerceIn(0, 100)
                AudioStreamType.entries.forEach { st ->
                    parsedStreams[st.key] = if (st.key.equals(legacyStream, ignoreCase = true)) {
                        StreamRuleConfig(true, legacyPct)
                    } else {
                        StreamRuleConfig(false, 70)
                    }
                }
            }

            return VolumeSchedule(
                id = obj.optString("id"),
                label = obj.optString("label", ""),
                time = obj.optString("time", "08:00"),
                streams = parsedStreams,
                days = daysList.sorted(),
                enabled = obj.optBoolean("enabled", true),
                nextTriggerAt = obj.optLong("nextTriggerAt", 0L),
                lastExecutedAt = obj.optLong("lastExecutedAt", 0L),
                lastExecutedSlotMillis = obj.optLong("lastExecutedSlotMillis", 0L),
                lastExecutionSuccess = obj.optBoolean("lastExecutionSuccess", true),
                lastExecutionMessage = obj.optString("lastExecutionMessage", "")
            )
        }
    }
}

data class VolumeApplyResult(
    val success: Boolean,
    val appliedExactTarget: Boolean,
    val stream: String,
    val requestedPercentage: Int,
    val indexBefore: Int,
    val targetIndex: Int,
    val actualIndex: Int,
    val actualPercentage: Int,
    val minIndex: Int,
    val maxIndex: Int,
    val dndBlocked: Boolean,
    val fixedVolume: Boolean,
    val streamUnavailable: Boolean,
    val failureReason: String,
    val message: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("success", success)
            put("appliedExactTarget", appliedExactTarget)
            put("stream", stream)
            put("requestedPercentage", requestedPercentage)
            put("indexBefore", indexBefore)
            put("targetIndex", targetIndex)
            put("actualIndex", actualIndex)
            put("actualPercentage", actualPercentage)
            put("minIndex", minIndex)
            put("maxIndex", maxIndex)
            put("dndBlocked", dndBlocked)
            put("fixedVolume", fixedVolume)
            put("streamUnavailable", streamUnavailable)
            put("failureReason", failureReason)
            put("message", message)
        }
    }
}

data class StreamExecutionDetail(
    val stream: String,
    val requestedPercentage: Int,
    val indexBefore: Int,
    val targetIndex: Int,
    val indexAfter: Int,
    val minIndex: Int,
    val maxIndex: Int,
    val actualPercentage: Int,
    val success: Boolean,
    val failureReason: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("stream", stream)
            put("requestedPercentage", requestedPercentage)
            put("indexBefore", indexBefore)
            put("targetIndex", targetIndex)
            put("indexAfter", indexAfter)
            put("minIndex", minIndex)
            put("maxIndex", maxIndex)
            put("actualPercentage", actualPercentage)
            put("success", success)
            put("failureReason", failureReason)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): StreamExecutionDetail {
            return StreamExecutionDetail(
                stream = obj.optString("stream", ""),
                requestedPercentage = obj.optInt("requestedPercentage", 0),
                indexBefore = obj.optInt("indexBefore", 0),
                targetIndex = obj.optInt("targetIndex", 0),
                indexAfter = obj.optInt("indexAfter", 0),
                minIndex = obj.optInt("minIndex", 0),
                maxIndex = obj.optInt("maxIndex", 0),
                actualPercentage = obj.optInt("actualPercentage", 0),
                success = obj.optBoolean("success", false),
                failureReason = obj.optString("failureReason", "")
            )
        }
    }
}

data class StreamTestResult(
    val stream: String,
    val requestedPercentage: Int,
    val indexBefore: Int,
    val targetIndex: Int,
    val actualIndex: Int,
    val minIndex: Int,
    val maxIndex: Int,
    val actualPercentage: Int,
    val volumeApplied: Boolean,
    val playbackStarted: Boolean,
    val streamVerified: Boolean,
    val callSessionActive: Boolean,
    val audioMode: String,
    val ringerMode: String,
    val dndBlocked: Boolean,
    val fixedVolume: Boolean,
    val failureReason: String,
    val source: String,
    val timestamp: Long,
    val message: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("stream", stream)
            put("requestedPercentage", requestedPercentage)
            put("indexBefore", indexBefore)
            put("targetIndex", targetIndex)
            put("actualIndex", actualIndex)
            put("minIndex", minIndex)
            put("maxIndex", maxIndex)
            put("actualPercentage", actualPercentage)
            put("volumeApplied", volumeApplied)
            put("playbackStarted", playbackStarted)
            put("streamVerified", streamVerified)
            put("callSessionActive", callSessionActive)
            put("audioMode", audioMode)
            put("ringerMode", ringerMode)
            put("dndBlocked", dndBlocked)
            put("fixedVolume", fixedVolume)
            put("failureReason", failureReason)
            put("source", source)
            put("timestamp", timestamp)
            put("message", message)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): StreamTestResult {
            return StreamTestResult(
                stream = obj.optString("stream", ""),
                requestedPercentage = obj.optInt("requestedPercentage", 0),
                indexBefore = obj.optInt("indexBefore", 0),
                targetIndex = obj.optInt("targetIndex", 0),
                actualIndex = obj.optInt("actualIndex", 0),
                minIndex = obj.optInt("minIndex", 0),
                maxIndex = obj.optInt("maxIndex", 0),
                actualPercentage = obj.optInt("actualPercentage", 0),
                volumeApplied = obj.optBoolean("volumeApplied", false),
                playbackStarted = obj.optBoolean("playbackStarted", false),
                streamVerified = obj.optBoolean("streamVerified", false),
                callSessionActive = obj.optBoolean("callSessionActive", false),
                audioMode = obj.optString("audioMode", "MODE_NORMAL"),
                ringerMode = obj.optString("ringerMode", "normal"),
                dndBlocked = obj.optBoolean("dndBlocked", false),
                fixedVolume = obj.optBoolean("fixedVolume", false),
                failureReason = obj.optString("failureReason", ""),
                source = obj.optString("source", "FOREGROUND_TEST"),
                timestamp = obj.optLong("timestamp", 0L),
                message = obj.optString("message", "")
            )
        }
    }
}

data class ExecutionLogEntry(
    val id: String,
    val timestamp: Long,
    val source: String, // SCHEDULE | IMMEDIATE_PAST_TIME | BACKGROUND_TEST_ALARM | FOREGROUND_TEST | MANUAL | BOOT_RESTORE | ALARM_CANCELED | RULE_SKIPPED
    val ruleId: String,
    val ruleName: String,
    val scheduledTime: String,
    val stream: String,
    val requestedPercentage: Int,
    val indexBefore: Int,
    val actualIndex: Int,
    val maxIndex: Int,
    val actualPercentage: Int,
    val success: Boolean,
    val failureReason: String,
    val streamsAliasedNote: String = "",
    val streamResults: List<StreamExecutionDetail> = emptyList(),
    val message: String
) {
    fun toJson(): JSONObject {
        val detailsArr = JSONArray()
        streamResults.forEach { detailsArr.put(it.toJson()) }
        return JSONObject().apply {
            put("id", id)
            put("timestamp", timestamp)
            put("source", source)
            put("ruleId", ruleId)
            put("scheduleId", ruleId)
            put("ruleName", ruleName)
            put("scheduledTime", scheduledTime)
            put("stream", stream)
            put("requestedPercentage", requestedPercentage)
            put("indexBefore", indexBefore)
            put("actualIndex", actualIndex)
            put("maxIndex", maxIndex)
            put("actualPercentage", actualPercentage)
            put("success", success)
            put("failureReason", failureReason)
            put("streamsAliasedNote", streamsAliasedNote)
            put("streamResults", detailsArr)
            put("message", message)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): ExecutionLogEntry {
            val details = mutableListOf<StreamExecutionDetail>()
            val arr = obj.optJSONArray("streamResults")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    details.add(StreamExecutionDetail.fromJson(item))
                }
            }
            return ExecutionLogEntry(
                id = obj.optString("id"),
                timestamp = obj.optLong("timestamp", 0L),
                source = obj.optString("source", "MANUAL"),
                ruleId = obj.optString("ruleId", obj.optString("scheduleId", "")),
                ruleName = obj.optString("ruleName", ""),
                scheduledTime = obj.optString("scheduledTime", ""),
                stream = obj.optString("stream", ""),
                requestedPercentage = obj.optInt("requestedPercentage", 0),
                indexBefore = obj.optInt("indexBefore", 0),
                actualIndex = obj.optInt("actualIndex", 0),
                maxIndex = obj.optInt("maxIndex", 0),
                actualPercentage = obj.optInt("actualPercentage", 0),
                success = obj.optBoolean("success", false),
                failureReason = obj.optString("failureReason", ""),
                streamsAliasedNote = obj.optString("streamsAliasedNote", ""),
                streamResults = details,
                message = obj.optString("message", "")
            )
        }
    }
}
