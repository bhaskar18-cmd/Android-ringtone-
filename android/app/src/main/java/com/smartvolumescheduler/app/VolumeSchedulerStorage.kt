package com.smartvolumescheduler.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object VolumeSchedulerStorage {
    private const val PREFS_NAME = "smart_volume_scheduler_prefs"
    private const val KEY_SCHEDULES = "schedules_json"
    private const val KEY_SCHEDULER_ENABLED = "scheduler_enabled"
    private const val KEY_INITIALIZED = "defaults_initialized_v1"
    private const val KEY_LOGS = "execution_logs_json"
    private const val KEY_LAST_TEST_RESULT = "last_test_alarm_result_json"
    private const val KEY_LAST_BOOT_AT = "last_boot_restore_at"
    private const val KEY_LAST_BOOT_REASON = "last_boot_restore_reason"
    private const val KEY_LAST_BOOT_COUNT = "last_boot_restored_count"
    private const val MAX_LOGS = 40

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun ensureDefaults(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_INITIALIZED, false)) {
            return
        }

        val allDays = listOf(0, 1, 2, 3, 4, 5, 6)
        val daySchedule = VolumeSchedule(
            id = "default-day-0800",
            label = "Day Mode",
            time = "08:00",
            streams = VolumeSchedule.defaultStreams(
                mapOf(
                    AudioStreamType.RINGTONE.key to StreamRuleConfig(true, 100),
                    AudioStreamType.NOTIFICATION.key to StreamRuleConfig(true, 100)
                )
            ),
            days = allDays,
            enabled = true
        )
        val nightSchedule = VolumeSchedule(
            id = "default-night-2000",
            label = "Night Mode",
            time = "20:00",
            streams = VolumeSchedule.defaultStreams(
                mapOf(
                    AudioStreamType.RINGTONE.key to StreamRuleConfig(true, 50),
                    AudioStreamType.NOTIFICATION.key to StreamRuleConfig(true, 50)
                )
            ),
            days = allDays,
            enabled = true
        )

        saveSchedulesInternal(p, listOf(daySchedule, nightSchedule))
        p.edit()
            .putBoolean(KEY_SCHEDULER_ENABLED, true)
            .putBoolean(KEY_INITIALIZED, true)
            .commit()
    }

    @Synchronized
    fun isSchedulerEnabled(context: Context): Boolean {
        ensureDefaults(context)
        return prefs(context).getBoolean(KEY_SCHEDULER_ENABLED, true)
    }

    @Synchronized
    fun setSchedulerEnabled(context: Context, enabled: Boolean) {
        ensureDefaults(context)
        prefs(context).edit().putBoolean(KEY_SCHEDULER_ENABLED, enabled).commit()
    }

    @Synchronized
    fun getSchedules(context: Context): List<VolumeSchedule> {
        ensureDefaults(context)
        val raw = prefs(context).getString(KEY_SCHEDULES, "[]") ?: "[]"
        val list = mutableListOf<VolumeSchedule>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(VolumeSchedule.fromJson(obj))
            }
        } catch (_: Exception) {
        }
        return list.sortedBy { it.time }
    }

    @Synchronized
    fun saveSchedules(context: Context, schedules: List<VolumeSchedule>) {
        ensureDefaults(context)
        saveSchedulesInternal(prefs(context), schedules)
    }

    private fun saveSchedulesInternal(p: SharedPreferences, schedules: List<VolumeSchedule>) {
        val arr = JSONArray()
        schedules.sortedBy { it.time }.forEach { arr.put(it.toJson()) }
        p.edit().putString(KEY_SCHEDULES, arr.toString()).commit()
    }

    @Synchronized
    fun upsertSchedule(context: Context, schedule: VolumeSchedule): List<VolumeSchedule> {
        val current = getSchedules(context).toMutableList()
        val idx = current.indexOfFirst { it.id == schedule.id }
        if (idx >= 0) {
            current[idx] = schedule
        } else {
            current.add(schedule)
        }
        saveSchedules(context, current)
        return getSchedules(context)
    }

    @Synchronized
    fun getScheduleById(context: Context, id: String): VolumeSchedule? {
        return getSchedules(context).firstOrNull { it.id == id }
    }

    @Synchronized
    fun deleteSchedule(context: Context, id: String): Pair<Boolean, List<VolumeSchedule>> {
        val current = getSchedules(context)
        val filtered = current.filterNot { it.id == id }
        val deleted = filtered.size != current.size
        saveSchedules(context, filtered)
        return deleted to filtered
    }

    @Synchronized
    fun appendLog(context: Context, entry: ExecutionLogEntry) {
        val p = prefs(context)
        val existing = getLogs(context).toMutableList()
        existing.add(0, entry)
        val trimmed = existing.take(MAX_LOGS)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        p.edit().putString(KEY_LOGS, arr.toString()).commit()
    }

    @Synchronized
    fun getLogs(context: Context): List<ExecutionLogEntry> {
        val raw = prefs(context).getString(KEY_LOGS, "[]") ?: "[]"
        val list = mutableListOf<ExecutionLogEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(ExecutionLogEntry.fromJson(obj))
            }
        } catch (_: Exception) {
        }
        return list
    }

    @Synchronized
    fun saveLastTestAlarmResult(context: Context, result: StreamTestResult) {
        prefs(context).edit().putString(KEY_LAST_TEST_RESULT, result.toJson().toString()).commit()
    }

    @Synchronized
    fun getLastTestAlarmResult(context: Context): StreamTestResult? {
        val raw = prefs(context).getString(KEY_LAST_TEST_RESULT, null) ?: return null
        return try {
            StreamTestResult.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun recordBootRestore(context: Context, reason: String, restoredCount: Int) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_LAST_BOOT_AT, now)
            .putString(KEY_LAST_BOOT_REASON, reason)
            .putInt(KEY_LAST_BOOT_COUNT, restoredCount)
            .commit()

        appendLog(
            context,
            ExecutionLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                source = "BOOT_RESTORE",
                scheduleId = "",
                stream = "system",
                requestedPercentage = 0,
                actualIndex = 0,
                maxIndex = 0,
                actualPercentage = 0,
                success = true,
                message = "Restored $restoredCount enabled schedule(s) on $reason."
            )
        )
    }

    @Synchronized
    fun getBootRestoreInfo(context: Context): JSONObject {
        val p = prefs(context)
        return JSONObject().apply {
            put("lastBootRestoreAt", p.getLong(KEY_LAST_BOOT_AT, 0L))
            put("lastBootRestoreReason", p.getString(KEY_LAST_BOOT_REASON, ""))
            put("lastBootRestoredCount", p.getInt(KEY_LAST_BOOT_COUNT, 0))
        }
    }
}
