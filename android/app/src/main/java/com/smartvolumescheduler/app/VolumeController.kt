package com.smartvolumescheduler.app

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.math.roundToInt

object VolumeController {

    fun getAudioManager(context: Context): AudioManager {
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun getNotificationManager(context: Context): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun getMinVolume(audioManager: AudioManager, streamType: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                audioManager.getStreamMinVolume(streamType)
            } catch (_: Exception) {
                0
            }
        } else {
            0
        }
    }

    fun getStreamSnapshot(context: Context, stream: AudioStreamType): JSONObject {
        val am = getAudioManager(context)
        val current = am.getStreamVolume(stream.androidStream)
        val min = getMinVolume(am, stream.androidStream)
        val max = am.getStreamMaxVolume(stream.androidStream).coerceAtLeast(1)
        val pct = ((current.toDouble() / max.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)

        return JSONObject().apply {
            put("stream", stream.key)
            put("label", stream.label)
            put("currentIndex", current)
            put("minIndex", min)
            put("maxIndex", max)
            put("percentage", pct)
        }
    }

    fun getRingerModeString(audioManager: AudioManager): String {
        return when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
    }

    fun getAudioModeString(audioManager: AudioManager): String {
        return when (audioManager.mode) {
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            AudioManager.MODE_CALL_SCREENING -> "MODE_CALL_SCREENING"
            else -> "MODE_NORMAL"
        }
    }

    fun isDoNotDisturbActive(context: Context): Boolean {
        val nm = getNotificationManager(context)
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    fun applyVolume(
        context: Context,
        streamKey: String,
        requestedPercentage: Int,
        showSystemUi: Boolean = false
    ): VolumeApplyResult {
        val stream = AudioStreamType.fromKey(streamKey)
            ?: return VolumeApplyResult(
                success = false,
                appliedExactTarget = false,
                stream = streamKey,
                requestedPercentage = requestedPercentage,
                targetIndex = 0,
                actualIndex = 0,
                actualPercentage = 0,
                minIndex = 0,
                maxIndex = 0,
                dndBlocked = false,
                fixedVolume = false,
                message = "Unsupported audio stream: $streamKey"
            )

        val clampedPct = requestedPercentage.coerceIn(0, 100)
        val am = getAudioManager(context)
        val nm = getNotificationManager(context)
        val minIndex = getMinVolume(am, stream.androidStream)
        val maxIndex = am.getStreamMaxVolume(stream.androidStream).coerceAtLeast(1)

        if (am.isVolumeFixed) {
            val actual = am.getStreamVolume(stream.androidStream)
            val actualPct = ((actual.toDouble() / maxIndex.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
            return VolumeApplyResult(
                success = false,
                appliedExactTarget = false,
                stream = stream.key,
                requestedPercentage = clampedPct,
                targetIndex = actual,
                actualIndex = actual,
                actualPercentage = actualPct,
                minIndex = minIndex,
                maxIndex = maxIndex,
                dndBlocked = false,
                fixedVolume = true,
                message = "Device reports fixed volume mode; Android AudioManager refuses stream adjustments."
            )
        }

        val span = (maxIndex - minIndex).coerceAtLeast(0)
        val targetIndex = (minIndex + ((clampedPct / 100.0) * span).roundToInt()).coerceIn(minIndex, maxIndex)
        val flags = if (showSystemUi) AudioManager.FLAG_SHOW_UI else 0

        return try {
            am.setStreamVolume(stream.androidStream, targetIndex, flags)
            val actualIndex = am.getStreamVolume(stream.androidStream)
            val actualPct = ((actualIndex.toDouble() / maxIndex.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
            val matched = actualIndex == targetIndex

            val note = when {
                matched && clampedPct == 0 && minIndex > 0 ->
                    "Applied minimum supported level $actualIndex/$maxIndex ($actualPct%). Android enforces min index $minIndex for ${stream.label}."
                matched ->
                    "Set ${stream.label} to $actualIndex/$maxIndex ($actualPct%)."
                else ->
                    "Requested index $targetIndex/$maxIndex, Android clamped actual level to $actualIndex/$maxIndex ($actualPct%)."
            }

            VolumeApplyResult(
                success = true,
                appliedExactTarget = matched,
                stream = stream.key,
                requestedPercentage = clampedPct,
                targetIndex = targetIndex,
                actualIndex = actualIndex,
                actualPercentage = actualPct,
                minIndex = minIndex,
                maxIndex = maxIndex,
                dndBlocked = false,
                fixedVolume = false,
                message = note
            )
        } catch (sec: SecurityException) {
            val actualIndex = am.getStreamVolume(stream.androidStream)
            val actualPct = ((actualIndex.toDouble() / maxIndex.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
            val dndAccessGranted = nm.isNotificationPolicyAccessGranted
            val detail = if (!dndAccessGranted) {
                "Android blocked ${stream.label} volume change because Do Not Disturb Policy Access is not granted."
            } else {
                "SecurityException while modifying ${stream.label}: ${sec.message ?: "restricted by system policy"}"
            }
            VolumeApplyResult(
                success = false,
                appliedExactTarget = false,
                stream = stream.key,
                requestedPercentage = clampedPct,
                targetIndex = targetIndex,
                actualIndex = actualIndex,
                actualPercentage = actualPct,
                minIndex = minIndex,
                maxIndex = maxIndex,
                dndBlocked = !dndAccessGranted,
                fixedVolume = false,
                message = detail
            )
        } catch (ex: Exception) {
            val actualIndex = am.getStreamVolume(stream.androidStream)
            val actualPct = ((actualIndex.toDouble() / maxIndex.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
            VolumeApplyResult(
                success = false,
                appliedExactTarget = false,
                stream = stream.key,
                requestedPercentage = clampedPct,
                targetIndex = targetIndex,
                actualIndex = actualIndex,
                actualPercentage = actualPct,
                minIndex = minIndex,
                maxIndex = maxIndex,
                dndBlocked = false,
                fixedVolume = false,
                message = "Failed to change ${stream.label}: ${ex.message ?: "unknown error"}"
            )
        }
    }

    fun playBriefProbeTone(context: Context, stream: AudioStreamType): Pair<Boolean, String> {
        return try {
            when (stream) {
                AudioStreamType.NOTIFICATION, AudioStreamType.RINGTONE, AudioStreamType.ALARM -> {
                    val uriType = when (stream) {
                        AudioStreamType.ALARM -> RingtoneManager.TYPE_ALARM
                        AudioStreamType.RINGTONE -> RingtoneManager.TYPE_RINGTONE
                        else -> RingtoneManager.TYPE_NOTIFICATION
                    }
                    val uri = RingtoneManager.getDefaultUri(uriType)
                    val ringtone = RingtoneManager.getRingtone(context, uri)
                    if (ringtone != null) {
                        val usage = when (stream) {
                            AudioStreamType.ALARM -> AudioAttributes.USAGE_ALARM
                            AudioStreamType.RINGTONE -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                            else -> AudioAttributes.USAGE_NOTIFICATION
                        }
                        ringtone.audioAttributes = AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        ringtone.play()
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                if (ringtone.isPlaying) ringtone.stop()
                            } catch (_: Exception) {
                            }
                        }, 1100L)
                        true to "Played 1.1s ${stream.label} audio probe."
                    } else {
                        playToneGenerator(stream.androidStream, stream.label)
                    }
                }
                AudioStreamType.MEDIA, AudioStreamType.VOICE_CALL -> {
                    playToneGenerator(stream.androidStream, stream.label)
                }
            }
        } catch (ex: Exception) {
            false to "Audio probe could not play on ${stream.label}: ${ex.message ?: "audio focus unavailable"}"
        }
    }

    private fun playToneGenerator(streamType: Int, label: String): Pair<Boolean, String> {
        val tone = ToneGenerator(streamType, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                tone.release()
            } catch (_: Exception) {
            }
        }, 650L)
        return true to "Played 500ms tone probe on $label stream."
    }
}
