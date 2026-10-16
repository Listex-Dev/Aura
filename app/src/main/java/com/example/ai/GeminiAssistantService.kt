package com.example.ai

import com.example.BuildConfig
import com.example.system.AuraAccessibilityService
import com.example.system.ScreenUiElement
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null
)

@Serializable
data class Content(
    val parts: List<Part> = emptyList()
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@Serializable
data class InlineData(
    val mimeType: String,
    val data: String
)

@Serializable
data class GenerationConfig(
    val responseMimeType: String? = null,
    val responseSchema: JsonObject? = null,
    val temperature: Float? = null
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList()
)

@Serializable
data class Candidate(
    val content: Content? = null
)

@Serializable
data class AndroidActionCommand(
    val actionType: String = "NONE",
    val target: String = "",
    val value: String = "",
    val x: Int = -1,
    val y: Int = -1,
    val reason: String = ""
)

@Serializable
data class AssistantPlanResponse(
    val screenContextAnalysis: String = "",
    val spokenResponse: String = "",
    val requiresFollowUpObservation: Boolean = false,
    val actions: List<AndroidActionCommand> = emptyList()
)

interface GeminiApiService {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object GeminiRetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiApiService::class.java)
    }
}

class GeminiAssistantService(private val appContext: android.content.Context? = null) {

