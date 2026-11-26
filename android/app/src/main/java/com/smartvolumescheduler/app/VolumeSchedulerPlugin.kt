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
import org.json.JSONArray
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
                scheduleId = "",
                stream = result.stream,
                requestedPercentage = result.requestedPercentage,
                actualIndex = result.actualIndex,
                maxIndex = result.maxIndex,
                actualPercentage = result.actualPercentage,
                success = result.success,
                message = result.message
            )
        )

        val response = JSObject.fromJSONObject(result.toJson())
        call.resolve(response)
    }

    @PluginMethod
    fun scheduleVolumeChange(call: PluginCall) {
        val time = call.getString("time")
        val stream = call.getString("stream")
        val percentage = call.getInt("percentage")
        if (time.isNullOrBlank() || stream.isNullOrBlank() || percentage == null) {
            call.reject("Missing required parameters: time, stream, percentage.")
            return
        }

        if (AudioStreamType.fromKey(stream) == null) {
            call.reject("Unsupported audio stream: $stream")
            return
        }

        val id = call.getString("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val existing = VolumeSchedulerStorage.getScheduleById(context, id)
        val label = call.getString("label") ?: existing?.label ?: ""
        val enabled = call.getBoolean("enabled") ?: existing?.enabled ?: true
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

        val schedule = VolumeSchedule(
            id = id,
            label = label.trim(),
            time = time.trim(),
            stream = stream.trim(),
            percentage = percentage.coerceIn(0, 100),
            days = days.sorted(),
            enabled = enabled,
            lastExecutedAt = existing?.lastExecutedAt ?: 0L,
            lastExecutionSuccess = existing?.lastExecutionSuccess ?: true,
            lastExecutionMessage = existing?.lastExecutionMessage ?: ""
        )

        VolumeSchedulerStorage.upsertSchedule(context, schedule)
        val dispatch = VolumeAlarmManager.scheduleSingle(context, schedule)
        val saved = VolumeSchedulerStorage.getScheduleById(context, id) ?: schedule

        val res = JSObject().apply {
            put("success", true)
            put("schedule", JSObject.fromJSONObject(saved.toJson()))
            put("scheduled", dispatch.scheduled)
            put("exact", dispatch.exact)
            put("mode", dispatch.mode)
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
        VolumeAlarmManager.cancelSchedule(context, id)
        val (deleted, remaining) = VolumeSchedulerStorage.deleteSchedule(context, id)
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
        val dispatch = VolumeAlarmManager.scheduleSingle(context, updated)
        val saved = VolumeSchedulerStorage.getScheduleById(context, id) ?: updated

        call.resolve(JSObject().apply {
            put("success", true)
            put("schedule", JSObject.fromJSONObject(saved.toJson()))
            put("mode", dispatch.mode)
            put("message", dispatch.message)
        })
    }

    @PluginMethod
    fun enableScheduler(call: PluginCall) {
        VolumeSchedulerStorage.setSchedulerEnabled(context, true)
        val (scheduledCount, allExact) = VolumeAlarmManager.rescheduleAll(context)
        val schedules = VolumeSchedulerStorage.getSchedules(context)
        val arr = JSArray()
        schedules.forEach { arr.put(it.toJson()) }

        call.resolve(JSObject().apply {
            put("success", true)
            put("schedulerEnabled", true)
            put("scheduledCount", scheduledCount)
            put("exactMode", allExact)
            put("schedules", arr)
            put(
                "message",
                if (allExact) {
                    "Scheduler enabled. $scheduledCount active rule(s) queued with exact AlarmManager timing."
                } else {
                    "Scheduler enabled. $scheduledCount active rule(s) queued using inexact fallback (grant Exact Alarm permission for minute-accurate timing)."
                }
            )
        })
    }

    @PluginMethod
    fun disableScheduler(call: PluginCall) {
        VolumeSchedulerStorage.setSchedulerEnabled(context, false)
        VolumeAlarmManager.cancelAll(context)
        val updated = VolumeSchedulerStorage.getSchedules(context).map {
            it.copy(nextTriggerAt = 0L)
        }
        VolumeSchedulerStorage.saveSchedules(context, updated)

        call.resolve(JSObject().apply {
            put("success", true)
            put("schedulerEnabled", false)
            put("message", "Scheduler disabled. All pending native alarms cancelled.")
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
                // Auto: only request/open the highest-priority missing permission
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
        val playTone = call.getBoolean("playTone", true) ?: true

        val applyResult = if (percentage != null) {
            VolumeController.applyVolume(context, stream.key, percentage, showSystemUi = false)
        } else {
            val snap = VolumeController.getStreamSnapshot(context, stream)
            val curr = snap.optInt("currentIndex", 0)
            val min = snap.optInt("minIndex", 0)
            val max = snap.optInt("maxIndex", 1)
            val pct = snap.optInt("percentage", 0)
            VolumeApplyResult(
                success = true,
                appliedExactTarget = true,
                stream = stream.key,
                requestedPercentage = pct,
                targetIndex = curr,
                actualIndex = curr,
                actualPercentage = pct,
                minIndex = min,
                maxIndex = max,
                dndBlocked = false,
                fixedVolume = false,
                message = "Read current ${stream.label} volume: $curr/$max ($pct%)."
            )
        }

        val (tonePlayed, toneMsg) = if (playTone && applyResult.success) {
            VolumeController.playBriefProbeTone(context, stream)
        } else {
            false to "Tone probe skipped."
        }

        val am = VolumeController.getAudioManager(context)
        VolumeSchedulerStorage.appendLog(
            context,
            ExecutionLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                source = "STREAM_TEST",
                scheduleId = "",
                stream = applyResult.stream,
                requestedPercentage = applyResult.requestedPercentage,
                actualIndex = applyResult.actualIndex,
                maxIndex = applyResult.maxIndex,
                actualPercentage = applyResult.actualPercentage,
                success = applyResult.success,
                message = "${applyResult.message} $toneMsg"
            )
        )

        call.resolve(JSObject().apply {
            put("applyResult", JSObject.fromJSONObject(applyResult.toJson()))
            put("tonePlayed", tonePlayed)
            put("toneMessage", toneMsg)
            put("audioMode", VolumeController.getAudioModeString(am))
            put("ringerMode", VolumeController.getRingerModeString(am))
        })
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
        call.resolve(JSObject().apply {
            put("logs", arr)
            put("bootRestore", JSObject.fromJSONObject(VolumeSchedulerStorage.getBootRestoreInfo(context)))
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

        return JSObject().apply {
            put("native", true)
            put("streams", streams)
            put("ringerMode", VolumeController.getRingerModeString(am))
            put("audioMode", VolumeController.getAudioModeString(am))
            put("isVolumeFixed", am.isVolumeFixed)
            put("isDoNotDisturbActive", VolumeController.isDoNotDisturbActive(context))
            put("schedulerEnabled", schedulerEnabled)
            put("schedules", schedulesArr)
            if (next != null) {
                put("nextScheduledChange", JSObject.fromJSONObject(next.toJson()))
            } else {
                put("nextScheduledChange", JSONObject.NULL)
            }
            put("bootRestore", JSObject.fromJSONObject(VolumeSchedulerStorage.getBootRestoreInfo(context)))
            put("recentLogs", logsArr)
            put("deviceInfo", JSObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("androidVersion", Build.VERSION.RELEASE)
                put("sdkInt", Build.VERSION.SDK_INT)
            })
        }
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
                        "Allowed for silent test-alarm diagnostic confirmations."
                    } else {
                        "Optional: only used for silent confirmation when running test alarms."
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
