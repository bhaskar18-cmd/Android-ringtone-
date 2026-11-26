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

    fun computeTargetIndex(percentage: Int, minIndex: Int, maxIndex: Int): Int {
        val clampedPct = percentage.coerceIn(0, 100)
        val span = (maxIndex - minIndex).coerceAtLeast(0)
        val computed = minIndex + ((clampedPct / 100.0) * span).roundToInt()
        return computed.coerceIn(minIndex, maxIndex)
    }

    fun computeAchievablePercentage(index: Int, minIndex: Int, maxIndex: Int): Int {
        val span = maxIndex - minIndex
        if (span <= 0) return 100
        return (((index - minIndex).toDouble() / span.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
    }

    fun getStreamSnapshot(context: Context, stream: AudioStreamType): JSONObject {
        val am = getAudioManager(context)
        return try {
            val current = am.getStreamVolume(stream.androidStream)
            val min = getMinVolume(am, stream.androidStream)
            val max = am.getStreamMaxVolume(stream.androidStream).coerceAtLeast(min.coerceAtLeast(1))
            val pct = computeAchievablePercentage(current, min, max)

            JSONObject().apply {
                put("stream", stream.key)
                put("label", stream.label)
                put("currentIndex", current)
                put("minIndex", min)
                put("maxIndex", max)
                put("percentage", pct)
                put("available", true)
            }
        } catch (_: Exception) {
            JSONObject().apply {
                put("stream", stream.key)
                put("label", stream.label)
                put("currentIndex", 0)
                put("minIndex", 0)
                put("maxIndex", 0)
                put("percentage", 0)
                put("available", false)
            }
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
                indexBefore = 0,
                targetIndex = 0,
                actualIndex = 0,
                actualPercentage = 0,
                minIndex = 0,
                maxIndex = 0,
                dndBlocked = false,
                fixedVolume = false,
                streamUnavailable = true,
                failureReason = "Unsupported audio stream: $streamKey",
                message = "Unsupported audio stream: $streamKey"
            )

        val clampedPct = requestedPercentage.coerceIn(0, 100)
        val am = getAudioManager(context)
        val nm = getNotificationManager(context)

        val minIndex: Int
        val maxIndex: Int
        val indexBefore: Int
        try {
            minIndex = getMinVolume(am, stream.androidStream)
            maxIndex = am.getStreamMaxVolume(stream.androidStream)
            indexBefore = am.getStreamVolume(stream.androidStream)
            if (maxIndex <= 0 || maxIndex < minIndex) {
                return VolumeApplyResult(
                    success = false,
                    appliedExactTarget = false,
                    stream = stream.key,
                    requestedPercentage = clampedPct,
                    indexBefore = indexBefore,
                    targetIndex = indexBefore,
                    actualIndex = indexBefore,
                    actualPercentage = 0,
                    minIndex = minIndex,
                    maxIndex = maxIndex,
                    dndBlocked = false,
                    fixedVolume = false,
                    streamUnavailable = true,
                    failureReason = "Volume stream unavailable (${stream.label} reported maxIndex=$maxIndex).",
                    message = "${stream.label} stream is unavailable on this device."
                )
            }
        } catch (ex: Exception) {
            return VolumeApplyResult(
                success = false,
                appliedExactTarget = false,
                stream = stream.key,
                requestedPercentage = clampedPct,
                indexBefore = 0,
                targetIndex = 0,
                actualIndex = 0,
                actualPercentage = 0,
                minIndex = 0,
                maxIndex = 0,
                dndBlocked = false,
                fixedVolume = false,
                streamUnavailable = true,
                failureReason = "Volume stream unavailable: ${ex.message ?: "unsupported"}",
                message = "Unable to query ${stream.label} stream on this device."
            )
        }

        if (am.isVolumeFixed) {
            val actualPct = computeAchievablePercentage(indexBefore, minIndex, maxIndex)
            val reason = "Device reports fixed volume mode; Android AudioManager refuses stream adjustments."
            return VolumeApplyResult(
                success = false,
                appliedExactTarget = false,
                stream = stream.key,
                requestedPercentage = clampedPct,
                indexBefore = indexBefore,
                targetIndex = indexBefore,
                actualIndex = indexBefore,
                actualPercentage = actualPct,
                minIndex = minIndex,
                maxIndex = maxIndex,
                dndBlocked = false,
                fixedVolume = true,
                streamUnavailable = false,
                failureReason = reason,
                message = reason
            )
        }

        val targetIndex = computeTargetIndex(clampedPct, minIndex, maxIndex)
        val flags = if (showSystemUi) AudioManager.FLAG_SHOW_UI else 0

        // Snapshot Ring/Notification before changing either so we can detect OS stream aliasing honestly
        val ringBefore = if (stream == AudioStreamType.NOTIFICATION) {
            try { am.getStreamVolume(AudioManager.STREAM_RING) } catch (_: Exception) { -1 }
        } else -1
        val notifBefore = if (stream == AudioStreamType.RINGTONE) {
            try { am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) } catch (_: Exception) { -1 }
        } else -1

        return try {
            if ((stream == AudioStreamType.RINGTONE || stream == AudioStreamType.NOTIFICATION) &&
                targetIndex > 0 &&
                am.ringerMode != AudioManager.RINGER_MODE_NORMAL
            ) {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }

            am.setStreamVolume(stream.androidStream, targetIndex, flags)

            val actualIndex = am.getStreamVolume(stream.androidStream)
            val actualPct = computeAchievablePercentage(actualIndex, minIndex, maxIndex)
            val appliedToHardware = (actualIndex == targetIndex)
            val exactPctMatch = (actualPct == clampedPct)
            val dndAccessGranted = nm.isNotificationPolicyAccessGranted

            // Detect OS-level Ringtone <-> Notification aliasing
            if (stream == AudioStreamType.RINGTONE && notifBefore >= 0 && actualIndex != indexBefore) {
                val notifAfter = try { am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) } catch (_: Exception) { -1 }
                if (notifAfter >= 0 && notifAfter != notifBefore) {
                    VolumeSchedulerStorage.setStreamsAliased(
                        context,
                        true,
                        "Changing Ringtone also moved Notification ($notifBefore -> $notifAfter); Android links these streams on this device."
                    )
                }
            } else if (stream == AudioStreamType.NOTIFICATION && ringBefore >= 0 && actualIndex != indexBefore) {
                val ringAfter = try { am.getStreamVolume(AudioManager.STREAM_RING) } catch (_: Exception) { -1 }
                if (ringAfter >= 0 && ringAfter != ringBefore) {
                    VolumeSchedulerStorage.setStreamsAliased(
                        context,
                        true,
                        "Changing Notification also moved Ringtone ($ringBefore -> $ringAfter); Android links these streams on this device."
                    )
                }
            }

            val failureReason = if (!appliedToHardware) {
                if (!dndAccessGranted && (stream == AudioStreamType.RINGTONE || stream == AudioStreamType.NOTIFICATION)) {
                    "Android restriction: Do Not Disturb Policy Access not granted or stream clamped at $actualIndex/$maxIndex."
                } else {
                    "Android AudioManager clamped or rejected target step $targetIndex/$maxIndex; actual index remained $actualIndex/$maxIndex ($actualPct%)."
                }
            } else {
                ""
            }

            val note = when {
                !appliedToHardware ->
                    "Requested ${stream.label} step $targetIndex/$maxIndex ($clampedPct%), but Android kept level at $actualIndex/$maxIndex ($actualPct%)."
                minIndex > 0 ->
                    "Applied ${stream.label} step $actualIndex/$maxIndex (range $minIndex..$maxIndex, $actualPct% achievable for $clampedPct% requested)."
                !exactPctMatch ->
                    "Applied ${stream.label} nearest hardware step $actualIndex/$maxIndex ($actualPct% achievable for $clampedPct% requested)."
                else ->
                    "Applied ${stream.label}: $actualIndex/$maxIndex ($actualPct%)."
            }

            VolumeApplyResult(
                success = appliedToHardware,
                appliedExactTarget = appliedToHardware && exactPctMatch,
                stream = stream.key,
                requestedPercentage = clampedPct,
                indexBefore = indexBefore,
                targetIndex = targetIndex,
                actualIndex = actualIndex,
                actualPercentage = actualPct,
                minIndex = minIndex,
                maxIndex = maxIndex,
                dndBlocked = !appliedToHardware && !dndAccessGranted,
                fixedVolume = false,
                streamUnavailable = false,
                failureReason = failureReason,
                message = note
            )
        } catch (sec: SecurityException) {
            val actualIndex = try { am.getStreamVolume(stream.androidStream) } catch (_: Exception) { indexBefore }
            val actualPct = computeAchievablePercentage(actualIndex, minIndex, maxIndex)
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
                indexBefore = indexBefore,
                targetIndex = targetIndex,
                actualIndex = actualIndex,
                actualPercentage = actualPct,
                minIndex = minIndex,
                maxIndex = maxIndex,
                dndBlocked = !dndAccessGranted,
                fixedVolume = false,
                streamUnavailable = false,
                failureReason = detail,
                message = detail
            )
        } catch (ex: Exception) {
            val actualIndex = try { am.getStreamVolume(stream.androidStream) } catch (_: Exception) { indexBefore }
            val actualPct = computeAchievablePercentage(actualIndex, minIndex, maxIndex)
            val reason = "Android error changing ${stream.label}: ${ex.message ?: "unknown error"}"
            VolumeApplyResult(
                success = false,
                appliedExactTarget = false,
                stream = stream.key,
                requestedPercentage = clampedPct,
                indexBefore = indexBefore,
                targetIndex = targetIndex,
                actualIndex = actualIndex,
                actualPercentage = actualPct,
                minIndex = minIndex,
                maxIndex = maxIndex,
                dndBlocked = false,
                fixedVolume = false,
                streamUnavailable = false,
                failureReason = reason,
                message = reason
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
            val min = getMinVolume(am, stream.androidStream)
            val cur = am.getStreamVolume(stream.androidStream)
            val max = am.getStreamMaxVolume(stream.androidStream).coerceAtLeast(1)
            computeAchievablePercentage(cur, min, max)
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
                indexBefore = applyResult.indexBefore,
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
                failureReason = applyResult.failureReason,
                source = source,
                timestamp = System.currentTimeMillis(),
                message = applyResult.message
            )
        }

        if (stream == AudioStreamType.VOICE_CALL) {
            onPlaybackComplete?.invoke()
            val msg = if (callSessionActive) {
                "Voice Call volume set to ${applyResult.actualIndex}/${applyResult.maxIndex} (${applyResult.actualPercentage}% achievable for ${applyResult.requestedPercentage}% requested) during active $audioModeStr session."
            } else {
                "Voice Call volume index set to ${applyResult.actualIndex}/${applyResult.maxIndex} (${applyResult.actualPercentage}% achievable), but actual call loudness could not be verified because no call or communication audio session is active ($audioModeStr). Test during a real phone or VoIP call."
            }
            return StreamTestResult(
                stream = stream.key,
                requestedPercentage = applyResult.requestedPercentage,
                indexBefore = applyResult.indexBefore,
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
                failureReason = if (callSessionActive) "" else "No active call or communication audio session ($audioModeStr).",
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
            indexBefore = applyResult.indexBefore,
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
            failureReason = if (verified) "" else playbackMsg,
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
