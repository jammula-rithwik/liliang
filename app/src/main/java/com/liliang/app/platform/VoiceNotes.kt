package com.liliang.app.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

private const val TAG_VOICE = "VoiceNotes"

/**
 * Dictation for gym notes (R20): one utterance in, text out, through the
 * phone's own speech recogniser. The on-device model is preferred where the
 * phone has one, so a note works in a gym with no signal and the audio never
 * leaves the phone. No audio is kept: the words are the note, because the
 * words are what the Sunday review reads.
 *
 * SpeechRecognizer's rule applies: create, call and destroy on the main
 * thread. One instance per open note sheet; [release] it on dispose.
 */
class VoiceNoteRecognizer(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    /** Is there any recogniser on this phone at all? False → offer typing. */
    fun isAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) || onDeviceAvailable()

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /**
     * Start listening. [onPartial] streams the words as they're recognised,
     * [onFinal] delivers the finished utterance once, [onError] says why in
     * words meant for the gym floor.
     */
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) = begin(preferOnDevice = true, onPartial, onFinal, onError)

    private fun begin(
        preferOnDevice: Boolean,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        release()
        val onDevice = preferOnDevice && onDeviceAvailable()
        val created = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.onFailure { Log.w(TAG_VOICE, "Could not create a recogniser", it) }.getOrNull()
        if (created == null) {
            onError("No speech recogniser on this phone")
            return
        }
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                // The on-device model can lack this language's pack; the
                // phone's ordinary recogniser usually has it. One retry.
                if (onDevice && error in ON_DEVICE_FALLBACK) {
                    Log.i(TAG_VOICE, "On-device recogniser failed ($error); trying the standard one")
                    begin(preferOnDevice = false, onPartial, onFinal, onError)
                    return
                }
                onError(describe(error))
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (text.isEmpty()) onError("Didn't catch that") else onFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onPartial(text)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // A thought said between breaths shouldn't be cut off at the first
            // pause. Recognisers treat these as hints; some ignore them.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        runCatching { created.startListening(intent) }.onFailure {
            Log.w(TAG_VOICE, "startListening failed", it)
            release()
            onError("Couldn't start listening")
        }
    }

    /** Stop listening and deliver what was heard so far through onFinal. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    // The three API-31 error codes are plain ints inlined at compile time: an
    // older phone never emits them, so matching on them there is harmless.
    @SuppressLint("InlinedApi")
    private companion object {
        val ON_DEVICE_FALLBACK = setOf(
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )

        fun describe(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The microphone is off for Lìliàng"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs a signal here — type it instead"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy — try again"
            SpeechRecognizer.ERROR_AUDIO -> "The microphone didn't answer"
            else -> "Speech recognition failed ($error)"
        }
    }
}
