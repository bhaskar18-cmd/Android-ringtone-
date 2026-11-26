package com.smartvolumescheduler.app

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
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

        // Map percentage directly to Android's 0..maxIndex range and clamp to [minIndex, maxIndex]
        val rawTargetIndex = ((clampedPct / 100.0) * maxIndex).roundToInt()
        val targetIndex = rawTargetIndex.coerceIn(minIndex, maxIndex)
        val flags = if (showSystemUi) AudioManager.FLAG_SHOW_UI else 0

        return try {
            // If setting Ringtone > 0 while device is in silent/vibrate, restore normal ringer mode first
            if (stream == AudioStreamType.RINGTONE && targetIndex > 0 && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }

            am.setStreamVolume(stream.androidStream, targetIndex, flags)

            val actualIndex = am.getStreamVolume(stream.androidStream)
            val actualPct = ((actualIndex.toDouble() / maxIndex.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
            val appliedToHardware = (actualIndex == targetIndex)
            val exactRequested = appliedToHardware && (rawTargetIndex == targetIndex)
            val dndAccessGranted = nm.isNotificationPolicyAccessGranted

            val note = when {
                !appliedToHardware ->
                    "Requested ${stream.label} index $targetIndex/$maxIndex ($clampedPct%), but Android kept level at $actualIndex/$maxIndex ($actualPct%)."
                rawTargetIndex < minIndex ->
                    "Applied minimum supported level $actualIndex/$maxIndex ($actualPct%) for ${stream.label} (Android enforces min index $minIndex)."
                else ->
                    "Applied ${stream.label}: $actualIndex/$maxIndex ($actualPct% achieved for $clampedPct% requested)."
            }

            VolumeApplyResult(
                success = appliedToHardware,
                appliedExactTarget = exactRequested,
                stream = stream.key,
                requestedPercentage = clampedPct,
                targetIndex = targetIndex,
                actualIndex = actualIndex,
                actualPercentage = actualPct,
                minIndex = minIndex,
                maxIndex = maxIndex,
                dndBlocked = !appliedToHardware && !dndAccessGranted,
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

    fun runStreamTest(
        context: Context,
        stream: AudioStreamType,
        requestedPercentage: Int?,
        source: String,
        onPlaybackComplete: (() -> Unit)? = null
    ): StreamTestResult {
        val am = getAudioManager(context)
        val targetPct = requestedPercentage ?: run {
            val cur = am.getStreamVolume(stream.androidStream)
            val max = am.getStreamMaxVolume(stream.androidStream).coerceAtLeast(1)
            ((cur.toDouble() / max.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        }

        val applyResult = applyVolume(context, stream.key, targetPct, showSystemUi = false)
        val audioModeStr = getAudioModeString(am)
        val ringerModeStr = getRingerModeString(am)
        val callSessionActive = am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION

        if (!applyResult.success) {
            onPlaybackComplete?.invoke()
            return StreamTestResult(
                stream = stream.key,
                requestedPercentage = applyResult.requestedPercentage,
                targetIndex = applyResult.targetIndex,
                actualIndex = applyResult.actualIndex,
                minIndex = applyResult.minIndex,
                maxIndex = applyResult.maxIndex,
                actualPercentage = applyResult.actualPercentage,
                volumeApplied = false,
                playbackStarted = false,
                streamVerified = false,
                callSessionActive = callSessionActive,
                audioMode = audioModeStr,
                ringerMode = ringerModeStr,
                dndBlocked = applyResult.dndBlocked,
                fixedVolume = applyResult.fixedVolume,
                source = source,
                timestamp = System.currentTimeMillis(),
                message = applyResult.message
            )
        }

        if (stream == AudioStreamType.VOICE_CALL) {
            onPlaybackComplete?.invoke()
            val msg = if (callSessionActive) {
                "Voice Call volume set to ${applyResult.actualIndex}/${applyResult.maxIndex} (${applyResult.actualPercentage}%) during active $audioModeStr session. No notification sound was substituted."
            } else {
                "Voice Call volume index set to ${applyResult.actualIndex}/${applyResult.maxIndex} (${applyResult.actualPercentage}%), but actual call loudness could not be verified because no call or communication audio session is active ($audioModeStr). Test during a real phone or VoIP call to verify call loudness."
            }
            return StreamTestResult(
                stream = stream.key,
                requestedPercentage = applyResult.requestedPercentage,
                targetIndex = applyResult.targetIndex,
                actualIndex = applyResult.actualIndex,
                minIndex = applyResult.minIndex,
                maxIndex = applyResult.maxIndex,
                actualPercentage = applyResult.actualPercentage,
                volumeApplied = true,
                playbackStarted = false,
                streamVerified = callSessionActive,
                callSessionActive = callSessionActive,
                audioMode = audioModeStr,
                ringerMode = ringerModeStr,
                dndBlocked = false,
                fixedVolume = false,
                source = source,
                timestamp = System.currentTimeMillis(),
                message = msg
            )
        }

        val (playbackStarted, playbackMsg) = playStreamAudioSample(context, stream, onPlaybackComplete)
        val verified = applyResult.success && playbackStarted
        val fullMessage = "${applyResult.message} $playbackMsg"

        return StreamTestResult(
            stream = stream.key,
            requestedPercentage = applyResult.requestedPercentage,
            targetIndex = applyResult.targetIndex,
            actualIndex = applyResult.actualIndex,
            minIndex = applyResult.minIndex,
            maxIndex = applyResult.maxIndex,
            actualPercentage = applyResult.actualPercentage,
            volumeApplied = true,
            playbackStarted = playbackStarted,
            streamVerified = verified,
            callSessionActive = callSessionActive,
            audioMode = audioModeStr,
            ringerMode = ringerModeStr,
            dndBlocked = false,
            fixedVolume = false,
            source = source,
            timestamp = System.currentTimeMillis(),
            message = fullMessage
        )
    }

    private fun playStreamAudioSample(
        context: Context,
        stream: AudioStreamType,
        onPlaybackComplete: (() -> Unit)?
    ): Pair<Boolean, String> {
        return when (stream) {
            AudioStreamType.MEDIA -> playBundledMediaWav(context, onPlaybackComplete)
            AudioStreamType.RINGTONE -> playSystemUriOnStream(
                context = context,
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                usage = AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
                label = "Ringtone",
                durationMs = 1500L,
                onPlaybackComplete = onPlaybackComplete
            )
            AudioStreamType.NOTIFICATION -> playSystemUriOnStream(
                context = context,
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                usage = AudioAttributes.USAGE_NOTIFICATION,
                contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
                label = "Notification",
                durationMs = 1300L,
                onPlaybackComplete = onPlaybackComplete
            )
            AudioStreamType.ALARM -> playSystemUriOnStream(
                context = context,
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                usage = AudioAttributes.USAGE_ALARM,
                contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
                label = "Alarm",
                durationMs = 1500L,
                onPlaybackComplete = onPlaybackComplete
            )
            AudioStreamType.VOICE_CALL -> {
                onPlaybackComplete?.invoke()
                false to "Voice Call does not play a substitute notification sound."
            }
        }
    }

    private fun playBundledMediaWav(
        context: Context,
        onPlaybackComplete: (() -> Unit)?
    ): Pair<Boolean, String> {
        val player = MediaPlayer()
        var released = false
        val mainHandler = Handler(Looper.getMainLooper())
        val releaseOnce = {
            if (!released) {
                released = true
                try {
                    if (player.isPlaying) player.stop()
                } catch (_: Exception) {
                }
                try {
                    player.release()
                } catch (_: Exception) {
                }
                onPlaybackComplete?.invoke()
            }
        }

        return try {
            val afd = context.resources.openRawResourceFd(R.raw.media_test_tone)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            player.setOnCompletionListener { releaseOnce() }
            player.setOnErrorListener { _, _, _ ->
                releaseOnce()
                true
            }
            player.prepare()
            player.start()
            mainHandler.postDelayed({ releaseOnce() }, 1800L)
            true to "Started bundled local WAV playback on Media stream (STREAM_MUSIC)."
        } catch (ex: Exception) {
            releaseOnce()
            false to "Failed to play bundled Media test audio: ${ex.message ?: "unknown error"}"
        }
    }

    private fun playSystemUriOnStream(
        context: Context,
        uri: Uri?,
        usage: Int,
        contentType: Int,
        label: String,
        durationMs: Long,
        onPlaybackComplete: (() -> Unit)?
    ): Pair<Boolean, String> {
        if (uri == null) {
            onPlaybackComplete?.invoke()
            return false to "No default $label sound URI configured on device."
        }

        val player = MediaPlayer()
        var released = false
        val mainHandler = Handler(Looper.getMainLooper())
        val releaseOnce = {
            if (!released) {
                released = true
                try {
                    if (player.isPlaying) player.stop()
                } catch (_: Exception) {
                }
                try {
                    player.release()
                } catch (_: Exception) {
                }
                onPlaybackComplete?.invoke()
            }
        }

        return try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()
            )
            player.setDataSource(context.applicationContext, uri)
            player.setOnCompletionListener { releaseOnce() }
            player.setOnErrorListener { _, _, _ ->
                releaseOnce()
                true
            }
            player.prepare()
            player.start()
            mainHandler.postDelayed({ releaseOnce() }, durationMs)
            true to "Started real $label sound playback on $label stream."
        } catch (ex: Exception) {
            releaseOnce()
            // Fallback to Ringtone API on the exact same stream usage (never substitutes another stream)
            try {
                val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
                if (ringtone != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ringtone.audioAttributes = AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(contentType)
                            .build()
                    } else {
                        @Suppress("DEPRECATION")
                        ringtone.streamType = when (usage) {
                            AudioAttributes.USAGE_ALARM -> AudioManager.STREAM_ALARM
                            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> AudioManager.STREAM_RING
                            else -> AudioManager.STREAM_NOTIFICATION
                        }
                    }
                    ringtone.play()
                    mainHandler.postDelayed({
                        try {
                            if (ringtone.isPlaying) ringtone.stop()
                        } catch (_: Exception) {
                        }
                        onPlaybackComplete?.invoke()
                    }, durationMs)
                    true to "Started real $label ringtone playback on $label stream."
                } else {
                    false to "Failed to start $label playback: ${ex.message ?: "unavailable"}"
                }
            } catch (fallbackEx: Exception) {
                false to "Failed to start $label playback: ${fallbackEx.message ?: ex.message ?: "unavailable"}"
            }
        }
    }
}
