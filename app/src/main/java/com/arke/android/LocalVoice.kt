package com.arke.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class LocalVoice(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onVoiceReady()
        fun onVoiceStatus(status: String)
        fun onTranscript(text: String, confidence: Float?)
        fun onVoiceFailure(message: String)
        fun onPromptFinished(expectInput: Boolean)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var synthesis: TextToSpeech? = null
    private var generation = 0L
    private var closed = false
    var hasFrenchVoice = false
        private set
    val hasLocalRecognizer: Boolean
        get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun initialize() {
        synthesis = TextToSpeech(context) { status ->
            handler.post {
                if (closed) return@post
                val engine = synthesis
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    val voice = engine.voices.orEmpty()
                        .filter {
                            it.locale.language == "fr" && !it.isNetworkConnectionRequired &&
                                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                        }
                        .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "FR" }
                            .thenBy { it.latency }.thenBy { it.name })
                        .firstOrNull()
                    if (voice != null) {
                        hasFrenchVoice = engine.setVoice(voice) == TextToSpeech.SUCCESS
                    }
                    engine.setSpeechRate(1.0f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            dispatchCompletion(utteranceId)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            dispatchError(utteranceId)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            dispatchError(utteranceId)
                        }
                    })
                }
                listener.onVoiceReady()
            }
        }
    }

    fun speak(text: String, expectInput: Boolean) {
        stop()
        if (!hasFrenchVoice || closed) {
            listener.onVoiceFailure("Aucune voix française hors ligne disponible. Configure une voix locale ou écris ta demande.")
            return
        }
        val id = "$generation:${if (expectInput) 1 else 0}"
        listener.onVoiceStatus("Arke vous répond")
        val status = synthesis?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (status != TextToSpeech.SUCCESS) {
            listener.onVoiceFailure("La voix locale est indisponible. Le texte reste affiché.")
        }
    }

    fun listen() {
        stop()
        if (closed || !hasLocalRecognizer) {
            listener.onVoiceFailure("La reconnaissance sur le téléphone est indisponible. Aucun service distant ne sera utilisé.")
            return
        }
        val activeGeneration = generation
        try {
            val current = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = current
            current.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (activeGeneration == generation) listener.onVoiceStatus("À vous de parler")
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (activeGeneration == generation) listener.onVoiceStatus("Compréhension locale…")
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    if (activeGeneration != generation || closed) return
                    disposeRecognizer()
                    listener.onVoiceFailure(when (error) {
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Le français hors ligne est indisponible. Installe les ressources vocales françaises dans les réglages du téléphone."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Le microphone n'est pas autorisé."
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Je n'ai pas compris. Appuie sur Parler pour réessayer."
                        SpeechRecognizer.ERROR_AUDIO -> "Le microphone est indisponible. Vérifie les autres applications et réessaie."
                        else -> "La reconnaissance locale s'est arrêtée. Aucun service distant ne sera utilisé."
                    })
                }
                override fun onResults(results: Bundle?) {
                    if (activeGeneration != generation || closed) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
                        ?.takeIf { it in 0f..1f }
                    disposeRecognizer()
                    if (text.isNullOrBlank()) {
                        listener.onVoiceFailure("Aucune parole comprise. Appuie sur Parler pour réessayer.")
                    } else {
                        listener.onTranscript(text, confidence)
                    }
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRANCE.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            current.startListening(intent)
        } catch (_: RuntimeException) {
            disposeRecognizer()
            listener.onVoiceFailure("Impossible de démarrer la reconnaissance locale. Vérifie le microphone et les langues hors ligne.")
        }
    }

    fun stop() {
        generation++
        synthesis?.stop()
        disposeRecognizer()
    }

    fun close() {
        closed = true
        stop()
        synthesis?.shutdown()
        synthesis = null
    }

    private fun disposeRecognizer() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun dispatchCompletion(id: String?) {
        handler.post {
            val parts = id?.split(":") ?: return@post
            if (closed || parts.firstOrNull()?.toLongOrNull() != generation) return@post
            val currentGeneration = generation
            handler.postDelayed({
                if (!closed && generation == currentGeneration) {
                    listener.onPromptFinished(parts.getOrNull(1) == "1")
                }
            }, 250)
        }
    }

    private fun dispatchError(id: String?) {
        handler.post {
            if (!closed && id?.substringBefore(":")?.toLongOrNull() == generation) {
                listener.onVoiceFailure("La lecture vocale a échoué. Aucun envoi n'a été déclenché par cet échec.")
            }
        }
    }
}
