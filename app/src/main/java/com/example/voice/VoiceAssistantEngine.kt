package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceOrbMode {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

enum class VoicePersona(
    val title: String,
    val subtitle: String,
    val basePitch: Float,
    val baseRate: Float
) {
    ALIVE_NATURAL("Живой диалог", "Естественная интонация и живые паузы", 1.04f, 1.05f),
    ENERGETIC_AI("Энергичный", "Быстрый, бодрый и выразительный темп", 1.09f, 1.15f),
    CALM_VELVET("Мягкий бархатный", "Спокойный, глубокий и тёплый тембр", 0.93f, 0.97f)
}

class VoiceAssistantEngine(
    context: Context,
    private val onFinalTranscript: (String) -> Unit
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeechText: String? =
        "Привет! Я Аура. Теперь я говорю живым голосом и могу управлять любыми приложениями на вашем телефоне."

    private val _orbMode = MutableStateFlow(VoiceOrbMode.IDLE)
    val orbMode: StateFlow<VoiceOrbMode> = _orbMode.asStateFlow()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _rmsAmplitude = MutableStateFlow(0f)
    val rmsAmplitude: StateFlow<Float> = _rmsAmplitude.asStateFlow()

    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private val _isContinuousMode = MutableStateFlow(false)
    val isContinuousMode: StateFlow<Boolean> = _isContinuousMode.asStateFlow()

    private val _persona = MutableStateFlow(VoicePersona.ALIVE_NATURAL)
    val persona: StateFlow<VoicePersona> = _persona.asStateFlow()

    private val _pitch = MutableStateFlow(VoicePersona.ALIVE_NATURAL.basePitch)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _speechRate = MutableStateFlow(VoicePersona.ALIVE_NATURAL.baseRate)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _activeVoiceLabel = MutableStateFlow("Neural RU HD")
    val activeVoiceLabel: StateFlow<String> = _activeVoiceLabel.asStateFlow()

    private val _voiceStatusMessage = MutableStateFlow("Нажмите на сферу для живого голосового диалога")
    val voiceStatusMessage: StateFlow<String> = _voiceStatusMessage.asStateFlow()

    private var russianVoices: List<Voice> = emptyList()
    private var currentVoiceIndex = 0
    private var lastQueuedUtteranceId: String? = null

    init {
        try {
            tts = TextToSpeech(appContext, this)
        } catch (_: Exception) {
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val ruLocale = Locale.forLanguageTag("ru-RU")
            val result = engine.setLanguage(ruLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.language = Locale.getDefault()
            }

            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            selectBestNaturalRussianVoice(engine)

            engine.setPitch(_pitch.value)
            engine.setSpeechRate(_speechRate.value)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _orbMode.value = VoiceOrbMode.SPEAKING
                    _voiceStatusMessage.value = "Аура отвечает живым голосом..."
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == null || utteranceId == lastQueuedUtteranceId) {
                        _orbMode.value = VoiceOrbMode.IDLE
                        if (_isContinuousMode.value) {
                            _voiceStatusMessage.value = "Живой диалог: слушаю ваш ответ..."
                            mainHandler.postDelayed({
                                if (_orbMode.value == VoiceOrbMode.IDLE && _isContinuousMode.value) {
                                    startListening()
                                }
                            }, 450L)
                        } else {
                            _voiceStatusMessage.value = "Готова к вашей следующей команде"
                        }
                    }
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

    private fun selectBestNaturalRussianVoice(engine: TextToSpeech) {
        try {
            val allVoices = engine.voices?.toList().orEmpty()
            val ruCandidates = allVoices.filter {
                it.locale.language.equals("ru", ignoreCase = true)
            }.sortedByDescending { voice ->
                var score = voice.quality * 10
                val name = voice.name.lowercase()
                if (name.contains("network") || name.contains("wavenet") || name.contains("neural")) score += 600
                if (name.contains("dfc") || name.contains("ruf") || name.contains("rud")) score += 300
                if (!voice.isNetworkConnectionRequired) score += 100
                score
            }
            russianVoices = ruCandidates
            val best = ruCandidates.firstOrNull()
            if (best != null) {
                engine.voice = best
                _activeVoiceLabel.value = formatVoiceLabel(best)
            }
        } catch (_: Exception) {
        }
    }

    private fun formatVoiceLabel(voice: Voice): String {
        val raw = voice.name.lowercase()
        val tier = if (raw.contains("network")) "Neural Cloud HD" else "Neural Local HD"
        val variant = raw.substringAfterLast("-x-").substringBefore("-").uppercase().take(4).ifBlank { "RU" }
        return "$tier ($variant)"
    }

    fun cycleRussianVoice() {
        val engine = tts ?: return
        if (russianVoices.isEmpty()) {
            selectBestNaturalRussianVoice(engine)
        }
        if (russianVoices.isNotEmpty()) {
            currentVoiceIndex = (currentVoiceIndex + 1) % russianVoices.size
            val nextVoice = russianVoices[currentVoiceIndex]
            try {
                engine.voice = nextVoice
                _activeVoiceLabel.value = formatVoiceLabel(nextVoice)
                speak("Тембр голоса изменён. Как вам такое звучание?")
            } catch (_: Exception) {
            }
        } else {
            speak("Используется основной русский голосовой движок системы.")
        }
    }

    fun setVoicePersona(newPersona: VoicePersona) {
        _persona.value = newPersona
        _pitch.value = newPersona.basePitch
        _speechRate.value = newPersona.baseRate
        tts?.setPitch(newPersona.basePitch)
        tts?.setSpeechRate(newPersona.baseRate)
        speak("Включён режим голоса: ${newPersona.title}.")
    }

    fun updatePitchAndRate(newPitch: Float, newRate: Float) {
        val cleanPitch = newPitch.coerceIn(0.75f, 1.35f)
        val cleanRate = newRate.coerceIn(0.75f, 1.45f)
        _pitch.value = cleanPitch
        _speechRate.value = cleanRate
        tts?.setPitch(cleanPitch)
        tts?.setSpeechRate(cleanRate)
    }

    fun toggleContinuousMode() {
        val next = !_isContinuousMode.value
        _isContinuousMode.value = next
        if (next) {
            speak("Режим непрерывного живого диалога активирован. После моего ответа вы можете сразу продолжать говорить.")
        } else {
            speak("Непрерывный диалог отключён.")
        }
    }

    fun toggleTtsEnabled() {
        val next = !_isTtsEnabled.value
        _isTtsEnabled.value = next
        if (!next) {
            stopSpeaking()
        } else {
            speak("Живой голос Ауры включён!")
        }
    }

    /**
     * Natural expressive speech synthesis: cleans robotic symbols, breaks text into natural
     * conversational clauses, and modulates prosody across sentences.
     */
    fun speak(text: String) {
        if (text.isBlank() || !_isTtsEnabled.value) {
            _orbMode.value = VoiceOrbMode.IDLE
            return
        }
        if (!isTtsReady) {
            pendingSpeechText = text
            return
        }
        val engine = tts ?: return
        try {
            val humanized = humanizeTextForSpeech(text)
            val clauses = splitIntoExpressiveClauses(humanized)
            if (clauses.isEmpty()) return

            _orbMode.value = VoiceOrbMode.SPEAKING
            engine.stop()

            val basePitch = _pitch.value
            val baseRate = _speechRate.value

            clauses.forEachIndexed { idx, clause ->
                val utteranceId = "aura_${UUID.randomUUID()}"
                if (idx == clauses.lastIndex) {
                    lastQueuedUtteranceId = utteranceId
                }
                // Subtle natural prosody modulation per sentence type
                val sentencePitch = when {
                    clause.endsWith("!") -> (basePitch * 1.04f).coerceAtMost(1.35f)
                    clause.endsWith("?") -> (basePitch * 1.06f).coerceAtMost(1.35f)
                    else -> basePitch
                }
                val sentenceRate = if (idx == 0 && clauses.size > 1) {
                    (baseRate * 1.03f).coerceAtMost(1.4f)
                } else {
                    baseRate
                }
                engine.setPitch(sentencePitch)
                engine.setSpeechRate(sentenceRate)

                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
                val queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(clause, queueMode, params, utteranceId)
            }
        } catch (_: Exception) {
            _orbMode.value = VoiceOrbMode.IDLE
        }
    }

    private fun humanizeTextForSpeech(raw: String): String {
        return raw
            .replace("%", " процентов")
            .replace("Wi-Fi", "Вай-Фай", ignoreCase = true)
            .replace("Bluetooth", "Блютуз", ignoreCase = true)
            .replace("YouTube", "Ютуб", ignoreCase = true)
            .replace("Telegram", "Телеграм", ignoreCase = true)
            .replace("WhatsApp", "Ватсап", ignoreCase = true)
            .replace("Aura", "Аура", ignoreCase = true)
            .replace("SMS", "СМС")
            .replace("ВКЛ", "включён")
            .replace("ВЫКЛ", "выключен")
            .replace(Regex("[•✓*_#`~]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun splitIntoExpressiveClauses(text: String): List<String> {
        return text
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
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
        _voiceStatusMessage.value = "Аура анализирует запрос и управляет приложением..."
    }

    fun startListening() {
        stopSpeaking()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _voiceStatusMessage.value = "Распознавание речи недоступно на эмуляторе — используйте быстрые чипы ниже"
            _orbMode.value = VoiceOrbMode.IDLE
            return
        }

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _orbMode.value = VoiceOrbMode.LISTENING
                            _voiceStatusMessage.value = "Слушаю вас... Говорите свободно"
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
                            _voiceStatusMessage.value = "Поняла, выполняю..."
                        }

                        override fun onError(error: Int) {
                            _rmsAmplitude.value = 0f
                            _orbMode.value = VoiceOrbMode.IDLE
                            val msg = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышала фразу — повторите или выберите быструю команду."
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Пауза в диалоге. Нажмите на сферу, чтобы продолжить."
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Разрешите доступ к микрофону для живого общения."
                                else -> "Готова к голосовой команде."
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
