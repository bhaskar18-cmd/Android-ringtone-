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
    private const val KEY_STREAMS_ALIASED = "streams_aliased_detected"
    private const val KEY_STREAMS_ALIASED_NOTE = "streams_aliased_note"
    private const val KEY_PROFILES = "volume_profiles_json"
    private const val KEY_PROFILES_INITIALIZED = "profiles_initialized_v1"
    private const val KEY_DEFAULT_PROFILE_ID = "default_fallback_profile_id"
    private const val KEY_ACTIVE_PROFILE_ID = "runtime_active_profile_id"
    private const val KEY_LAST_APPLIED_PROFILE_KEY = "last_applied_profile_slot_key"
    private const val KEY_NEXT_PROFILE_TRANSITION_AT = "next_profile_transition_at"
    private const val KEY_LAST_BOOT_AT = "last_boot_restore_at"
    private const val KEY_LAST_BOOT_REASON = "last_boot_restore_reason"
    private const val KEY_LAST_BOOT_COUNT = "last_boot_restored_count"
    private const val MAX_LOGS = 50

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun ensureDefaults(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_INITIALIZED, false)) {
            saveSchedulesInternal(p, emptyList())
            p.edit()
                .putBoolean(KEY_SCHEDULER_ENABLED, true)
                .putBoolean(KEY_INITIALIZED, true)
                .commit()
        }

        if (!p.getBoolean(KEY_PROFILES_INITIALIZED, false)) {
            val dayProfile = VolumeProfile(
                id = "default-profile-day",
                name = "Day Profile",
                icon = "sun",
                startTime = "06:00",
                endTime = "20:00",
                streams = VolumeProfile.defaultStreams(
                    mapOf(
                        AudioStreamType.MEDIA.key to StreamRuleConfig(true, 70),
                        AudioStreamType.RINGTONE.key to StreamRuleConfig(true, 100),
                        AudioStreamType.NOTIFICATION.key to StreamRuleConfig(true, 100),
                        AudioStreamType.ALARM.key to StreamRuleConfig(true, 80),
                        AudioStreamType.VOICE_CALL.key to StreamRuleConfig(true, 100)
                    )
                ),
                enabled = true,
                priority = 0
            )
            val nightProfile = VolumeProfile(
                id = "default-profile-night",
                name = "Night Profile",
                icon = "moon",
                startTime = "20:01",
                endTime = "05:59",
                streams = VolumeProfile.defaultStreams(
                    mapOf(
                        AudioStreamType.MEDIA.key to StreamRuleConfig(true, 30),
                        AudioStreamType.RINGTONE.key to StreamRuleConfig(true, 50),
                        AudioStreamType.NOTIFICATION.key to StreamRuleConfig(true, 50),
                        AudioStreamType.ALARM.key to StreamRuleConfig(true, 50),
                        AudioStreamType.VOICE_CALL.key to StreamRuleConfig(true, 50)
                    )
                ),
                enabled = true,
                priority = 0
            )
            saveProfilesInternal(p, listOf(dayProfile, nightProfile))
            p.edit()
                .putBoolean(KEY_PROFILES_INITIALIZED, true)
                .commit()
        }
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
    fun getProfiles(context: Context): List<VolumeProfile> {
        ensureDefaults(context)
        val raw = prefs(context).getString(KEY_PROFILES, "[]") ?: "[]"
        val list = mutableListOf<VolumeProfile>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(VolumeProfile.fromJson(obj))
            }
        } catch (_: Exception) {
        }
        return list.sortedWith(compareBy<VolumeProfile> { it.startTime }.thenBy { it.name })
    }

    @Synchronized
    fun saveProfiles(context: Context, profiles: List<VolumeProfile>) {
        ensureDefaults(context)
        saveProfilesInternal(prefs(context), profiles)
    }

    private fun saveProfilesInternal(p: SharedPreferences, profiles: List<VolumeProfile>) {
        val arr = JSONArray()
        profiles.sortedWith(compareBy<VolumeProfile> { it.startTime }.thenBy { it.name })
            .forEach { arr.put(it.toJson()) }
        p.edit().putString(KEY_PROFILES, arr.toString()).commit()
    }

    @Synchronized
    fun upsertProfile(context: Context, profile: VolumeProfile): List<VolumeProfile> {
        val current = getProfiles(context).toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) {
            current[idx] = profile
        } else {
            current.add(profile)
        }
        saveProfiles(context, current)
        return getProfiles(context)
    }

    @Synchronized
    fun getProfileById(context: Context, id: String): VolumeProfile? {
        return getProfiles(context).firstOrNull { it.id == id }
    }

    @Synchronized
    fun deleteProfile(context: Context, id: String): Pair<Boolean, List<VolumeProfile>> {
        val current = getProfiles(context)
        val filtered = current.filterNot { it.id == id }
        val deleted = filtered.size != current.size
        saveProfiles(context, filtered)
        if (getDefaultProfileId(context) == id) {
            setDefaultProfileId(context, null)
        }
        return deleted to filtered
    }

    @Synchronized
    fun getDefaultProfileId(context: Context): String? {
        val v = prefs(context).getString(KEY_DEFAULT_PROFILE_ID, null)
        return v?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun setDefaultProfileId(context: Context, profileId: String?) {
        val editor = prefs(context).edit()
        if (profileId.isNullOrBlank()) {
            editor.remove(KEY_DEFAULT_PROFILE_ID)
        } else {
            editor.putString(KEY_DEFAULT_PROFILE_ID, profileId)
        }
        editor.commit()
    }

    @Synchronized
    fun getRuntimeActiveProfileId(context: Context): String? {
        val v = prefs(context).getString(KEY_ACTIVE_PROFILE_ID, null)
        return v?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun setRuntimeActiveProfileId(context: Context, profileId: String?) {
        val editor = prefs(context).edit()
        if (profileId.isNullOrBlank()) {
            editor.remove(KEY_ACTIVE_PROFILE_ID)
        } else {
            editor.putString(KEY_ACTIVE_PROFILE_ID, profileId)
        }
        editor.commit()
    }

    @Synchronized
    fun getLastAppliedProfileKey(context: Context): String {
        return prefs(context).getString(KEY_LAST_APPLIED_PROFILE_KEY, "") ?: ""
    }

    @Synchronized
    fun setLastAppliedProfileKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_LAST_APPLIED_PROFILE_KEY, key).commit()
    }

    @Synchronized
    fun getNextProfileTransitionAt(context: Context): Long {
        return prefs(context).getLong(KEY_NEXT_PROFILE_TRANSITION_AT, 0L)
    }

    @Synchronized
    fun setNextProfileTransitionAt(context: Context, epochMs: Long) {
        prefs(context).edit().putLong(KEY_NEXT_PROFILE_TRANSITION_AT, epochMs).commit()
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
    fun setStreamsAliased(context: Context, aliased: Boolean, note: String) {
        prefs(context).edit()
            .putBoolean(KEY_STREAMS_ALIASED, aliased)
            .putString(KEY_STREAMS_ALIASED_NOTE, note)
            .commit()
    }

    @Synchronized
    fun getStreamsAliasedInfo(context: Context): Pair<Boolean, String> {
        val p = prefs(context)
        return p.getBoolean(KEY_STREAMS_ALIASED, false) to
            (p.getString(KEY_STREAMS_ALIASED_NOTE, "") ?: "")
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
                ruleId = "",
                ruleName = "Boot & System Restore",
                activeProfileName = "",
                scheduledTime = "",
                stream = "system",
                requestedPercentage = 0,
                indexBefore = 0,
                actualIndex = 0,
                maxIndex = 0,
                actualPercentage = 0,
                success = true,
                failureReason = "",
                message = "Restored $restoredCount enabled schedule/profile transition(s) on $reason."
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
