package com.liliang.app.platform

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG_TTS = "WorkoutTts"

/**
 * Thin wrapper around Android's [TextToSpeech] for the round-timer screen.
 *
 * Calling [speak] before the engine has finished initialising silently drops
 * the request — the round timer fires its first callout right after the
 * screen mounts, so we can't block, and a missed first round-number
 * announcement is much less bad than crashing.
 *
 * Always pair construction with a [shutdown] call when the screen leaves
 * (DisposableEffect's onDispose). The engine holds a service connection that
 * leaks otherwise.
 */
class TtsEngine(context: Context) {
    private val ready = AtomicBoolean(false)
    private var engineLanguage: Int = TextToSpeech.LANG_NOT_SUPPORTED
    // lateinit because the OnInitListener references `engine` to set the
    // language. The callback fires after the TextToSpeech constructor returns
    // (no synchronous callbacks documented), so the lateinit is safe by the
    // time it's read.
    private lateinit var engine: TextToSpeech

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Default voice locale — most callouts are English ("Jab",
                // "Cross", "Round one"). The form-tracker doesn't speak
                // Chinese yet; if that comes later we'll set the language at
                // speak() time.
                engineLanguage = engine.setLanguage(Locale.US)
                ready.set(true)
            } else {
                Log.w(TAG_TTS, "TextToSpeech init failed: status=$status")
            }
        }
    }

    /**
     * Queue [text] for speech. No-op if the engine isn't ready yet, or if the
     * device doesn't have a TTS voice for the requested locale.
     *
     * [rate] adjusts the speech rate (1.0 is the engine default; 0.85 is
     * comfortably slow for move callouts). setSpeechRate is engine-global and
     * applies when an utterance is *synthesised*, not when it's queued — the
     * old set→queue→reset dance meant the rate was back to 1.0 before the
     * queued utterance ever reached the synthesiser. So: every call sets the
     * rate it wants and leaves it; the next call sets its own.
     */
    private var currentRate = 1.0f

    fun speak(text: String, rate: Float = 1.0f) {
        if (!ready.get()) return
        if (engineLanguage == TextToSpeech.LANG_MISSING_DATA || engineLanguage == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG_TTS, "TTS locale unsupported; dropping: $text")
            return
        }
        if (rate != currentRate) {
            engine.setSpeechRate(rate)
            currentRate = rate
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, text)
    }

    /**
     * Stop any in-progress utterance and clear the queue. Used when the user
     * pauses the round timer or skips ahead — without this the engine would
     * continue saying "left hook… cross…" after the round-end bell.
     */
    fun stop() {
        if (!ready.get()) return
        engine.stop()
    }

    fun shutdown() {
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
    }
}

/**
 * Tiny wrapper around [ToneGenerator] for the round-start / round-end "bell"
 * sound. ToneGenerator is part of the platform — no asset bundling, no audio
 * focus dance — and the TONE_CDMA_HIGH_L tone is a clear chime. Not a real
 * bell, but recognisable as "round signal".
 *
 * Rings on STREAM_MUSIC, the same stream TTS speaks on — a phone on vibrate/DND
 * mutes STREAM_NOTIFICATION, which used to leave the hold timer (whose whole
 * premise is eyes-off) completely silent while the callouts still spoke.
 *
 * Construction can fail on some emulator builds; callers should treat a null
 * [WorkoutBell] as "no sound, not the end of the world".
 */
class WorkoutBell {
    private val toneGenerator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, /* volume = */ 100)
    }.onFailure { Log.w(TAG_TTS, "Failed to create ToneGenerator", it) }.getOrNull()

    /** Round-start ding: a short high tone. */
    fun ringStart() {
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 350)
    }

    /** Countdown tick — a short blip for each get-ready second. */
    fun tick() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    /** Round-end ding-ding: two pulses so the end signal is distinguishable. */
    fun ringEnd() {
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 600)
    }

    fun release() {
        toneGenerator?.release()
    }
}
