package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

enum class VoiceOrbMode {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

class VoiceAssistantEngine(
    context: Context,
    private val onFinalTranscript: (String) -> Unit
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeechText: String? = "Здравствуйте! Голосовой ассистент Аура готов к управлению вашим Android."

    private val _orbMode = MutableStateFlow(VoiceOrbMode.IDLE)
    val orbMode: StateFlow<VoiceOrbMode> = _orbMode.asStateFlow()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _rmsAmplitude = MutableStateFlow(0f)
    val rmsAmplitude: StateFlow<Float> = _rmsAmplitude.asStateFlow()

    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private val _voiceStatusMessage = MutableStateFlow("Нажмите на сферу, чтобы произнести команду")
    val voiceStatusMessage: StateFlow<String> = _voiceStatusMessage.asStateFlow()

    init {
        try {
            tts = TextToSpeech(appContext, this)
        } catch (_: Exception) {
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ruLocale = Locale.forLanguageTag("ru-RU")
            val result = tts?.setLanguage(ruLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.02f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _orbMode.value = VoiceOrbMode.SPEAKING
                    _voiceStatusMessage.value = "Aura озвучивает ответ..."
                }

                override fun onDone(utteranceId: String?) {
                    _orbMode.value = VoiceOrbMode.IDLE
                    _voiceStatusMessage.value = "Готов к следующей голосовой команде"
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _orbMode.value = VoiceOrbMode.IDLE
                }
            })
            isTtsReady = true
            val queued = pendingSpeechText
            pendingSpeechText = null
            if (!queued.isNullOrBlank() && _isTtsEnabled.value) {
                speak(queued)
            }
        }
    }

    fun toggleTtsEnabled() {
        val next = !_isTtsEnabled.value
        _isTtsEnabled.value = next
        if (!next) {
            stopSpeaking()
        } else {
            speak("Голосовое озвучивание включено")
        }
    }

    fun speak(text: String) {
        if (text.isBlank() || !_isTtsEnabled.value) {
            _orbMode.value = VoiceOrbMode.IDLE
            return
        }
        if (!isTtsReady) {
            pendingSpeechText = text
            return
        }
        try {
            val utteranceId = UUID.randomUUID().toString()
            _orbMode.value = VoiceOrbMode.SPEAKING
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (_: Exception) {
            _orbMode.value = VoiceOrbMode.IDLE
        }
    }

    fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        if (_orbMode.value == VoiceOrbMode.SPEAKING) {
            _orbMode.value = VoiceOrbMode.IDLE
        }
    }

    fun setThinkingState() {
        _orbMode.value = VoiceOrbMode.THINKING
        _voiceStatusMessage.value = "Анализирую команду и управляю Android..."
    }

    fun startListening() {
        stopSpeaking()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _voiceStatusMessage.value = "Распознавание речи недоступно на эмуляторе — используйте быстрые голосовые чипы ниже"
            _orbMode.value = VoiceOrbMode.IDLE
            return
        }

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _orbMode.value = VoiceOrbMode.LISTENING
                            _voiceStatusMessage.value = "Слушаю вас... Говорите команду"
                        }

                        override fun onBeginningOfSpeech() {
                            _orbMode.value = VoiceOrbMode.LISTENING
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
                            _rmsAmplitude.value = normalized
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            _rmsAmplitude.value = 0f
                            _voiceStatusMessage.value = "Обрабатываю речь..."
                        }

                        override fun onError(error: Int) {
                            _rmsAmplitude.value = 0f
                            _orbMode.value = VoiceOrbMode.IDLE
                            val msg = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана. Попробуйте ещё раз или выберите команду."
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Время ожидания речи истекло."
                                SpeechRecognizer.ERROR_AUDIO -> "Микрофон недоступен в текущем окружении."
                                else -> "Голосовой ввод завершён. Вы также можете нажать на любую быструю фразу."
                            }
                            _voiceStatusMessage.value = msg
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val best = matches?.firstOrNull()?.trim().orEmpty()
                            if (best.isNotEmpty()) {
                                _liveTranscript.value = best
                                onFinalTranscript(best)
                            } else {
                                _orbMode.value = VoiceOrbMode.IDLE
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                                .orEmpty()
                            if (partial.isNotEmpty()) {
                                _liveTranscript.value = partial
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            _liveTranscript.value = ""
            _orbMode.value = VoiceOrbMode.LISTENING
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            _orbMode.value = VoiceOrbMode.IDLE
            _voiceStatusMessage.value = "Не удалось запустить микрофон: ${e.localizedMessage ?: "ошибка"}"
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
        _rmsAmplitude.value = 0f
        if (_orbMode.value == VoiceOrbMode.LISTENING) {
            _orbMode.value = VoiceOrbMode.IDLE
        }
    }

    fun release() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {
        }
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {
        }
    }
}
