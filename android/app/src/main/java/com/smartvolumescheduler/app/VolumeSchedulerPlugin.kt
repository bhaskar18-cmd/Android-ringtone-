package com.smartvolumescheduler.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.json.JSONObject
import java.util.UUID

@CapacitorPlugin(
    name = "VolumeSchedulerPlugin",
    permissions = [
        Permission(
            strings = [Manifest.permission.POST_NOTIFICATIONS],
            alias = "notifications"
        )
    ]
)
class VolumeSchedulerPlugin : Plugin() {

    private var internalReceiver: BroadcastReceiver? = null

    override fun load() {
        super.load()
        VolumeSchedulerStorage.ensureDefaults(context)
        VolumeAlarmManager.rescheduleAll(context)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                notifyListeners("volumeStateChanged", buildVolumeSettingsObject())
            }
        }
        internalReceiver = receiver
        val filter = IntentFilter(VolumeAlarmReceiver.ACTION_INTERNAL_VOLUME_UPDATED)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun handleOnDestroy() {
        internalReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        internalReceiver = null
        super.handleOnDestroy()
    }

    @PluginMethod
    fun getVolumeSettings(call: PluginCall) {
        call.resolve(buildVolumeSettingsObject())
    }

    @PluginMethod
    fun setVolume(call: PluginCall) {
        val stream = call.getString("stream")
        val percentage = call.getInt("percentage")
        if (stream.isNullOrBlank() || percentage == null) {
            call.reject("Missing required parameters: stream and percentage.")
            return
        }

        val showUi = call.getBoolean("showUi", false) ?: false
        val result = VolumeController.applyVolume(context, stream, percentage, showUi)

        VolumeSchedulerStorage.appendLog(
            context,
            ExecutionLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                source = "MANUAL",
                ruleId = "",
                ruleName = "Manual Control",
                scheduledTime = "",
                stream = result.stream,
                requestedPercentage = result.requestedPercentage,
                indexBefore = result.indexBefore,
                actualIndex = result.actualIndex,
                maxIndex = result.maxIndex,
                actualPercentage = result.actualPercentage,
                success = result.success,
                failureReason = result.failureReason,
                message = result.message
            )
        )

        call.resolve(JSObject.fromJSONObject(result.toJson()))
    }

    @PluginMethod
    fun scheduleVolumeChange(call: PluginCall) {
        val time = call.getString("time")
        if (time.isNullOrBlank()) {
            call.reject("Missing required parameter: time.")
            return
        }

        val id = call.getString("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val existing = VolumeSchedulerStorage.getScheduleById(context, id)
        val label = call.getString("label") ?: existing?.label ?: ""
        val enabled = call.getBoolean("enabled") ?: existing?.enabled ?: true

        val parsedStreams = linkedMapOf<String, StreamRuleConfig>()
        val streamsObj = call.getObject("streams")
        if (streamsObj != null) {
            AudioStreamType.entries.forEach { st ->
                val cfgObj = streamsObj.optJSONObject(st.key)
                parsedStreams[st.key] = StreamRuleConfig.fromJson(
                    cfgObj,
                    defaultEnabled = false,
                    defaultPct = 70
                )
            }
        } else {
            val singleStream = call.getString("stream")
            val singlePct = call.getInt("percentage")
            if (singleStream.isNullOrBlank() || singlePct == null) {
                call.reject("Missing required streams configuration.")
                return
            }
            if (AudioStreamType.fromKey(singleStream) == null) {
                call.reject("Unsupported audio stream: $singleStream")
                return
            }
            AudioStreamType.entries.forEach { st ->
                parsedStreams[st.key] = if (st.key.equals(singleStream, ignoreCase = true)) {
                    StreamRuleConfig(true, singlePct.coerceIn(0, 100))
                } else {
                    StreamRuleConfig(false, 70)
                }
            }
        }

        if (parsedStreams.values.none { it.enabled }) {
            call.reject("Enable at least one audio stream in the rule.")
            return
        }

        val daysArr = call.getArray("days")
        val days = mutableListOf<Int>()
        if (daysArr != null) {
            for (i in 0 until daysArr.length()) {
                val d = daysArr.optInt(i, -1)
                if (d in 0..6 && !days.contains(d)) {
                    days.add(d)
                }
            }
        }
        if (days.isEmpty()) {
            days.addAll(listOf(0, 1, 2, 3, 4, 5, 6))
        }
        val sortedDays = days.sorted()

        val configChanged = existing == null ||
            existing.time != time.trim() ||
            existing.days != sortedDays ||
            existing.streams != parsedStreams

        val schedule = VolumeSchedule(
            id = id,
            label = label.trim(),
            time = time.trim(),
            streams = parsedStreams,
            days = sortedDays,
            enabled = enabled,
            lastExecutedAt = existing?.lastExecutedAt ?: 0L,
            lastExecutedSlotMillis = if (existing != null && existing.time == time.trim() && existing.days == sortedDays) {
                existing.lastExecutedSlotMillis
            } else {
                0L
            },
            lastExecutionSuccess = existing?.lastExecutionSuccess ?: true,
            lastExecutionMessage = existing?.lastExecutionMessage ?: ""
        )

        VolumeSchedulerStorage.upsertSchedule(context, schedule)
        val dispatch = VolumeAlarmManager.applyPastTimeIfNeededAndSchedule(
            context = context,
            schedule = schedule,
            forceImmediateIfPassed = configChanged
        )
        val saved = VolumeSchedulerStorage.getScheduleById(context, id) ?: schedule

        val res = JSObject().apply {
            put("success", true)
            put("schedule", JSObject.fromJSONObject(saved.toJson()))
            put("scheduled", dispatch.scheduled)
            put("exact", dispatch.exact)
            put("mode", dispatch.mode)
            put("executedImmediately", dispatch.executedImmediately)
            put("message", dispatch.message)
        }
        call.resolve(res)
    }

    @PluginMethod
    fun getScheduledChanges(call: PluginCall) {
        val schedulerEnabled = VolumeSchedulerStorage.isSchedulerEnabled(context)
        val schedules = VolumeSchedulerStorage.getSchedules(context)
        val arr = JSArray()
        schedules.forEach { arr.put(it.toJson()) }

        val next = findNextScheduledChange(schedules, schedulerEnabled)
        val res = JSObject().apply {
            put("schedulerEnabled", schedulerEnabled)
            put("schedules", arr)
            if (next != null) {
                put("nextScheduledChange", JSObject.fromJSONObject(next.toJson()))
            } else {
                put("nextScheduledChange", JSONObject.NULL)
            }
        }
        call.resolve(res)
    }

    @PluginMethod
    fun deleteScheduledChange(call: PluginCall) {
        val id = call.getString("id")
        if (id.isNullOrBlank()) {
            call.reject("Missing required parameter: id.")
            return
        }
        val existing = VolumeSchedulerStorage.getScheduleById(context, id)
        VolumeAlarmManager.cancelSchedule(context, id)
        val (deleted, remaining) = VolumeSchedulerStorage.deleteSchedule(context, id)
        if (deleted && existing != null) {
            VolumeSchedulerStorage.appendLog(
                context,
                ExecutionLogEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    source = "ALARM_CANCELED",
                    ruleId = existing.id,
                    ruleName = existing.label.ifBlank { "Rule ${existing.time}" },
                    scheduledTime = existing.time,
                    stream = "system",
                    requestedPercentage = 0,
                    indexBefore = 0,
                    actualIndex = 0,
                    maxIndex = 0,
                    actualPercentage = 0,
                    success = true,
                    failureReason = "",
                    message = "Rule deleted and native AlarmManager alarm canceled."
                )
            )
        }
        val arr = JSArray()
        remaining.forEach { arr.put(it.toJson()) }

        call.resolve(JSObject().apply {
            put("success", deleted)
            put("deletedId", id)
            put("schedules", arr)
        })
    }

    @PluginMethod
    fun toggleScheduleEnabled(call: PluginCall) {
        val id = call.getString("id")
        val enabled = call.getBoolean("enabled")
        if (id.isNullOrBlank() || enabled == null) {
            call.reject("Missing required parameters: id, enabled.")
            return
        }
        val existing = VolumeSchedulerStorage.getScheduleById(context, id)
        if (existing == null) {
            call.reject("Schedule not found: $id")
            return
        }

        val updated = existing.copy(enabled = enabled)
        VolumeSchedulerStorage.upsertSchedule(context, updated)

        val dispatch = if (enabled) {
            VolumeAlarmManager.applyPastTimeIfNeededAndSchedule(
                context = context,
                schedule = updated,
                forceImmediateIfPassed = false
            )
        } else {
            VolumeAlarmManager.cancelSchedule(context, updated.id)
            val disabledSaved = updated.copy(nextTriggerAt = 0L)
            VolumeSchedulerStorage.upsertSchedule(context, disabledSaved)
            VolumeSchedulerStorage.appendLog(
                context,
                ExecutionLogEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    source = "ALARM_CANCELED",
                    ruleId = updated.id,
                    ruleName = updated.label.ifBlank { "Rule ${updated.time}" },
                    scheduledTime = updated.time,
                    stream = "system",
                    requestedPercentage = 0,
                    indexBefore = 0,
                    actualIndex = 0,
                    maxIndex = 0,
                    actualPercentage = 0,
                    success = true,
                    failureReason = "",
                    message = "Rule disabled and native AlarmManager alarm canceled."
                )
            )
            AlarmDispatchResult(
                scheduled = false,
                exact = false,
                nextTriggerAt = 0L,
                mode = "disabled",
                message = "Rule disabled and pending alarm canceled."
            )
        }

        val saved = VolumeSchedulerStorage.getScheduleById(context, id) ?: updated
        call.resolve(JSObject().apply {
            put("success", true)
            put("schedule", JSObject.fromJSONObject(saved.toJson()))
            put("mode", dispatch.mode)
            put("executedImmediately", dispatch.executedImmediately)
            put("message", dispatch.message)
        })
    }

    @PluginMethod
    fun getVolumeProfiles(call: PluginCall) {
        val profiles = VolumeSchedulerStorage.getProfiles(context)
        val overlap = VolumeProfileManager.detectOverlaps(profiles)
        val resolution = VolumeProfileManager.resolveProfileAt(context)
        val defaultProfileId = VolumeSchedulerStorage.getDefaultProfileId(context)
        val nextTransitionAt = VolumeSchedulerStorage.getNextProfileTransitionAt(context)

        val arr = JSArray()
        profiles.forEach { arr.put(it.toJson()) }

        call.resolve(JSObject().apply {
            put("schedulerEnabled", VolumeSchedulerStorage.isSchedulerEnabled(context))
            put("profiles", arr)
            if (defaultProfileId != null) {
                put("defaultProfileId", defaultProfileId)
            } else {
                put("defaultProfileId", JSONObject.NULL)
            }
            put("profileOverlap", JSObject.fromJSONObject(overlap.toJson()))
            put("activeProfileResolution", JSObject.fromJSONObject(resolution.toJson()))
            put("nextProfileTransitionAt", nextTransitionAt)
        })
    }

    @PluginMethod
    fun saveVolumeProfile(call: PluginCall) {
        val name = call.getString("name")?.trim()
        val startTime = call.getString("startTime")?.trim()
        val endTime = call.getString("endTime")?.trim()
        if (name.isNullOrBlank() || startTime.isNullOrBlank() || endTime.isNullOrBlank()) {
            call.reject("Missing required profile parameters: name, startTime, endTime.")
            return
        }
        if (startTime == endTime) {
            call.reject("Start time and end time cannot be identical.")
            return
        }

        val id = call.getString("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val existing = VolumeSchedulerStorage.getProfileById(context, id)
        val icon = call.getString("icon")?.trim()?.takeIf { it.isNotBlank() } ?: existing?.icon ?: "sun"
        val enabled = call.getBoolean("enabled") ?: existing?.enabled ?: true
        val priority = call.getInt("priority") ?: existing?.priority ?: 0

        val streamsObj = call.getObject("streams")
        val parsedStreams = linkedMapOf<String, StreamRuleConfig>()
        AudioStreamType.entries.forEach { st ->
            parsedStreams[st.key] = StreamRuleConfig.fromJson(
                streamsObj?.optJSONObject(st.key),
                defaultEnabled = true,
                defaultPct = 70
            )
        }

        if (parsedStreams.values.none { it.enabled }) {
            call.reject("Enable at least one audio stream in the profile.")
            return
        }

        val configChanged = existing == null ||
            existing.startTime != startTime ||
            existing.endTime != endTime ||
            existing.streams != parsedStreams ||
            existing.priority != priority

        val profile = VolumeProfile(
            id = id,
            name = name,
            icon = icon,
            startTime = startTime,
            endTime = endTime,
            streams = parsedStreams,
            enabled = enabled,
            priority = priority,
            lastActivatedAt = existing?.lastActivatedAt ?: 0L,
            lastActivatedSlotMillis = if (existing != null && existing.startTime == startTime && existing.endTime == endTime) {
                existing.lastActivatedSlotMillis
            } else {
                0L
            },
            lastExecutionSuccess = existing?.lastExecutionSuccess ?: true,
            lastExecutionMessage = existing?.lastExecutionMessage ?: ""
        )

        VolumeSchedulerStorage.upsertProfile(context, profile)
        val recalc = VolumeAlarmManager.recalculateProfilesAndSchedule(
            context = context,
            activateIfCoversNow = true,
            forceApplyActiveIfChanged = configChanged,
            source = "IMMEDIATE_PROFILE"
        )
        val saved = VolumeSchedulerStorage.getProfileById(context, id) ?: profile
        val msg = buildProfileActionMessage("Saved \"${saved.name}\"", recalc)

        call.resolve(JSObject().apply {
            put("success", true)
            put("profile", JSObject.fromJSONObject(saved.toJson()))
            put("executedImmediately", recalc.executedImmediately)
            put("mode", recalc.dispatch.mode)
            put("profileOverlap", JSObject.fromJSONObject(recalc.overlapReport.toJson()))
            put("activeProfileResolution", JSObject.fromJSONObject(recalc.resolution.toJson()))
            put("nextProfileTransitionAt", VolumeSchedulerStorage.getNextProfileTransitionAt(context))
            put("message", msg)
        })
    }

    @PluginMethod
    fun deleteVolumeProfile(call: PluginCall) {
        val id = call.getString("id")
        if (id.isNullOrBlank()) {
            call.reject("Missing required parameter: id.")
            return
        }
        val existing = VolumeSchedulerStorage.getProfileById(context, id)
        val (deleted, _) = VolumeSchedulerStorage.deleteProfile(context, id)
        if (deleted && existing != null) {
            VolumeSchedulerStorage.appendLog(
                context,
                ExecutionLogEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    source = "ALARM_CANCELED",
                    ruleId = existing.id,
                    ruleName = existing.name,
                    activeProfileName = "",
                    scheduledTime = "${existing.startTime}-${existing.endTime}",
                    stream = "system",
                    requestedPercentage = 0,
                    indexBefore = 0,
                    actualIndex = 0,
                    maxIndex = 0,
                    actualPercentage = 0,
                    success = true,
                    failureReason = "",
                    message = "Profile \"${existing.name}\" deleted and transitions recalculated."
                )
            )
        }

        val recalc = VolumeAlarmManager.recalculateProfilesAndSchedule(
            context = context,
            activateIfCoversNow = true,
            forceApplyActiveIfChanged = false,
            source = "PROFILE_SCHEDULE"
        )

        val remaining = VolumeSchedulerStorage.getProfiles(context)
        val arr = JSArray()
        remaining.forEach { arr.put(it.toJson()) }

        call.resolve(JSObject().apply {
            put("success", deleted)
            put("deletedId", id)
            put("profiles", arr)
            put("profileOverlap", JSObject.fromJSONObject(recalc.overlapReport.toJson()))
            put("activeProfileResolution", JSObject.fromJSONObject(recalc.resolution.toJson()))
            put("message", if (deleted) "Deleted \"${existing?.name ?: id}\". ${recalc.resolution.message}" else "Profile not found.")
        })
    }

    @PluginMethod
    fun toggleProfileEnabled(call: PluginCall) {
        val id = call.getString("id")
        val enabled = call.getBoolean("enabled")
        if (id.isNullOrBlank() || enabled == null) {
            call.reject("Missing required parameters: id, enabled.")
            return
        }
        val existing = VolumeSchedulerStorage.getProfileById(context, id)
        if (existing == null) {
            call.reject("Profile not found: $id")
            return
        }

        val updated = existing.copy(enabled = enabled)
        VolumeSchedulerStorage.upsertProfile(context, updated)

        if (!enabled) {
            VolumeSchedulerStorage.appendLog(
                context,
                ExecutionLogEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    source = "ALARM_CANCELED",
                    ruleId = updated.id,
                    ruleName = updated.name,
                    activeProfileName = "",
                    scheduledTime = "${updated.startTime}-${updated.endTime}",
                    stream = "system",
                    requestedPercentage = 0,
                    indexBefore = 0,
                    actualIndex = 0,
                    maxIndex = 0,
                    actualPercentage = 0,
                    success = true,
                    failureReason = "",
                    message = "Profile \"${updated.name}\" disabled."
                )
            )
        }

        val recalc = VolumeAlarmManager.recalculateProfilesAndSchedule(
            context = context,
            activateIfCoversNow = true,
            forceApplyActiveIfChanged = false,
            source = "IMMEDIATE_PROFILE"
        )
        val saved = VolumeSchedulerStorage.getProfileById(context, id) ?: updated
        val actionPrefix = if (enabled) "Enabled \"${saved.name}\"" else "Disabled \"${saved.name}\""
        val msg = buildProfileActionMessage(actionPrefix, recalc)

        call.resolve(JSObject().apply {
            put("success", true)
            put("profile", JSObject.fromJSONObject(saved.toJson()))
            put("executedImmediately", recalc.executedImmediately)
            put("mode", recalc.dispatch.mode)
            put("profileOverlap", JSObject.fromJSONObject(recalc.overlapReport.toJson()))
            put("activeProfileResolution", JSObject.fromJSONObject(recalc.resolution.toJson()))
            put("message", msg)
        })
    }

    @PluginMethod
    fun setDefaultProfile(call: PluginCall) {
        val profileId = call.getString("profileId")?.takeIf { it.isNotBlank() }
        if (profileId != null && VolumeSchedulerStorage.getProfileById(context, profileId) == null) {
            call.reject("Profile not found: $profileId")
            return
        }
        VolumeSchedulerStorage.setDefaultProfileId(context, profileId)
        val recalc = VolumeAlarmManager.recalculateProfilesAndSchedule(
            context = context,
            activateIfCoversNow = true,
            forceApplyActiveIfChanged = false,
            source = "PROFILE_FALLBACK"
        )
        val label = profileId?.let { VolumeSchedulerStorage.getProfileById(context, it)?.name }
        call.resolve(JSObject().apply {
            put("success", true)
            if (profileId != null) {
                put("defaultProfileId", profileId)
            } else {
                put("defaultProfileId", JSONObject.NULL)
            }
            put("activeProfileResolution", JSObject.fromJSONObject(recalc.resolution.toJson()))
            put(
                "message",
                if (label != null) {
                    "Fallback default profile set to \"$label\"."
                } else {
                    "Fallback default cleared. System volume stays unchanged outside timed profiles."
                }
            )
        })
    }

    @PluginMethod
    fun enableScheduler(call: PluginCall) {
        VolumeSchedulerStorage.setSchedulerEnabled(context, true)
        val (scheduledCount, allExact) = VolumeAlarmManager.rescheduleAll(context)
        val recalc = VolumeAlarmManager.recalculateProfilesAndSchedule(
            context = context,
            activateIfCoversNow = true,
            forceApplyActiveIfChanged = true,
            source = "IMMEDIATE_PROFILE"
        )
        val schedules = VolumeSchedulerStorage.getSchedules(context)
        val arr = JSArray()
        schedules.forEach { arr.put(it.toJson()) }

        call.resolve(JSObject().apply {
            put("success", true)
            put("schedulerEnabled", true)
            put("scheduledCount", scheduledCount)
            put("exactMode", allExact)
            put("schedules", arr)
            put("activeProfileResolution", JSObject.fromJSONObject(recalc.resolution.toJson()))
            put(
                "message",
                if (allExact) {
                    "Scheduler enabled. $scheduledCount active alarm(s) queued with exact timing. ${recalc.resolution.message}"
                } else {
                    "Scheduler enabled. $scheduledCount active alarm(s) queued using inexact fallback. ${recalc.resolution.message}"
                }
            )
        })
    }

    @PluginMethod
    fun disableScheduler(call: PluginCall) {
        VolumeSchedulerStorage.setSchedulerEnabled(context, false)
        VolumeAlarmManager.cancelAll(context)
        VolumeSchedulerStorage.setRuntimeActiveProfileId(context, null)
        val updated = VolumeSchedulerStorage.getSchedules(context).map {
            it.copy(nextTriggerAt = 0L)
        }
        VolumeSchedulerStorage.saveSchedules(context, updated)

        call.resolve(JSObject().apply {
            put("success", true)
            put("schedulerEnabled", false)
            put("message", "Scheduler disabled. All pending native alarms and profile transitions canceled.")
        })
    }

    @PluginMethod
    fun getPermissionStatus(call: PluginCall) {
        call.resolve(buildPermissionStatusObject())
    }

    @PluginMethod
    fun requestRequiredPermissions(call: PluginCall) {
        val target = call.getString("permissionType") ?: "auto"
        val statusBefore = buildPermissionStatusObject()

        when (target) {
            "notifications" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    getPermissionState("notifications") == PermissionState.GRANTED
                ) {
                    call.resolve(JSObject().apply {
                        put("opened", false)
                        put("granted", true)
                        put("message", "Notification permission is already granted.")
                        put("status", buildPermissionStatusObject())
                    })
                } else {
                    requestPermissionForAlias("notifications", call, "notificationsPermissionCallback")
                }
                return
            }

            "exact_alarm" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !VolumeAlarmManager.canScheduleExactAlarms(context)) {
                    val opened = openSystemSettings(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                    call.resolve(JSObject().apply {
                        put("opened", opened)
                        put("action", "exact_alarm")
                        put("message", if (opened) "Opened Exact Alarm permission settings." else "Unable to open Exact Alarm settings on this device.")
                        put("status", statusBefore)
                    })
                } else {
                    call.resolve(JSObject().apply {
                        put("opened", false)
                        put("action", "exact_alarm")
                        put("message", "Exact alarm scheduling is already allowed.")
                        put("status", statusBefore)
                    })
                }
                return
            }

            "dnd_access" -> {
                val opened = openSystemSettings(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                )
                call.resolve(JSObject().apply {
                    put("opened", opened)
                    put("action", "dnd_access")
                    put("message", if (opened) "Opened Do Not Disturb Policy Access settings." else "Unable to open Do Not Disturb settings.")
                    put("status", statusBefore)
                })
                return
            }

            "battery_optimization" -> {
                val opened = openSystemSettings(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
                call.resolve(JSObject().apply {
                    put("opened", opened)
                    put("action", "battery_optimization")
                    put("message", if (opened) "Opened Battery Optimization settings." else "Unable to open Battery Optimization settings.")
                    put("status", statusBefore)
                })
                return
            }

            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !VolumeAlarmManager.canScheduleExactAlarms(context)) {
                    val opened = openSystemSettings(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                    call.resolve(JSObject().apply {
                        put("opened", opened)
                        put("action", "exact_alarm")
                        put("message", "Opened Exact Alarm settings for minute-accurate scheduling.")
                        put("status", statusBefore)
                    })
                    return
                }

                val nm = VolumeController.getNotificationManager(context)
                if (!nm.isNotificationPolicyAccessGranted) {
                    val opened = openSystemSettings(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    )
                    call.resolve(JSObject().apply {
                        put("opened", opened)
                        put("action", "dnd_access")
                        put("message", "Opened Do Not Disturb Policy Access settings for ringtone/notification control.")
                        put("status", statusBefore)
                    })
                    return
                }

                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    val opened = openSystemSettings(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                    call.resolve(JSObject().apply {
                        put("opened", opened)
                        put("action", "battery_optimization")
                        put("message", "Opened Battery Optimization settings to prevent background killing.")
                        put("status", statusBefore)
                    })
                    return
                }

                call.resolve(JSObject().apply {
                    put("opened", false)
                    put("action", "none")
                    put("message", "All required permissions and battery exemptions are already active.")
                    put("status", statusBefore)
                })
            }
        }
    }

    @PermissionCallback
    private fun notificationsPermissionCallback(call: PluginCall) {
        val granted = getPermissionState("notifications") == PermissionState.GRANTED
        call.resolve(JSObject().apply {
            put("opened", false)
            put("granted", granted)
            put("action", "notifications")
            put("message", if (granted) "Notification permission granted." else "Notification permission denied.")
            put("status", buildPermissionStatusObject())
        })
    }

    @PluginMethod
    fun testVolumeStream(call: PluginCall) {
        val streamKey = call.getString("stream") ?: AudioStreamType.RINGTONE.key
        val stream = AudioStreamType.fromKey(streamKey)
        if (stream == null) {
            call.reject("Unsupported stream: $streamKey")
            return
        }

        val percentage = call.getInt("percentage")
        val testResult = VolumeController.runStreamTest(
            context = context,
            stream = stream,
            requestedPercentage = percentage,
            source = "FOREGROUND_TEST"
        )

        VolumeSchedulerStorage.appendLog(
            context,
            ExecutionLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = testResult.timestamp,
                source = "FOREGROUND_TEST",
                ruleId = "",
                ruleName = "Stream Test (${stream.label})",
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

        call.resolve(JSObject.fromJSONObject(testResult.toJson()))
    }

    @PluginMethod
    fun scheduleTestAlarm(call: PluginCall) {
        val seconds = call.getInt("secondsFromNow", 10) ?: 10
        val stream = call.getString("stream") ?: AudioStreamType.RINGTONE.key
        val percentage = call.getInt("percentage", 65) ?: 65

        if (AudioStreamType.fromKey(stream) == null) {
            call.reject("Unsupported stream: $stream")
            return
        }

        val dispatch = VolumeAlarmManager.scheduleTestAlarm(context, seconds, stream, percentage)
        call.resolve(JSObject().apply {
            put("success", dispatch.scheduled)
            put("exact", dispatch.exact)
            put("nextTriggerAt", dispatch.nextTriggerAt)
            put("mode", dispatch.mode)
            put("message", dispatch.message)
        })
    }

    @PluginMethod
    fun getExecutionLogs(call: PluginCall) {
        val logs = VolumeSchedulerStorage.getLogs(context)
        val arr = JSArray()
        logs.forEach { arr.put(it.toJson()) }
        val lastTest = VolumeSchedulerStorage.getLastTestAlarmResult(context)
        call.resolve(JSObject().apply {
            put("logs", arr)
            put("bootRestore", JSObject.fromJSONObject(VolumeSchedulerStorage.getBootRestoreInfo(context)))
            if (lastTest != null) {
                put("lastTestAlarmResult", JSObject.fromJSONObject(lastTest.toJson()))
            } else {
                put("lastTestAlarmResult", JSONObject.NULL)
            }
        })
    }

    @PluginMethod
    fun getAudioModeProbe(call: PluginCall) {
        val am = VolumeController.getAudioManager(context)
        val streams = JSObject()
        AudioStreamType.entries.forEach { st ->
            streams.put(st.key, JSObject.fromJSONObject(VolumeController.getStreamSnapshot(context, st)))
        }
        call.resolve(JSObject().apply {
            put("audioMode", VolumeController.getAudioModeString(am))
            put("ringerMode", VolumeController.getRingerModeString(am))
            put("isSpeakerphoneOn", am.isSpeakerphoneOn)
            put("isBluetoothScoOn", am.isBluetoothScoOn)
            put("isMusicActive", am.isMusicActive)
            put("streams", streams)
        })
    }

    private fun openSystemSettings(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun buildVolumeSettingsObject(): JSObject {
        val am = VolumeController.getAudioManager(context)
        val streams = JSObject()
        AudioStreamType.entries.forEach { st ->
            streams.put(st.key, JSObject.fromJSONObject(VolumeController.getStreamSnapshot(context, st)))
        }

        val schedulerEnabled = VolumeSchedulerStorage.isSchedulerEnabled(context)
        val schedules = VolumeSchedulerStorage.getSchedules(context)
        val schedulesArr = JSArray()
        schedules.forEach { schedulesArr.put(it.toJson()) }
        val next = findNextScheduledChange(schedules, schedulerEnabled)

        val logsArr = JSArray()
        VolumeSchedulerStorage.getLogs(context).forEach { logsArr.put(it.toJson()) }

        val lastTest = VolumeSchedulerStorage.getLastTestAlarmResult(context)
        val (aliased, aliasedNote) = VolumeSchedulerStorage.getStreamsAliasedInfo(context)

        val profiles = VolumeSchedulerStorage.getProfiles(context)
        val profilesArr = JSArray()
        profiles.forEach { profilesArr.put(it.toJson()) }
        val overlapReport = VolumeProfileManager.detectOverlaps(profiles)
        val resolution = VolumeProfileManager.resolveProfileAt(context)
        val defaultProfileId = VolumeSchedulerStorage.getDefaultProfileId(context)
        val nextProfileTransitionAt = VolumeSchedulerStorage.getNextProfileTransitionAt(context)

        return JSObject().apply {
            put("native", true)
            put("streams", streams)
            put("ringerMode", VolumeController.getRingerModeString(am))
            put("audioMode", VolumeController.getAudioModeString(am))
            put("isVolumeFixed", am.isVolumeFixed)
            put("isDoNotDisturbActive", VolumeController.isDoNotDisturbActive(context))
            put("streamsAliased", aliased)
            put("streamsAliasedNote", aliasedNote)
            put("schedulerEnabled", schedulerEnabled)
            put("schedules", schedulesArr)
            put("profiles", profilesArr)
            if (defaultProfileId != null) {
                put("defaultProfileId", defaultProfileId)
            } else {
                put("defaultProfileId", JSONObject.NULL)
            }
            put("profileOverlap", JSObject.fromJSONObject(overlapReport.toJson()))
            put("activeProfileResolution", JSObject.fromJSONObject(resolution.toJson()))
            put("nextProfileTransitionAt", nextProfileTransitionAt)
            if (next != null) {
                put("nextScheduledChange", JSObject.fromJSONObject(next.toJson()))
            } else {
                put("nextScheduledChange", JSONObject.NULL)
            }
            put("bootRestore", JSObject.fromJSONObject(VolumeSchedulerStorage.getBootRestoreInfo(context)))
            put("recentLogs", logsArr)
            if (lastTest != null) {
                put("lastTestAlarmResult", JSObject.fromJSONObject(lastTest.toJson()))
            } else {
                put("lastTestAlarmResult", JSONObject.NULL)
            }
            put("deviceInfo", JSObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("androidVersion", Build.VERSION.RELEASE)
                put("sdkInt", Build.VERSION.SDK_INT)
            })
        }
    }

    private fun buildProfileActionMessage(prefix: String, recalc: ProfileRecalcResult): String {
        val parts = mutableListOf(prefix)
        if (recalc.executedImmediately && recalc.executionOutcome != null) {
            parts.add("Activated immediately (${recalc.executionOutcome.summaryMessage}).")
        } else if (recalc.resolution.status == "conflict") {
            parts.add(recalc.resolution.message)
        } else {
            parts.add(recalc.resolution.message)
        }
        if (recalc.dispatch.scheduled) {
            parts.add(recalc.dispatch.message)
        }
        return parts.joinToString(" ")
    }

    private fun buildPermissionStatusObject(): JSObject {
        val exactAllowed = VolumeAlarmManager.canScheduleExactAlarms(context)
        val exactSdkNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val nm = VolumeController.getNotificationManager(context)
        val dndGranted = nm.isNotificationPolicyAccessGranted
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryExempt = pm.isIgnoringBatteryOptimizations(context.packageName)

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val pmState = context.packageManager.getComponentEnabledSetting(
            ComponentName(context, BootCompletedReceiver::class.java)
        )
        val bootReceiverActive = pmState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        return JSObject().apply {
            put("exactAlarm", JSObject().apply {
                put("granted", exactAllowed)
                put("required", exactSdkNeeded)
                put("sdkSupported", exactSdkNeeded)
                put(
                    "detail",
                    if (exactAllowed) {
                        "Exact AlarmManager scheduling is permitted."
                    } else {
                        "Exact alarms restricted; scheduler falls back to Doze-safe inexact alarms."
                    }
                )
            })
            put("dndPolicyAccess", JSObject().apply {
                put("granted", dndGranted)
                put("required", true)
                put(
                    "detail",
                    if (dndGranted) {
                        "App can adjust Ringtone and Notification volumes across silent/DND states."
                    } else {
                        "Required on Android to mute/unmute Ringtone or Notification when DND is active."
                    }
                )
            })
            put("batteryOptimizationExempt", JSObject().apply {
                put("granted", batteryExempt)
                put("recommended", true)
                put(
                    "detail",
                    if (batteryExempt) {
                        "Unrestricted background battery usage enabled."
                    } else {
                        "Recommended on Vivo Funtouch/OriginOS so background alarms are not delayed."
                    }
                )
            })
            put("postNotifications", JSObject().apply {
                put("granted", notifGranted)
                put("required", false)
                put(
                    "detail",
                    if (notifGranted) {
                        "Optional permission granted."
                    } else {
                        "Optional permission not granted (not required for volume scheduling or testing)."
                    }
                )
            })
            put("bootReceiverRegistered", bootReceiverActive)
        }
    }

    private fun findNextScheduledChange(
        schedules: List<VolumeSchedule>,
        schedulerEnabled: Boolean
    ): VolumeSchedule? {
        if (!schedulerEnabled) return null
        val enabled = schedules.filter { it.enabled && it.nextTriggerAt > 0L }
        return enabled.minByOrNull { it.nextTriggerAt }
    }
}