    companion object {
        private const val PREFS_NAME = "aura_control_prefs"
        private const val KEY_CUSTOM_GEMINI_API_KEY = "custom_gemini_api_key"

        private val PREFERRED_MODELS = listOf(
            "gemini-3.5-flash",
            "gemini-flash-latest",
            "gemini-3.1-pro-preview"
        )

        fun getSavedApiKey(context: android.content.Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            return prefs.getString(KEY_CUSTOM_GEMINI_API_KEY, "")?.trim().orEmpty()
        }

        fun saveApiKey(context: android.content.Context, apiKey: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_CUSTOM_GEMINI_API_KEY, apiKey.trim()).apply()
        }
    }

    private val responseSchema: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("screenContextAnalysis") {
                put("type", "STRING")
                put(
                    "description",
                    "Concise Russian explanation of the current screen context, which UI elements/coordinates were identified, and why the target coordinates (x, y) or element #index were selected."
                )
            }
            putJsonObject("spokenResponse") {
                put("type", "STRING")
                put(
                    "description",
                    "Warm, lively, human-like Russian spoken response (1-2 natural conversational sentences) to be spoken aloud."
                )
            }
            putJsonObject("requiresFollowUpObservation") {
                put("type", "BOOLEAN")
                put(
                    "description",
                    "Set to true ONLY if the current action opens a new app, dialog, or screen and the user's goal requires inspecting the new screen afterwards to perform additional clicks."
                )
            }
            putJsonObject("actions") {
                put("type", "ARRAY")
                put(
                    "description",
                    "Ordered sequence of context-aware Android system and coordinate/UI Accessibility actions to execute."
                )
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("actionType") {
                            put("type", "STRING")
                            put(
                                "description",
                                "One of: TAP_PIXEL, CLICK_INDEX, CLICK_UI, LONG_PRESS_PIXEL, LONG_CLICK_UI, INPUT_TEXT, SWIPE_PIXEL, SCROLL_SCREEN, TAP_SCREEN, OPEN_APP, SEARCH_IN_APP, ANALYZE_SCREEN, READ_SCREEN, GLOBAL_ACTION, SHOW_OVERLAY, HIDE_OVERLAY, MEDIA_CONTROL, TORCH_ON, TORCH_OFF, SET_VOLUME, SET_BRIGHTNESS, SET_ALARM, SET_TIMER, CALL_CONTACT, SEND_SMS, OPEN_SETTINGS, CREATE_NOTE, CREATE_TASK, CREATE_REMINDER, RUN_ROUTINE, NONE"
                            )
                        }
                        putJsonObject("target") {
                            put("type", "STRING")
                            put(
                                "description",
                                "Element #index ('1', '4'), UI button text/id, app name, input field hint/#index, scroll direction (DOWN/UP/LEFT/RIGHT), or global action."
                            )
                        }
                        putJsonObject("value") {
                            put("type", "STRING")
                            put(
                                "description",
                                "Text to type, search query, volume/brightness 0-100, alarm HH:MM, timer seconds, or swipe end coordinates 'endX,endY'."
                            )
                        }
                        putJsonObject("x") {
                            put("type", "INTEGER")
                            put(
                                "description",
                                "Exact X pixel coordinate on the screen to tap/long-press/focus (e.g. 540, 960), or -1 if not applicable."
                            )
                        }
                        putJsonObject("y") {
                            put("type", "INTEGER")
                            put(
                                "description",
                                "Exact Y pixel coordinate on the screen to tap/long-press/focus (e.g. 148, 1280), or -1 if not applicable."
                            )
                        }
                        putJsonObject("reason") {
                            put("type", "STRING")
                            put(
                                "description",
                                "Short Russian explanation of what this specific action/coordinate tap does in the current screen context."
                            )
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("actionType"))
                        add(JsonPrimitive("target"))
                        add(JsonPrimitive("value"))
                        add(JsonPrimitive("x"))
                        add(JsonPrimitive("y"))
                        add(JsonPrimitive("reason"))
                    }
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("screenContextAnalysis"))
            add(JsonPrimitive("spokenResponse"))
            add(JsonPrimitive("requiresFollowUpObservation"))
            add(JsonPrimitive("actions"))
        }
    }

    fun getEffectiveApiKey(): String {
        val customKey = appContext?.let { getSavedApiKey(it) }.orEmpty()
        val buildKey = BuildConfig.GEMINI_API_KEY.trim()
        return when {
            customKey.isNotBlank() && customKey != "MY_GEMINI_API_KEY" -> customKey
            buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY" -> buildKey
            else -> ""
        }
    }

    suspend fun planCommand(
        userQuery: String,
        deviceContextSummary: String,
        installedAppNames: List<String>,
        routineNames: List<String>,
        screenshotBase64: String? = null,
        previousStepsHistory: List<String> = emptyList()
    ): AssistantPlanResponse = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey()
        if (apiKey.isBlank()) {
            return@withContext parseCommandLocally(userQuery)
        }

        val historyBlock = if (previousStepsHistory.isNotEmpty()) {
            "\nУже выполненные шаги в рамках этой задачи:\n" +
                previousStepsHistory.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n") +
                "\nПроанализируй НОВЫЙ текущий экран после этих шагов и выполни следующее точное действие (или верни пустой массив actions и requiresFollowUpObservation=false, если цель уже полностью достигнута)."
        } else {
            ""
        }

        val systemPrompt = """
            Ты — Аура (Aura Control), автономный ИИ-агент полного управления Android через сервер Gemini и службу AccessibilityService.
            Ты видишь:
            1) Полную карту элементов текущего экрана с точными пиксельными координатами `пиксели=(X, Y)`, процентными координатами `(X%, Y%)`, рамками `[left,top-right,bottom]`, номерами `[#1..#70]`, зонами экрана (`ВЕРХ-ПРАВО`, `НИЗ-НАВИГАЦИЯ`, `ЦЕНТР-КОНТЕНТ`) и ID элементов.
            2) Визуальный скриншот текущего экрана (если передан), по которому ты понимаешь контекст приложения, иконки без подписей, баннеры, диалоги, видео, чаты и кнопки.
            
            КАК ВЫБИРАТЬ КУДА ИМЕННО НАЖАТЬ ПО КОНТЕКСТУ:
            - Всегда анализируй смысл запроса пользователя и контекст открытого приложения.
            - В поле `screenContextAnalysis` кратко напиши: какое приложение/экран сейчас открыт, какой именно элемент или координаты `(X, Y)` ты выбрала и почему (например: "Открыт YouTube, вижу иконку поиска [#3] в правом верхнем углу (954, 142) — нажимаю туда").
            - Если нужный элемент есть в списке `[#index]`, используй `CLICK_INDEX` (указав `target` = номер элемента, а также `x` и `y` его пиксельных координат из карты экрана) ИЛИ `TAP_PIXEL` с точными пиксельными координатами `x` и `y`!
            - Если нужно нажать в конкретную точку экрана по визуальному контексту (например, закрыть всплывающее окно, нажать на видео/аватар/карточку/иконку по координатам), используй `TAP_PIXEL` и передай точные пиксельные координаты `x` и `y` (исходя из разрешения экрана в `Состояние смартфона`).
            - Если нужно ввести текст в поле (`INPUT_TEXT`): укажи `value` = текст для ввода, `target` = подсказка или `#номер` поля ввода, а в `x` и `y` передай координаты этого поля ввода (или кнопки поиска, которая его открывает).
            - Если ты открываешь другое приложение (`OPEN_APP`) и пользователь попросил сделать что-то внутри него (например: "Открой Telegram и нажми на первый чат" или "Открой настройки и включи..."), поставь `requiresFollowUpObservation = true`, чтобы после открытия приложения система заново считала новый экран с координатами и передала его тебе для точного клика!
            
            ДОСТУПНЫЕ ТИПЫ ДЕЙСТВИЙ (actionType):
            - TAP_PIXEL — точное нажатие по пиксельным координатам экрана (`x`: пиксель X, `y`: пиксель Y, `reason`: куда нажимаем).
            - CLICK_INDEX — нажать элемент по его номеру `#index` из списка элементов экрана (`target`: "1", "2"..., `x`: centerX, `y`: centerY, `reason`: описание).
            - CLICK_UI — нажать элемент по тексту или смыслу (`target`: текст кнопки, `x`: centerX если известен, `y`: centerY).
            - LONG_PRESS_PIXEL / LONG_CLICK_UI — долгое удержание по координатам `(x, y)` или названию элемента.
            - INPUT_TEXT — ввести текст и отправить (`target`: подсказка или `#index` поля, `value`: текст для ввода, `x`: X поля, `y`: Y поля).
            - SWIPE_PIXEL — свайп от `(x, y)` до координат в `value` ("endX,endY").
            - SCROLL_SCREEN — прокрутить экран (`target`: "DOWN", "UP", "LEFT", "RIGHT").
            - TAP_SCREEN — касание в процентах 0..100 (`target`: "50,50", `x`: 50, `y`: 50).
            - OPEN_APP — запустить приложение (`target`: название приложения).
            - SEARCH_IN_APP — поиск или действие внутри приложения (`target`: "YouTube", "Telegram", "WhatsApp", "Maps", "Google"; `value`: запрос).
            - ANALYZE_SCREEN / READ_SCREEN — глубокое сканирование или чтение экрана вслух.
            - GLOBAL_ACTION — системное действие (`target`: "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "LOCK_SCREEN", "SCREENSHOT", "SPLIT_SCREEN").
            - SHOW_OVERLAY / HIDE_OVERLAY — показать или скрыть плавающий аватар Ауры.
            - MEDIA_CONTROL (`PLAY_PAUSE`, `NEXT`, `PREVIOUS`, `PLAY`, `PAUSE`), TORCH_ON, TORCH_OFF, SET_VOLUME, SET_BRIGHTNESS, SET_ALARM, SET_TIMER, CALL_CONTACT, SEND_SMS, OPEN_SETTINGS, CREATE_NOTE, CREATE_TASK, CREATE_REMINDER, RUN_ROUTINE.
            
            Состояние смартфона и координатная карта экрана:
            $deviceContextSummary
            $historyBlock
            Установленные приложения: ${installedAppNames.take(45).joinToString(", ")}
            Сценарии: ${routineNames.joinToString(", ")}
        """.trimIndent()

        val userParts = mutableListOf<Part>()
        userParts.add(Part(text = "Команда пользователя: $userQuery"))
        if (!screenshotBase64.isNullOrBlank()) {
            userParts.add(
                Part(
                    inlineData = InlineData(
                        mimeType = "image/jpeg",
                        data = screenshotBase64
                    )
                )
            )
        }

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = userParts)),
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseSchema = responseSchema,
                temperature = 0.25f
            )
        )

        for (modelName in PREFERRED_MODELS) {
            try {
                val response = GeminiRetrofitClient.service.generateContent(
                    model = modelName,
                    apiKey = apiKey,
                    request = request
                )
                val rawJson = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!rawJson.isNullOrBlank()) {
                    return@withContext GeminiRetrofitClient.json.decodeFromString<AssistantPlanResponse>(rawJson)
                }
            } catch (_: Exception) {
                // Try next supported Gemini model
            }
        }

        parseCommandLocally(userQuery)
    }

    /**
     * Context-aware local coordinate & intent analyzer (used when offline or if no API key is configured yet).
     * Inspects live ScreenUiElements from AuraAccessibilityService to pick exact (centerX, centerY) coordinates!
     */
    fun parseCommandLocally(query: String): AssistantPlanResponse {
        val lower = query.lowercase().trim()
        val actions = mutableListOf<AndroidActionCommand>()
        val replies = mutableListOf<String>()
        val liveElements = AuraAccessibilityService.screenElements.value
        val activePkg = AuraAccessibilityService.activePackage.value.substringAfterLast('.').ifBlank { "Активный экран" }
        var contextSummary = "Контекст ($activePkg): проанализировано ${liveElements.size} UI-элементов с координатами"

        // Helper to find best matching element on current screen by keywords or zone
        fun findContextualElement(keywords: List<String>, preferEditable: Boolean = false): ScreenUiElement? {
            if (liveElements.isEmpty()) return null
            if (preferEditable) {
                val edit = liveElements.firstOrNull { it.isEditable }
                if (edit != null) return edit
            }
            return liveElements.firstOrNull { el ->
                val l = el.label.lowercase()
                val v = el.viewId.lowercase()
                (el.isClickable || el.isEditable) && keywords.any { kw -> l.contains(kw) || v.contains(kw) }
            }
        }

        // 0. Explicit coordinate click ("нажми 540 1200", "координаты 500, 800", "тыкни в 300 600")
        val coordRegex = Regex("(?:координат[а-я]*|точк[а-я]*|пиксел[а-я]*|нажми|тыкни|кликни)\\s*(?:в|на|по)?\\s*(\\d{2,4})\\s*[,;:\\s]\\s*(\\d{2,4})")
        val coordMatch = coordRegex.find(lower)
        if (coordMatch != null) {
            val x = coordMatch.groupValues[1].toIntOrNull() ?: 540
            val y = coordMatch.groupValues[2].toIntOrNull() ?: 960
            actions.add(
                AndroidActionCommand(
                    actionType = "TAP_PIXEL",
                    target = "$x,$y",
                    value = "",
                    x = x,
                    y = y,
                    reason = "Точный клик в пиксельные координаты ($x, $y)"
                )
            )
            contextSummary = "Прямое наведение по координатам экрана ($x, $y) на окне $activePkg"
            replies.add("Нажимаю точно в координаты $x на $y!")
        }

        // 0.5 Contextual AI Screen Target ("самый нужный", "главное", "по контексту", "куда надо")
        if (actions.isEmpty() && (lower.contains("по контексту") || lower.contains("куда надо") || lower.contains("самый нужный") || lower.contains("главный элемент"))) {
            val searchEl = findContextualElement(listOf("поиск", "search", "найти", "отправить", "send", "играть", "play", "смотреть", "далее", "ок"))
                ?: liveElements.firstOrNull { it.isClickable && it.yPercent in 15..85 }
                ?: liveElements.firstOrNull { it.isClickable }

            if (searchEl != null) {
                contextSummary = "В приложении $activePkg выбран целевой элемент #${searchEl.index} «${searchEl.label}» в координатах (${searchEl.centerX}, ${searchEl.centerY})"
                actions.add(
                    AndroidActionCommand(
                        actionType = "TAP_PIXEL",
                        target = searchEl.index.toString(),
                        value = searchEl.label,
                        x = searchEl.centerX,
                        y = searchEl.centerY,
                        reason = "Контекстный выбор #${searchEl.index} «${searchEl.label}» (${searchEl.centerX}, ${searchEl.centerY})"
                    )
                )
                replies.add("Поняла контекст экрана! Нажимаю на «${searchEl.label}» в координатах ${searchEl.centerX}, ${searchEl.centerY}")
            }
        }

        // 1. Floating Avatar Overlay commands
        if (lower.contains("покажи аватар") || lower.contains("включи аватар") || lower.contains("поверх всех") || lower.contains("включи оверлей")) {
            actions.add(AndroidActionCommand("SHOW_OVERLAY", "OVERLAY", "", reason = "Запуск плавающего аватара Ауры"))
            replies.add("Запускаю мой плавающий аватар поверх всех приложений!")
        } else if (lower.contains("скрой аватар") || lower.contains("убери аватар") || lower.contains("выключи оверлей")) {
            actions.add(AndroidActionCommand("HIDE_OVERLAY", "OVERLAY", "", reason = "Скрытие плавающего аватара"))
            replies.add("Скрываю плавающий аватар")
        }

        // 2. Global System & Accessibility Actions
        when {
            lower.contains("скриншот") || lower.contains("снимок экрана") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "SCREENSHOT", "", reason = "Системный скриншот"))
                replies.add("Секунду, делаю скриншот экрана!")
            }
            lower.contains("заблокир") || lower.contains("выключи экран") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "LOCK_SCREEN", "", reason = "Блокировка экрана"))
                replies.add("Блокирую экран телефона")
            }
            lower.contains("уведомлен") || lower.contains("шторк") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "NOTIFICATIONS", "", reason = "Открытие шторки уведомлений"))
                replies.add("Открываю панель уведомлений")
            }
            lower.contains("быстрые настройк") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "QUICK_SETTINGS", "", reason = "Быстрые настройки"))
                replies.add("Открываю быстрые настройки системы")
            }
            lower.contains("домой") || lower.contains("главный экран") || lower.contains("рабочий стол") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "HOME", "", reason = "Переход на главный экран"))
                replies.add("Возвращаюсь на главный экран")
            }
            lower == "назад" || lower.contains("вернись назад") || lower.contains("шаг назад") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "BACK", "", reason = "Системное действие Назад"))
                replies.add("Возвращаюсь назад")
            }
            lower.contains("недавн") || lower.contains("открытые приложен") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "RECENTS", "", reason = "Недавние приложения"))
                replies.add("Показываю список недавних приложений")
            }
            lower.contains("раздели экран") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "SPLIT_SCREEN", "", reason = "Разделение экрана"))
                replies.add("Переключаю режим разделения экрана")
            }
        }

        // 3. Screen Reading & Deep Screen Analysis
        if (lower.contains("анализ экран") || lower.contains("проанализируй экран") || lower.contains("какие кнопки") || lower.contains("какие координаты")) {
            actions.add(AndroidActionCommand("ANALYZE_SCREEN", "SCREEN", "", reason = "Сканирование координатной сетки экрана"))
            replies.add("Сканирую все кнопки и их точные координаты на текущем экране!")
        } else if (lower.contains("что на экране") || lower.contains("прочитай экран")) {
            actions.add(AndroidActionCommand("READ_SCREEN", "SCREEN", "", reason = "Чтение элементов экрана"))
            replies.add("Считываю элементы и контекст текущего экрана")
        }

        // 4. Scroll / Swipe inside apps
        if (lower.contains("пролист") || lower.contains("скролл") || lower.contains("прокрут") || lower.contains("свайп")) {
            val dir = when {
                lower.contains("вверх") || lower.contains("наверх") -> "UP"
                lower.contains("влево") -> "LEFT"
                lower.contains("вправо") -> "RIGHT"
                else -> "DOWN"
            }
            actions.add(AndroidActionCommand("SCROLL_SCREEN", dir, "", reason = "Прокрутка экрана $dir"))
            replies.add("Прокручиваю экран")
        }

        // 5. Contextual Click UI Element by voice ("нажми ...", "кликни ...", "тыкни ...", "поставь лайк", "подпишись")
        if (actions.isEmpty()) {
            if (lower.contains("поставь лайк") || lower.contains("кнопку лайк") || lower == "лайк") {
                val el = findContextualElement(listOf("нравится", "лайк", "like", "heart", "favorite"))
                if (el != null) {
                    contextSummary = "Найдена кнопка лайка #${el.index} «${el.label}» в координатах (${el.centerX}, ${el.centerY})"
                    actions.add(AndroidActionCommand("TAP_PIXEL", el.index.toString(), el.label, x = el.centerX, y = el.centerY, reason = contextSummary))
                    replies.add("Нашла кнопку лайка в координатах ${el.centerX}, ${el.centerY} — нажимаю!")
                } else {
                    actions.add(AndroidActionCommand("CLICK_UI", "лайк", "", reason = "Поиск и нажатие кнопки лайка"))
                    replies.add("Ставлю лайк!")
                }
            } else if (lower.contains("подпишись") || lower.contains("нажми подписаться")) {
                val el = findContextualElement(listOf("подписаться", "subscribe", "follow", "читать"))
                if (el != null) {
                    contextSummary = "Найдена кнопка подписки #${el.index} «${el.label}» (${el.centerX}, ${el.centerY})"
                    actions.add(AndroidActionCommand("TAP_PIXEL", el.index.toString(), el.label, x = el.centerX, y = el.centerY, reason = contextSummary))
                    replies.add("Нажимаю «${el.label}» в координатах ${el.centerX}, ${el.centerY}!")
                } else {
                    actions.add(AndroidActionCommand("CLICK_UI", "подписаться", "", reason = "Нажатие кнопки Подписаться"))
                    replies.add("Нажимаю кнопку Подписаться!")
                }
            } else if (lower.contains("поиск") && (lower.contains("нажми") || lower.contains("найди") || lower.contains("открой") || lower.contains("тыкни"))) {
                val el = findContextualElement(listOf("поиск", "search", "найти", "лупа", "искать"))
                if (el != null) {
                    contextSummary = "В приложении $activePkg найдена кнопка/поле поиска #${el.index} «${el.label}» в координатах (${el.centerX}, ${el.centerY})"
                    actions.add(AndroidActionCommand("TAP_PIXEL", el.index.toString(), el.label, x = el.centerX, y = el.centerY, reason = contextSummary))
                    replies.add("Нажимаю на поиск (#${el.index}) точно в координаты ${el.centerX}, ${el.centerY}!")
                } else {
                    actions.add(AndroidActionCommand("CLICK_UI", "поиск", "", reason = "Нажатие кнопки поиска"))
                    replies.add("Нажимаю на кнопку поиска!")
                }
            } else if (lower.contains("отправ") && (lower.contains("нажми") || lower.contains("кнопк") || lower.contains("тыкни"))) {
                val el = findContextualElement(listOf("отправить", "send", "submit", "готово", "done"))
                if (el != null) {
                    contextSummary = "Найдена кнопка отправки #${el.index} «${el.label}» в координатах (${el.centerX}, ${el.centerY})"
                    actions.add(AndroidActionCommand("TAP_PIXEL", el.index.toString(), el.label, x = el.centerX, y = el.centerY, reason = contextSummary))
                    replies.add("Нажимаю кнопку отправки в координатах ${el.centerX}, ${el.centerY}!")
                } else {
                    actions.add(AndroidActionCommand("CLICK_UI", "отправить", "", reason = "Нажатие кнопки Отправить"))
                    replies.add("Нажимаю кнопку Отправить!")
                }
            } else if (lower.contains("перв") && (lower.contains("видео") || lower.contains("результат") || lower.contains("чат") || lower.contains("трек"))) {
                val el = liveElements.firstOrNull { it.isClickable && it.yPercent in 18..85 }
                    ?: liveElements.firstOrNull { it.isClickable }
                if (el != null) {
                    contextSummary = "Выбран первый элемент контента #${el.index} «${el.label}» в координатах (${el.centerX}, ${el.centerY})"
                    actions.add(AndroidActionCommand("TAP_PIXEL", el.index.toString(), el.label, x = el.centerX, y = el.centerY, reason = contextSummary))
                    replies.add("Включаю «${el.label.take(28)}» в координатах ${el.centerX}, ${el.centerY}!")
                } else {
                    actions.add(AndroidActionCommand("CLICK_UI", "первое видео", "", reason = "Клик по первому результату"))
                    replies.add("Включаю первый результат на экране!")
                }
            } else if (lower.startsWith("зажми ") || lower.startsWith("удерживай ") || lower.contains("долгое нажатие")) {
                val target = query.replace(Regex("(?i)^(зажми|удерживай|долгое нажатие на|долгое нажатие)\\s*"), "").trim()
                if (target.isNotEmpty()) {
                    val el = findContextualElement(listOf(target.lowercase()))
                    if (el != null) {
                        actions.add(AndroidActionCommand("LONG_PRESS_PIXEL", target, el.label, x = el.centerX, y = el.centerY, reason = "Долгое нажатие #${el.index} (${el.centerX},${el.centerY})"))
                    } else {
                        actions.add(AndroidActionCommand("LONG_CLICK_UI", target, "", reason = "Долгое нажатие «$target»"))
                    }
                    replies.add("Удерживаю элемент «$target»")
                }
            } else if (lower.startsWith("нажми ") || lower.startsWith("кликни ") || lower.contains("нажми на ") || lower.startsWith("тыкни ") || lower.startsWith("выбери ")) {
                val buttonTarget = query
                    .replace(Regex("(?i)^(нажми на кнопку|нажми на|нажми кнопку|нажми|кликни на|кликни|тыкни на|тыкни в|тыкни|выбери)\\s*"), "")
                    .trim()
                if (buttonTarget.isNotEmpty()) {
                    val idxMatch = Regex("^#?(\\d{1,2})$").find(buttonTarget)
                    if (idxMatch != null) {
                        val idx = idxMatch.groupValues[1].toIntOrNull() ?: 1
                        val el = liveElements.firstOrNull { it.index == idx }
                        if (el != null) {
                            contextSummary = "Выбран элемент #$idx «${el.label}» в координатах (${el.centerX}, ${el.centerY})"
                            actions.add(AndroidActionCommand("TAP_PIXEL", idx.toString(), el.label, x = el.centerX, y = el.centerY, reason = contextSummary))
                            replies.add("Нажимаю элемент #$idx «${el.label.take(24)}» (${el.centerX}, ${el.centerY})!")
                        } else {
                            actions.add(AndroidActionCommand("CLICK_INDEX", idx.toString(), "", reason = "Нажатие элемента #$idx"))
                            replies.add("Нажимаю на элемент номер $idx!")
                        }
                    } else {
                        val matchedEl = findContextualElement(listOf(buttonTarget.lowercase()))
                        if (matchedEl != null) {
                            contextSummary = "По контексту найден элемент #${matchedEl.index} «${matchedEl.label}» в координатах (${matchedEl.centerX}, ${matchedEl.centerY})"
                            actions.add(
                                AndroidActionCommand(
                                    actionType = "TAP_PIXEL",
                                    target = matchedEl.index.toString(),
                                    value = matchedEl.label,
                                    x = matchedEl.centerX,
                                    y = matchedEl.centerY,
                                    reason = contextSummary
                                )
                            )
                            replies.add("Нашла «${matchedEl.label.take(24)}» в координатах ${matchedEl.centerX}, ${matchedEl.centerY} — нажимаю!")
                        } else {
                            actions.add(AndroidActionCommand("CLICK_UI", buttonTarget, "", reason = "Поиск и клик по «$buttonTarget»"))
                            replies.add("Нажимаю на «$buttonTarget»")
                        }
                    }
                }
            }
        }

        // 6. Type text into app ("введи текст ...", "напечатай ...")
        if (lower.startsWith("введи ") || lower.startsWith("напечатай ")) {
            val textVal = query
                .replace(Regex("(?i)^(введи текст|введи|напечатай текст|напечатай)\\s*"), "")
                .trim()
            if (textVal.isNotEmpty()) {
                val inputEl = findContextualElement(listOf("поиск", "сообщение", "введите", "search", "message"), preferEditable = true)
                val tx = inputEl?.centerX ?: -1
                val ty = inputEl?.centerY ?: -1
                if (inputEl != null) {
                    contextSummary = "Найдено поле ввода #${inputEl.index} «${inputEl.label}» в координатах ($tx, $ty)"
                }
                actions.add(AndroidActionCommand("INPUT_TEXT", inputEl?.index?.toString().orEmpty(), textVal, x = tx, y = ty, reason = "Ввод текста «$textVal»"))
                replies.add("Ввожу текст «$textVal» и отправляю")
            }
        }

        // 7. Media playback control
        if (lower.contains("пауз") || lower.contains("останови музыку") || lower.contains("останови видео")) {
            actions.add(AndroidActionCommand("MEDIA_CONTROL", "PAUSE", "", reason = "Пауза мультимедиа"))
            replies.add("Ставлю воспроизведение на паузу")
        } else if (lower.contains("следующ") && (lower.contains("трек") || lower.contains("песн") || lower.contains("видео"))) {
            actions.add(AndroidActionCommand("MEDIA_CONTROL", "NEXT", "", reason = "Следующий трек"))
            replies.add("Включаю следующий трек!")
        } else if (lower.contains("предыдущ") && (lower.contains("трек") || lower.contains("песн"))) {
            actions.add(AndroidActionCommand("MEDIA_CONTROL", "PREVIOUS", "", reason = "Предыдущий трек"))
            replies.add("Возвращаю предыдущий трек")
        } else if (lower.contains("продолжи воспроизведен") || lower.contains("включи плеер")) {
            actions.add(AndroidActionCommand("MEDIA_CONTROL", "PLAY", "", reason = "Продолжить воспроизведение"))
            replies.add("Продолжаю воспроизведение")
        }

        // 8. Cross-App Search & In-App Actions (YouTube, Telegram, WhatsApp, Google, Maps)
        if (lower.contains("ютуб") || lower.contains("youtube")) {
            val searchPart = query
                .replace(Regex("(?i)(открой|запусти|включи|найди|на|в|ютубе|ютуб|youtube|и|поищи|видео|первое)"), "")
                .trim()
            if (searchPart.isNotEmpty()) {
                actions.add(AndroidActionCommand("SEARCH_IN_APP", "YouTube", searchPart, reason = "Поиск в YouTube: $searchPart"))
                if (lower.contains("первое") || lower.contains("включи")) {
                    actions.add(AndroidActionCommand("CLICK_UI", "первое видео", "", reason = "Клик по первому видео в результатах"))
                }
                replies.add("С удовольствием! Открываю YouTube и включаю «$searchPart»")
            } else {
                actions.add(AndroidActionCommand("OPEN_APP", "YouTube", "", reason = "Запуск YouTube"))
                replies.add("Запускаю YouTube!")
            }
        } else if ((lower.contains("телеграм") || lower.contains("telegram")) && (lower.contains("напиши") || lower.contains("отправь"))) {
            val msgPart = query
                .replace(Regex("(?i)(открой|в|телеграм|телеграме|telegram|и|напиши|отправь|сообщение)"), "")
                .trim()
            actions.add(AndroidActionCommand("SEARCH_IN_APP", "Telegram", msgPart.ifBlank { "Привет!" }, reason = "Отправка в Telegram"))
            replies.add("Открываю Telegram и отправляю сообщение!")
        } else if ((lower.contains("ватсап") || lower.contains("whatsapp")) && (lower.contains("напиши") || lower.contains("отправь"))) {
            val msgPart = query
                .replace(Regex("(?i)(открой|в|ватсап|ватсапе|whatsapp|и|напиши|отправь|сообщение)"), "")
                .trim()
            actions.add(AndroidActionCommand("SEARCH_IN_APP", "WhatsApp", msgPart.ifBlank { "Привет!" }, reason = "Отправка в WhatsApp"))
            replies.add("Перехожу в WhatsApp с вашим сообщением!")
        } else if (lower.startsWith("найди ") || lower.startsWith("поищи ") || lower.contains("загугли")) {
            val searchQ = query
                .replace(Regex("(?i)^(найди в интернете|найди в гугле|загугли|найди|поищи)\\s*"), "")
                .trim()
            if (searchQ.isNotEmpty()) {
                actions.add(AndroidActionCommand("SEARCH_IN_APP", "Google", searchQ, reason = "Поиск в Google: $searchQ"))
                replies.add("Ищу для вас «$searchQ»!")
            }
        }

        // 9. Torch / Flashlight
        if (lower.contains("фонар") || lower.contains("вспышк")) {
            if (lower.contains("выкл") || lower.contains("погас") || lower.contains("откл")) {
                actions.add(AndroidActionCommand("TORCH_OFF", "FLASHLIGHT", "OFF", reason = "Выключение фонарика"))
                replies.add("Готово, выключаю фонарик")
            } else {
                actions.add(AndroidActionCommand("TORCH_ON", "FLASHLIGHT", "ON", reason = "Включение фонарика"))
                replies.add("Конечно, включаю фонарик!")
            }
        }

        // 10. Volume
        if (lower.contains("громк") || lower.contains("звук")) {
            val numberMatch = Regex("(\\d{1,3})").find(lower)
            val level = numberMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
                ?: when {
                    lower.contains("максим") || lower.contains("полн") -> 100
                    lower.contains("тиш") || lower.contains("выкл") || lower.contains("без звука") -> 0
                    lower.contains("средн") || lower.contains("полов") -> 50
                    else -> 70
                }
            val stream = if (lower.contains("звон")) "RING" else if (lower.contains("будильн")) "ALARM" else "MEDIA"
            actions.add(AndroidActionCommand("SET_VOLUME", stream, level.toString(), reason = "Громкость $level%"))
            replies.add("Ставлю громкость на $level процентов")
        }

        // 11. Brightness
        if (lower.contains("яркост") || lower.contains("экран ярч") || lower.contains("тускл")) {
            val numberMatch = Regex("(\\d{1,3})").find(lower)
            val level = numberMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(10, 100)
                ?: if (lower.contains("максим") || lower.contains("ярч")) 100 else if (lower.contains("тускл") || lower.contains("миним")) 20 else 65
            actions.add(AndroidActionCommand("SET_BRIGHTNESS", "SCREEN", level.toString(), reason = "Яркость $level%"))
            replies.add("Меняю яркость экрана на $level процентов")
        }

        // 12. Alarm
        if (lower.contains("будильн") || lower.contains("разбуд")) {
            val timeMatch = Regex("(\\d{1,2})[:.\\s](\\d{2})").find(lower)
            val hourOnlyMatch = Regex("на\\s+(\\d{1,2})\\s*(час|утр|вечер)?").find(lower)
            val timeStr = when {
                timeMatch != null -> {
                    val h = timeMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 23) ?: 7
                    val m = timeMatch.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
                    "%02d:%02d".format(h, m)
                }
                hourOnlyMatch != null -> {
                    val h = hourOnlyMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 23) ?: 7
                    "%02d:00".format(h)
                }
                else -> "07:30"
            }
            actions.add(AndroidActionCommand("SET_ALARM", "Aura Alarm", timeStr, reason = "Установка будильника $timeStr"))
            replies.add("Поставила будильник на $timeStr!")
        }

        // 13. Timer
        if (lower.contains("таймер") || lower.contains("засеки")) {
            val numMatch = Regex("(\\d+)").find(lower)
            val amount = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val seconds = if (lower.contains("сек")) amount else if (lower.contains("час")) amount * 3600 else amount * 60
            actions.add(AndroidActionCommand("SET_TIMER", "Aura Timer", seconds.toString(), reason = "Таймер на $seconds сек."))
            replies.add("Засекаю таймер на $amount ${if (lower.contains("сек")) "секунд" else "минут"}!")
        }

        // 14. Calls
        if (lower.contains("позвон") || lower.contains("набери")) {
            val target = query.replace(Regex("(?i)(позвони|позвонить|набери|набрать|контакту|по номеру)"), "").trim()
                .ifBlank { "Голосовой вызов" }
            actions.add(AndroidActionCommand("CALL_CONTACT", target, "", reason = "Звонок: $target"))
            replies.add("Сейчас наберу $target")
        }

        // 15. SMS
        if (lower.contains("смс") || (lower.contains("сообщен") && !lower.contains("телеграм") && !lower.contains("ватсап"))) {
            val cleaned = query.replace(Regex("(?i)(отправь|напиши|смс|сообщение)"), "").trim()
            val parts = cleaned.split(" ", limit = 2)
            val recipient = parts.firstOrNull()?.ifBlank { "Контакт" } ?: "Контакт"
            val body = parts.getOrNull(1) ?: "Привет! Я скоро перезвоню."
            actions.add(AndroidActionCommand("SEND_SMS", recipient, body, reason = "SMS для $recipient"))
            replies.add("Подготовила сообщение для $recipient")
        }

        // 16. Settings & Accessibility
        if (lower.contains("специальн") && lower.contains("возможност")) {
            actions.add(AndroidActionCommand("OPEN_SETTINGS", "ACCESSIBILITY", "", reason = "Открытие настроек Accessibility"))
            replies.add("Открываю настройки специальных возможностей. Включите там сервис Aura Control для полного управления телефоном!")
        } else if (lower.contains("настройк") || lower.contains("вайфай") || lower.contains("wi-fi") || lower.contains("блютуз") || lower.contains("bluetooth")) {
            val panel = when {
                lower.contains("вайфай") || lower.contains("wi-fi") || lower.contains("интернет") || lower.contains("сет") -> "WIFI"
                lower.contains("блютуз") || lower.contains("bluetooth") -> "BLUETOOTH"
                lower.contains("звук") -> "SOUND"
                lower.contains("экран") -> "DISPLAY"
                lower.contains("гео") || lower.contains("локац") || lower.contains("gps") -> "LOCATION"
                else -> "GENERAL"
            }
            actions.add(AndroidActionCommand("OPEN_SETTINGS", panel, "", reason = "Системные настройки $panel"))
            replies.add("Открываю системные настройки $panel")
        }

        // 17. Tasks / Notes / Reminders
        if (lower.contains("задач") || lower.contains("купить") || lower.contains("список дел")) {
            val taskTitle = query.replace(Regex("(?i)(добавь|создай|задачу|в список дел|нужно)"), "").trim()
                .ifBlank { query }
            actions.add(AndroidActionCommand("CREATE_TASK", taskTitle, "Создано голосовой командой", reason = "Создание задачи"))
            replies.add("Записала задачу «$taskTitle» в ваш список дел!")
        } else if (lower.contains("заметк") || lower.contains("запиши")) {
            val noteText = query.replace(Regex("(?i)(создай|добавь|заметку|запиши)"), "").trim()
                .ifBlank { query }
            actions.add(AndroidActionCommand("CREATE_NOTE", noteText.take(40), noteText, reason = "Создание заметки"))
            replies.add("Сохранила вашу заметку!")
        } else if (lower.contains("напомн")) {
            val remText = query.replace(Regex("(?i)(напомни|создай напоминание)"), "").trim()
                .ifBlank { query }
            actions.add(AndroidActionCommand("CREATE_REMINDER", remText, "Сегодня", reason = "Напоминание"))
            replies.add("Обязательно напомню: «$remText»")
        }

        // 18. Open Any App (with optional follow-up action after " и ")
        if ((lower.contains("открой") || lower.contains("запусти")) &&
            !lower.contains("настройк") &&
            !lower.contains("уведомлен") &&
            actions.isEmpty()
        ) {
            val rawTarget = query.replace(Regex("(?i)^(открой|запусти|приложение)\\s*"), "").trim()
            if (rawTarget.contains(" и ")) {
                val split = rawTarget.split(" и ", limit = 2)
                val appPart = split[0].trim()
                val secondPart = split[1].trim()
                actions.add(AndroidActionCommand("OPEN_APP", appPart, "", reason = "Запуск $appPart"))
                when {
                    secondPart.startsWith("нажми ") || secondPart.startsWith("тыкни ") -> {
                        val btn = secondPart.replace(Regex("^(нажми|тыкни)\\s*(на\\s*)?"), "").trim()
                        actions.add(AndroidActionCommand("CLICK_UI", btn, "DELAY", reason = "Контекстный клик по «$btn»"))
                    }
                    secondPart.startsWith("напиши ") || secondPart.startsWith("введи ") -> {
                        val txt = secondPart.replace(Regex("^(напиши|введи)\\s*"), "").trim()
                        actions.add(AndroidActionCommand("INPUT_TEXT", "", txt, reason = "Ввод текста «$txt»"))
                    }
                    secondPart.startsWith("найди ") -> {
                        val q = secondPart.removePrefix("найди ").trim()
                        actions.add(AndroidActionCommand("SEARCH_IN_APP", appPart, q, reason = "Поиск «$q» в $appPart"))
                    }
                    secondPart.contains("пролист") || secondPart.contains("скролл") -> {
                        actions.add(AndroidActionCommand("SCROLL_SCREEN", "DOWN", "DELAY", reason = "Прокрутка ленты"))
                    }
                }
                replies.add("Открываю $appPart и выполняю действие по контексту экрана!")
            } else if (rawTarget.isNotEmpty()) {
                actions.add(AndroidActionCommand("OPEN_APP", rawTarget, "", reason = "Запуск $rawTarget"))
                replies.add("С радостью! Запускаю $rawTarget.")
            }
        }

        if (actions.isEmpty()) {
            return AssistantPlanResponse(
                screenContextAnalysis = contextSummary,
                spokenResponse = "Я проанализировала экран ($activePkg, элементов: ${liveElements.size}). Скажите, куда именно нажать, что найти или какой текст ввести!",
                requiresFollowUpObservation = false,
                actions = emptyList()
            )
        }

        return AssistantPlanResponse(
            screenContextAnalysis = contextSummary,
            spokenResponse = replies.joinToString(". ") + ".",
            requiresFollowUpObservation = false,
            actions = actions
        )
    }
}
