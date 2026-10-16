package com.example.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AndroidActionCommand
import com.example.ai.GeminiAssistantService
import com.example.data.model.AutomationRoutine
import com.example.data.model.CommandLog
import com.example.data.model.SmartItem
import com.example.data.repository.AssistantRepository
import com.example.system.AndroidSystemController
import com.example.system.DeviceTelemetryState
import com.example.voice.VoiceAssistantEngine
import com.example.voice.VoiceOrbMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class LastExecutionCard(
    val userQuery: String,
    val assistantReply: String,
    val actionBadges: List<String>
)

class AssistantViewModel(
    private val repository: AssistantRepository,
    private val currentUserId: String,
    appContext: Context
) : ViewModel() {

    private val geminiService = GeminiAssistantService(appContext)
    val systemController = AndroidSystemController(appContext)
    val voiceEngine = VoiceAssistantEngine(appContext) { finalSpokenText ->
        executeVoiceOrTextCommand(finalSpokenText, activity = null)
    }

    val telemetry: StateFlow<DeviceTelemetryState> = systemController.telemetry
    val orbMode: StateFlow<VoiceOrbMode> = voiceEngine.orbMode
    val liveTranscript: StateFlow<String> = voiceEngine.liveTranscript
    val rmsAmplitude: StateFlow<Float> = voiceEngine.rmsAmplitude
    val isTtsEnabled: StateFlow<Boolean> = voiceEngine.isTtsEnabled
    val voiceStatusMessage: StateFlow<String> = voiceEngine.voiceStatusMessage

    private val _lastExecution = MutableStateFlow(
        LastExecutionCard(
            userQuery = "Привет, Aura!",
            assistantReply = "Здравствуйте! Я ваш голосовой ИИ-ассистент для управления Android. Нажмите на сферу микрофона или выберите команду ниже — я выполню действие и озвучу результат.",
            actionBadges = listOf("ГОЛОСОВОЙ РЕЖИМ АКТИВЕН", "ОБЛАКО ПОДКЛЮЧЕНО")
        )
    )
    val lastExecution: StateFlow<LastExecutionCard> = _lastExecution.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    init {
        com.example.system.AuraOverlayManager.onMicCommandRequested = {
            voiceEngine.toggleListening()
        }
        com.example.system.AuraOverlayManager.onQuickCommandRequested = { cmd ->
            executeVoiceOrTextCommand(cmd, activity = null)
        }
        com.example.system.AuraOverlayManager.showOverlay(appContext)
    }

    val smartItemsState: StateFlow<UiState<List<SmartItem>>> = repository.observeSmartItems(currentUserId)
        .map<List<SmartItem>, UiState<List<SmartItem>>> { UiState.Success(it) }
        .catch { error ->
            Log.w("AssistantVM", "Error observing smart items", error)
            emit(UiState.Error(error.localizedMessage ?: "Не удалось загрузить заметки и задачи"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    val routinesState: StateFlow<UiState<List<AutomationRoutine>>> = repository.observeRoutines(currentUserId)
        .map<List<AutomationRoutine>, UiState<List<AutomationRoutine>>> { UiState.Success(it) }
        .catch { error ->
            Log.w("AssistantVM", "Error observing routines", error)
            emit(UiState.Error(error.localizedMessage ?: "Не удалось загрузить сценарии"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    val historyState: StateFlow<UiState<List<CommandLog>>> = repository.observeHistory(currentUserId)
        .map<List<CommandLog>, UiState<List<CommandLog>>> { UiState.Success(it) }
        .catch { error ->
            Log.w("AssistantVM", "Error observing command history", error)
            emit(UiState.Error(error.localizedMessage ?: "Не удалось загрузить историю команд"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun executeVoiceOrTextCommand(query: String, activity: Activity? = null) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return

        viewModelScope.launch {
            voiceEngine.setThinkingState()
            com.example.system.AuraOverlayManager.updateStatus(
                com.example.system.AuraOverlayVisualState.ANALYZING,
                "Анализирую: «${cleanQuery.take(28)}»"
            )

            // Refresh screen inspection before planning so AI sees the latest buttons & inputs
            com.example.system.AuraAccessibilityService.instance.value?.inspectCurrentScreen(silentOverlayStatus = true)

            // First check if query matches a saved routine trigger phrase
            val currentRoutines = (routinesState.value as? UiState.Success)?.data.orEmpty()
            val matchedRoutine = currentRoutines.firstOrNull {
                cleanQuery.lowercase().contains(it.triggerPhrase.lowercase()) ||
                    cleanQuery.lowercase() == it.title.lowercase()
            }

            if (matchedRoutine != null) {
                runAutomationRoutine(matchedRoutine, activity)
                return@launch
            }

            val appNames = telemetry.value.installedApps.map { it.label }
            val routineNames = currentRoutines.map { "${it.title} (${it.triggerPhrase})" }
            val plan = geminiService.planCommand(
                userQuery = cleanQuery,
                deviceContextSummary = systemController.buildDeviceSummaryForAi(),
                installedAppNames = appNames,
                routineNames = routineNames
            )

            val executedBadges = mutableListOf<String>()
            var launchedAppInChain = false
            for (action in plan.actions) {
                if (action.actionType.equals("OPEN_APP", ignoreCase = true) ||
                    action.actionType.equals("SEARCH_IN_APP", ignoreCase = true)
                ) {
                    launchedAppInChain = true
                }
                val badge = executeSingleAndroidAction(action, activity, delayForCrossApp = launchedAppInChain)
                if (badge.isNotBlank()) {
                    executedBadges.add(badge)
                }
            }

            val spoken = plan.spokenResponse.ifBlank {
                if (executedBadges.isNotEmpty()) {
                    "Готово! ${executedBadges.joinToString(", ")}"
                } else {
                    "Команда выполнена!"
                }
            }

            _lastExecution.value = LastExecutionCard(
                userQuery = cleanQuery,
                assistantReply = spoken,
                actionBadges = executedBadges.ifEmpty { listOf("ЖИВОЙ ОТВЕТ AURA") }
            )

            voiceEngine.speak(spoken)
            repository.addCommandLog(
                userQuery = cleanQuery,
                assistantReply = spoken,
                executedActions = executedBadges
            )
        }
    }

    private suspend fun executeSingleAndroidAction(
        action: AndroidActionCommand,
        activity: Activity?,
        delayForCrossApp: Boolean = false
    ): String {
        val crossAppDelay = if (delayForCrossApp) 1500L else 0L
        com.example.system.AuraOverlayManager.updateStatus(
            com.example.system.AuraOverlayVisualState.ACTING,
            "Действие: ${action.actionType} ${action.target.take(18)}"
        )
        return when (action.actionType.uppercase()) {
            "TORCH_ON" -> systemController.setTorch(true)
            "TORCH_OFF" -> systemController.setTorch(false)
            "SET_VOLUME" -> {
                val pct = action.value.filter { it.isDigit() }.toIntOrNull() ?: 70
                systemController.setVolume(action.target.ifBlank { "MEDIA" }, pct)
            }
            "SET_BRIGHTNESS" -> {
                val pct = action.value.filter { it.isDigit() }.toIntOrNull() ?: 75
                systemController.setBrightness(activity, pct)
            }
            "SET_ALARM" -> {
                systemController.setAlarm(action.target.ifBlank { "Будильник Aura" }, action.value.ifBlank { "07:30" })
            }
            "SET_TIMER" -> {
                val secs = action.value.filter { it.isDigit() }.toIntOrNull() ?: 300
                systemController.setTimer(action.target.ifBlank { "Таймер Aura" }, secs)
            }
            "OPEN_APP" -> {
                systemController.openAppByQuery(action.target.ifBlank { action.value })
            }
            "SEARCH_IN_APP" -> {
                systemController.searchInAppOrWeb(
                    appNameOrTarget = action.target.ifBlank { "Google" },
                    searchQuery = action.value.ifBlank { action.target }
                )
            }
            "ANALYZE_SCREEN" -> {
                systemController.inspectCurrentScreenElements()
            }
            "CLICK_UI" -> {
                systemController.clickUiElementInApp(
                    targetText = action.target.ifBlank { action.value },
                    delayMs = crossAppDelay
                )
            }
            "CLICK_INDEX" -> {
                val idx = (action.target.ifBlank { action.value }).filter { it.isDigit() }.toIntOrNull() ?: 1
                systemController.clickElementByIndex(idx, delayMs = crossAppDelay)
            }
            "LONG_CLICK_UI" -> {
                systemController.longClickUiElementInApp(
                    targetText = action.target.ifBlank { action.value },
                    delayMs = crossAppDelay
                )
            }
            "SHOW_OVERLAY" -> {
                systemController.showFloatingAvatarOverlay()
            }
            "HIDE_OVERLAY" -> {
                systemController.hideFloatingAvatarOverlay()
            }
            "INPUT_TEXT" -> {
                systemController.inputTextInApp(
                    fieldHint = action.target,
                    textToType = action.value.ifBlank { action.target },
                    delayMs = crossAppDelay
                )
            }
            "SCROLL_SCREEN" -> {
                systemController.scrollInApp(
                    direction = action.target.ifBlank { "DOWN" },
                    delayMs = crossAppDelay
                )
            }
            "TAP_SCREEN" -> {
                val coords = (action.target.ifBlank { action.value }).split(",")
                val x = coords.getOrNull(0)?.trim()?.toFloatOrNull() ?: 50f
                val y = coords.getOrNull(1)?.trim()?.toFloatOrNull() ?: 50f
                systemController.tapScreenPercent(x, y, delayMs = crossAppDelay)
            }
            "GLOBAL_ACTION" -> {
                systemController.executeGlobalAccessibilityAction(action.target.ifBlank { action.value })
            }
            "READ_SCREEN" -> {
                systemController.readCurrentScreenContent()
            }
            "MEDIA_CONTROL" -> {
                systemController.controlMediaPlayback(action.target.ifBlank { action.value })
            }
            "CALL_CONTACT" -> {
                systemController.callContactOrNumber(action.target.ifBlank { action.value })
            }
            "SEND_SMS" -> {
                systemController.sendSmsToTarget(action.target, action.value)
            }
            "OPEN_SETTINGS" -> {
                systemController.openSystemSettings(action.target.ifBlank { "GENERAL" })
            }
            "CREATE_NOTE" -> {
                repository.createSmartItem(
                    title = action.target.ifBlank { "Голосовая заметка" },
                    content = action.value,
                    category = "NOTE"
                )
                "Заметка: ${action.target.ifBlank { action.value }.take(28)}"
            }
            "CREATE_TASK" -> {
                repository.createSmartItem(
                    title = action.target.ifBlank { action.value.ifBlank { "Новая задача" } },
                    content = action.value,
                    category = "TASK"
                )
                "Задача: ${action.target.ifBlank { action.value }.take(28)}"
            }
            "CREATE_REMINDER" -> {
                repository.createSmartItem(
                    title = action.target.ifBlank { "Напоминание" },
                    content = action.value,
                    category = "REMINDER",
                    scheduledTime = action.value.take(32)
                )
                "Напоминание: ${action.target.take(28)}"
            }
            "RUN_ROUTINE" -> {
                val routines = (routinesState.value as? UiState.Success)?.data.orEmpty()
                val found = routines.firstOrNull {
                    it.title.contains(action.target, ignoreCase = true) ||
                        it.triggerPhrase.contains(action.target, ignoreCase = true)
                }
                if (found != null) {
                    runAutomationRoutine(found, activity)
                    "Сценарий: ${found.title}"
                } else {
                    "Сценарий «${action.target}» не найден"
                }
            }
            else -> ""
        }
    }

    fun runAutomationRoutine(routine: AutomationRoutine, activity: Activity? = null) {
        viewModelScope.launch {
            val badges = mutableListOf<String>()
            var customSpeech: String? = null
            for (step in routine.actionSteps) {
                val parts = step.split(":", limit = 2)
                val key = parts.firstOrNull()?.uppercase().orEmpty()
                val param = parts.getOrNull(1).orEmpty()
                when (key) {
                    "TORCH" -> badges.add(systemController.setTorch(param.equals("ON", ignoreCase = true)))
                    "VOLUME" -> badges.add(systemController.setVolume("MEDIA", param.toIntOrNull() ?: 50))
                    "BRIGHTNESS" -> badges.add(systemController.setBrightness(activity, param.toIntOrNull() ?: 70))
                    "ALARM" -> badges.add(systemController.setAlarm(routine.title, param.ifBlank { "07:30" }))
                    "TIMER" -> badges.add(systemController.setTimer(routine.title, param.toIntOrNull() ?: 600))
                    "SETTINGS" -> badges.add(systemController.openSystemSettings(param))
                    "OPEN_APP" -> badges.add(systemController.openAppByQuery(param))
                    "CLICK" -> badges.add(systemController.clickUiElementInApp(param, delayMs = 1200L))
                    "INPUT" -> badges.add(systemController.inputTextInApp("", param, delayMs = 1200L))
                    "SCROLL" -> badges.add(systemController.scrollInApp(param.ifBlank { "DOWN" }, delayMs = 800L))
                    "GLOBAL" -> badges.add(systemController.executeGlobalAccessibilityAction(param))
                    "TASK" -> {
                        repository.createSmartItem(param.ifBlank { routine.title }, "Создано макросом", "TASK")
                        badges.add("Задача: $param")
                    }
                    "SPEAK" -> customSpeech = param
                }
            }
            val reply = customSpeech ?: "Сценарий «${routine.title}» выполнен: ${badges.joinToString(", ")}"
            _lastExecution.value = LastExecutionCard(
                userQuery = "Сценарий: ${routine.triggerPhrase}",
                assistantReply = reply,
                actionBadges = badges.ifEmpty { listOf("МАКРОС ВЫПОЛНЕН") }
            )
            voiceEngine.speak(reply)
            repository.addCommandLog(
                userQuery = "Макрос: ${routine.title}",
                assistantReply = reply,
                executedActions = badges
            )
            _statusBanner.value = "Сценарий «${routine.title}» выполнен"
        }
    }

    fun addSmartItem(title: String, content: String, category: String, scheduledTime: String) {
        viewModelScope.launch {
            val res = repository.createSmartItem(title, content, category, scheduledTime)
            if (res.isSuccess) {
                val spoken = when (category) {
                    "TASK" -> "Задача «$title» добавлена"
                    "REMINDER" -> "Напоминание «$title» сохранено"
                    else -> "Заметка «$title» сохранена"
                }
                voiceEngine.speak(spoken)
                _statusBanner.value = spoken
            }
        }
    }

    fun toggleSmartItem(item: SmartItem) {
        viewModelScope.launch {
            repository.toggleSmartItemCompleted(item)
        }
    }

    fun deleteSmartItem(itemId: String) {
        viewModelScope.launch {
            repository.deleteSmartItem(itemId)
        }
    }

    fun createRoutine(title: String, triggerPhrase: String, iconName: String, steps: List<String>) {
        viewModelScope.launch {
            val res = repository.createRoutine(title, triggerPhrase, iconName, steps)
            if (res.isSuccess) {
                val msg = "Сценарий «$title» сохранён в облаке"
                voiceEngine.speak(msg)
                _statusBanner.value = msg
            }
        }
    }

    fun seedStarterRoutinesIfNeeded() {
        viewModelScope.launch {
            repository.createRoutine(
                title = "Доброе утро",
                triggerPhrase = "Доброе утро",
                iconName = "wb_sunny",
                actionSteps = listOf(
                    "TORCH:OFF",
                    "VOLUME:65",
                    "BRIGHTNESS:85",
                    "SPEAK:Доброе утро! Яркость и звук настроены для начала дня."
                )
            )
            repository.createRoutine(
                title = "Глубокий фокус",
                triggerPhrase = "Режим фокуса",
                iconName = "bolt",
                actionSteps = listOf(
                    "VOLUME:15",
                    "TIMER:1500",
                    "TASK:Сессия глубокой концентрации 25 минут",
                    "SPEAK:Режим фокуса активирован. Звук приглушён, таймер на 25 минут запущен."
                )
            )
            repository.createRoutine(
                title = "В дороге / За рулём",
                triggerPhrase = "Я за рулём",
                iconName = "directions_car",
                actionSteps = listOf(
                    "VOLUME:90",
                    "BRIGHTNESS:100",
                    "SETTINGS:BLUETOOTH",
                    "SPEAK:Режим в дороге включён. Громкость и яркость на максимуме, открываю Bluetooth."
                )
            )
            _statusBanner.value = "Готовые голосовые макросы добавлены"
        }
    }

    fun deleteRoutine(routineId: String) {
        viewModelScope.launch {
            repository.deleteRoutine(routineId)
        }
    }

    fun deleteHistoryLog(logId: String) {
        viewModelScope.launch {
            repository.deleteCommandLog(logId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceEngine.release()
        systemController.release()
    }
}
