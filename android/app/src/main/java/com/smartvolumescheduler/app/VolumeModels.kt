package com.smartvolumescheduler.app

import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject

enum class AudioStreamType(
    val key: String,
    val label: String,
    val androidStream: Int
) {
    RINGTONE("ringtone", "Ringtone", AudioManager.STREAM_RING),
    NOTIFICATION("notification", "Notification", AudioManager.STREAM_NOTIFICATION),
    ALARM("alarm", "Alarm", AudioManager.STREAM_ALARM),
    MEDIA("media", "Media", AudioManager.STREAM_MUSIC),
    VOICE_CALL("voice_call", "Voice Call", AudioManager.STREAM_VOICE_CALL);

    companion object {
        fun fromKey(key: String?): AudioStreamType? {
            return entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
        }
    }
}

data class VolumeSchedule(
    val id: String,
    val label: String,
    val time: String, // 24h HH:mm
    val stream: String,
    val percentage: Int,
    val days: List<Int>, // 0=Sun .. 6=Sat
    val enabled: Boolean,
    val nextTriggerAt: Long = 0L,
    val lastExecutedAt: Long = 0L,
    val lastExecutionSuccess: Boolean = true,
    val lastExecutionMessage: String = ""
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("label", label)
        obj.put("time", time)
        obj.put("stream", stream)
        obj.put("percentage", percentage)
        val daysArray = JSONArray()
        days.forEach { daysArray.put(it) }
        obj.put("days", daysArray)
        obj.put("enabled", enabled)
        obj.put("nextTriggerAt", nextTriggerAt)
        obj.put("lastExecutedAt", lastExecutedAt)
        obj.put("lastExecutionSuccess", lastExecutionSuccess)
        obj.put("lastExecutionMessage", lastExecutionMessage)
        return obj
    }

    companion object {
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
            return VolumeSchedule(
                id = obj.optString("id"),
                label = obj.optString("label", ""),
                time = obj.optString("time", "08:00"),
                stream = obj.optString("stream", AudioStreamType.RINGTONE.key),
                percentage = obj.optInt("percentage", 100).coerceIn(0, 100),
                days = daysList.sorted(),
                enabled = obj.optBoolean("enabled", true),
                nextTriggerAt = obj.optLong("nextTriggerAt", 0L),
                lastExecutedAt = obj.optLong("lastExecutedAt", 0L),
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
    val targetIndex: Int,
    val actualIndex: Int,
    val actualPercentage: Int,
    val minIndex: Int,
    val maxIndex: Int,
    val dndBlocked: Boolean,
    val fixedVolume: Boolean,
    val message: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("success", success)
            put("appliedExactTarget", appliedExactTarget)
            put("stream", stream)
            put("requestedPercentage", requestedPercentage)
            put("targetIndex", targetIndex)
            put("actualIndex", actualIndex)
            put("actualPercentage", actualPercentage)
            put("minIndex", minIndex)
            put("maxIndex", maxIndex)
            put("dndBlocked", dndBlocked)
            put("fixedVolume", fixedVolume)
            put("message", message)
        }
    }
}

data class ExecutionLogEntry(
    val id: String,
    val timestamp: Long,
    val source: String, // SCHEDULE | TEST_ALARM | MANUAL | BOOT_RESTORE | SYSTEM_EVENT
    val scheduleId: String,
    val stream: String,
    val requestedPercentage: Int,
    val actualIndex: Int,
    val maxIndex: Int,
    val actualPercentage: Int,
    val success: Boolean,
    val message: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("timestamp", timestamp)
            put("source", source)
            put("scheduleId", scheduleId)
            put("stream", stream)
            put("requestedPercentage", requestedPercentage)
            put("actualIndex", actualIndex)
            put("maxIndex", maxIndex)
            put("actualPercentage", actualPercentage)
            put("success", success)
            put("message", message)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): ExecutionLogEntry {
            return ExecutionLogEntry(
                id = obj.optString("id"),
                timestamp = obj.optLong("timestamp", 0L),
                source = obj.optString("source", "MANUAL"),
                scheduleId = obj.optString("scheduleId", ""),
                stream = obj.optString("stream", ""),
                requestedPercentage = obj.optInt("requestedPercentage", 0),
                actualIndex = obj.optInt("actualIndex", 0),
                maxIndex = obj.optInt("maxIndex", 0),
                actualPercentage = obj.optInt("actualPercentage", 0),
                success = obj.optBoolean("success", false),
                message = obj.optString("message", "")
            )
        }
    }
}
