package com.maptechgh.adminalerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Plays the chosen ringtone for an alert — owned by the app rather than
 * the notification channel, so each alert type can have its own sound,
 * volume, duration (once / 15s / 30s / 1min / until answered) and can
 * ring through silent mode on the alarm stream when the admin wants it.
 */
object AlertPlayer {
    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var restoreAlarmVolume = -1
    private var appCtx: Context? = null
    private val stopTask = Runnable { appCtx?.let { stop(it) } }

    @Volatile var ringing = false
        private set

    fun toneUri(c: Context, tone: String): Uri? = when {
        tone == "silent" -> null
        tone == "default" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        tone.startsWith("builtin:") -> Uri.parse("android.resource://${c.packageName}/raw/${tone.removePrefix("builtin:")}")
        else -> Uri.parse(tone)
    }

    /** Ring for an alert type using that type's saved settings. */
    fun play(c: Context, type: AlertType, preview: Boolean = false) {
        val ring = if (preview) 0 else Prefs.typeRing(c, type)
        val vib = !preview && Prefs.typeVibrate(c, type)
        playTone(c, Prefs.typeTone(c, type), ring, vib)
    }

    /** Preview any tone once (used by the tone picker). */
    fun preview(c: Context, tone: String) = playTone(c, tone, 0, false)

    private fun playTone(c: Context, tone: String, ringMode: Int, vibrate: Boolean) {
        h.post {
            val ctx = c.applicationContext
            stop(ctx)
            appCtx = ctx
            val seconds = RING_SECONDS[ringMode.coerceIn(0, RING_SECONDS.size - 1)]
            val loop = seconds > 0
            val onSilent = Prefs.ringOnSilent(ctx)
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Respect the phone's silent/vibrate switch unless "ring even on silent" is on.
            val silentNow = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
            val playSound = tone != "silent" && (onSilent || !silentNow)

            if (playSound) {
                if (onSilent && Prefs.forceLoud(ctx)) {
                    try {
                        restoreAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                    } catch (e: Exception) { restoreAlarmVolume = -1 }
                }
                val attrs = AudioAttributes.Builder()
                    .setUsage(if (onSilent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val candidates = listOfNotNull(
                    toneUri(ctx, tone),
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    Uri.parse("android.resource://${ctx.packageName}/raw/tone_alarm"),
                )
                val vol = (Prefs.volume(ctx).coerceIn(5, 100)) / 100f
                for (uri in candidates) {
                    try {
                        val mp = MediaPlayer()
                        mp.setAudioAttributes(attrs)
                        mp.setDataSource(ctx, uri)
                        mp.isLooping = loop
                        mp.setVolume(vol, vol)
                        mp.setOnCompletionListener { if (!loop) stop(ctx) }
                        mp.prepare()
                        mp.start()
                        player = mp
                        break
                    } catch (e: Exception) {
                        // try the next fallback sound
                    }
                }
            }

            if (vibrate) {
                try {
                    @Suppress("DEPRECATION")
                    val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    val pattern = longArrayOf(0, 700, 400, 700, 400)
                    v.vibrate(VibrationEffect.createWaveform(pattern, if (loop) 0 else -1))
                    vibrator = v
                } catch (e: Exception) { }
            }

            ringing = player != null || vibrator != null
            if (ringing) {
                if (loop) h.postDelayed(stopTask, seconds * 1000L)
                else if (player == null) h.postDelayed(stopTask, 2500L) // vibration only
            }
        }
    }

    fun stop(c: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { h.post { stop(c) }; return }
        h.removeCallbacks(stopTask)
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        try { vibrator?.cancel() } catch (e: Exception) { }
        vibrator = null
        if (restoreAlarmVolume >= 0) {
            try {
                val audio = c.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.setStreamVolume(AudioManager.STREAM_ALARM, restoreAlarmVolume, 0)
            } catch (e: Exception) { }
            restoreAlarmVolume = -1
        }
        ringing = false
    }
}
