package com.example.ai

import com.example.BuildConfig
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
import retrofit2.http.Query

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null
)

@Serializable
data class Content(
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null
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
    val value: String = ""
)

@Serializable
data class AssistantPlanResponse(
    val spokenResponse: String = "",
    val actions: List<AndroidActionCommand> = emptyList()
)

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
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
            putJsonObject("spokenResponse") {
                put("type", "STRING")
                put(
                    "description",
                    "Warm, lively, human-like Russian spoken response (1-2 natural conversational sentences) to be spoken aloud."
                )
            }
            putJsonObject("actions") {
                put("type", "ARRAY")
                put(
                    "description",
                    "Ordered sequence of Android system and in-app Accessibility actions to execute."
                )
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("actionType") {
                            put("type", "STRING")
                            put(
                                "description",
                                "One of: TORCH_ON, TORCH_OFF, SET_VOLUME, SET_BRIGHTNESS, SET_ALARM, SET_TIMER, OPEN_APP, SEARCH_IN_APP, ANALYZE_SCREEN, CLICK_UI, CLICK_INDEX, LONG_CLICK_UI, INPUT_TEXT, SCROLL_SCREEN, TAP_SCREEN, GLOBAL_ACTION, READ_SCREEN, SHOW_OVERLAY, HIDE_OVERLAY, MEDIA_CONTROL, CALL_CONTACT, SEND_SMS, OPEN_SETTINGS, CREATE_NOTE, CREATE_TASK, CREATE_REMINDER, RUN_ROUTINE, NONE"
                            )
                        }
                        putJsonObject("target") {
                            put("type", "STRING")
                            put(
                                "description",
                                "Target app name, UI button text to click, UI element index ('1', '2', '#3'), input field hint, scroll direction (DOWN/UP/LEFT/RIGHT), global action (BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS/LOCK_SCREEN/SCREENSHOT/SPLIT_SCREEN), media command (PLAY_PAUSE/NEXT/PREVIOUS), settings section, or note/task title."
                            )
                        }
                        putJsonObject("value") {
                            put("type", "STRING")
                            put(
                                "description",
                                "Action parameter: search query, text to type into app field, volume/brightness 0-100, alarm HH:MM, timer seconds, coordinates '50,50', or SMS/note text."
                            )
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("actionType"))
                        add(JsonPrimitive("target"))
                        add(JsonPrimitive("value"))
                    }
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("spokenResponse"))
            add(JsonPrimitive("actions"))
        }
    }

    suspend fun planCommand(
        userQuery: String,
        deviceContextSummary: String,
        installedAppNames: List<String>,
        routineNames: List<String>
    ): AssistantPlanResponse = withContext(Dispatchers.IO) {
        val customKey = appContext?.let { getSavedApiKey(it) }.orEmpty()
        val buildKey = BuildConfig.GEMINI_API_KEY.trim()
        val apiKey = when {
            customKey.isNotBlank() && customKey != "MY_GEMINI_API_KEY" -> customKey
            buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY" -> buildKey
            else -> ""
        }
        if (apiKey.isBlank()) {
            return@withContext parseCommandLocally(userQuery)
        }

        val systemPrompt = """
            Ты — Аура (Aura Control), живой, эмоциональный и умный голосовой ИИ-ассистент с полным управлением Android через AccessibilityService и плавающим аватаром поверх всех окон.
            Твой голос звучит тепло, живо и естественно, как у настоящего человека-помощника (без канцеляризмов и роботизированных фраз).
            В поле spokenResponse пиши живой разговорный ответ на русском языке (1-2 коротких предложения с естественной интонацией).
            
            Ты видишь структуру текущего экрана (список кнопок [#1], [#2], полей ввода и координат в "Состояние смартфона") и можешь выполнять ЛЮБЫЕ действия в любых приложениях, комбинируя шаги в массиве actions:
            1. OPEN_APP — запустить любое приложение (target: название приложения).
            2. ANALYZE_SCREEN — проанализировать все кнопки, поля и элементы на текущем экране (target: "SCREEN", value: "").
            3. CLICK_UI — нажать на любую кнопку, вкладку, иконку или элемент на экране по тексту/смыслу (target: текст элемента, например "Поиск", "Отправить", "Лайк", "Подписаться", "первое видео", "первый чат").
            4. CLICK_INDEX — нажать элемент по его номеру из анализа экрана (target: номер элемента, например "1", "2", "3").
            5. LONG_CLICK_UI — долгое нажатие (удержание) на элементе (target: текст или название элемента).
            6. INPUT_TEXT — ввести текст в поле ввода любого открытого приложения и отправить (target: подсказка поля или пусто "", value: текст для ввода).
            7. SEARCH_IN_APP — найти или открыть что-то внутри приложения или браузера (target: "YouTube", "Telegram", "WhatsApp", "Maps", "Google" или имя приложения; value: поисковый запрос или сообщение).
            8. SCROLL_SCREEN — прокрутить или свайпнуть экран в любом приложении (target: "DOWN", "UP", "LEFT", "RIGHT").
            9. TAP_SCREEN — коснуться экрана по координатам в процентах (target: "50,50", value: "").
            10. GLOBAL_ACTION — выполнить глобальное системное действие Android (target: "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "LOCK_SCREEN", "SCREENSHOT", "SPLIT_SCREEN").
            11. READ_SCREEN — прочитать вслух содержимое и кнопки текущего экрана (target: "SCREEN", value: "").
            12. SHOW_OVERLAY / HIDE_OVERLAY — показать или скрыть плавающий аватар Ауры поверх всех приложений.
            13. MEDIA_CONTROL — управление музыкой/видео в любом плеере (target: "PLAY_PAUSE", "NEXT", "PREVIOUS", "PLAY", "PAUSE").
            14. TORCH_ON / TORCH_OFF — включить/выключить фонарик.
            15. SET_VOLUME — изменить громкость (target: "MEDIA", "RING", "ALARM"; value: "0".."100").
            16. SET_BRIGHTNESS — изменить яркость (target: "SCREEN", value: "10".."100").
            17. SET_ALARM / SET_TIMER / CALL_CONTACT / SEND_SMS / OPEN_SETTINGS / CREATE_NOTE / CREATE_TASK / CREATE_REMINDER / RUN_ROUTINE.
            
            Если пользователь просит составное действие (например: "Открой YouTube, найди музыку и включи первое видео" или "Поставь лайк и пролистай дальше"), возвращай всю цепочку действий в массиве actions!
            
            Состояние смартфона и элементы экрана: $deviceContextSummary
            Установленные приложения: ${installedAppNames.take(45).joinToString(", ")}
            Сценарии: ${routineNames.joinToString(", ")}
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = userQuery)))),
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseSchema = responseSchema,
                temperature = 0.35f
            )
        )

        try {
            val response = GeminiRetrofitClient.service.generateContent(apiKey, request)
            val rawJson = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (!rawJson.isNullOrBlank()) {
                GeminiRetrofitClient.json.decodeFromString<AssistantPlanResponse>(rawJson)
            } else {
                parseCommandLocally(userQuery)
            }
        } catch (_: Exception) {
            parseCommandLocally(userQuery)
        }
    }

    fun parseCommandLocally(query: String): AssistantPlanResponse {
        val lower = query.lowercase().trim()
        val actions = mutableListOf<AndroidActionCommand>()
        val replies = mutableListOf<String>()

        // 0. Floating Avatar Overlay commands
        if (lower.contains("покажи аватар") || lower.contains("включи аватар") || lower.contains("поверх всех") || lower.contains("включи оверлей")) {
            actions.add(AndroidActionCommand("SHOW_OVERLAY", "OVERLAY", ""))
            replies.add("Запускаю мой плавающий аватар поверх всех приложений!")
        } else if (lower.contains("скрой аватар") || lower.contains("убери аватар") || lower.contains("выключи оверлей")) {
            actions.add(AndroidActionCommand("HIDE_OVERLAY", "OVERLAY", ""))
            replies.add("Скрываю плавающий аватар")
        }

        // 1. Global System & Accessibility Actions (Screenshot, Home, Back, Notifications, Lock, Recents)
        when {
            lower.contains("скриншот") || lower.contains("снимок экрана") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "SCREENSHOT", ""))
                replies.add("Секунду, делаю скриншот экрана!")
            }
            lower.contains("заблокир") || lower.contains("выключи экран") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "LOCK_SCREEN", ""))
                replies.add("Блокирую экран телефона")
            }
            lower.contains("уведомлен") || lower.contains("шторк") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "NOTIFICATIONS", ""))
                replies.add("Открываю панель уведомлений")
            }
            lower.contains("быстрые настройк") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "QUICK_SETTINGS", ""))
                replies.add("Открываю быстрые настройки системы")
            }
            lower.contains("домой") || lower.contains("главный экран") || lower.contains("рабочий стол") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "HOME", ""))
                replies.add("Возвращаюсь на главный экран")
            }
            lower == "назад" || lower.contains("вернись назад") || lower.contains("шаг назад") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "BACK", ""))
                replies.add("Возвращаюсь назад")
            }
            lower.contains("недавн") || lower.contains("открытые приложен") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "RECENTS", ""))
                replies.add("Показываю список недавних приложений")
            }
            lower.contains("раздели экран") -> {
                actions.add(AndroidActionCommand("GLOBAL_ACTION", "SPLIT_SCREEN", ""))
                replies.add("Переключаю режим разделения экрана")
            }
        }

        // 2. Screen Reading & Deep Screen Analysis
        if (lower.contains("анализ экран") || lower.contains("проанализируй экран") || lower.contains("какие кнопки")) {
            actions.add(AndroidActionCommand("ANALYZE_SCREEN", "SCREEN", ""))
            replies.add("Сканирую все кнопки и элементы на текущем экране!")
        } else if (lower.contains("что на экране") || lower.contains("прочитай экран")) {
            actions.add(AndroidActionCommand("READ_SCREEN", "SCREEN", ""))
            replies.add("Считываю элементы текущего экрана")
        }

        // 3. Scroll / Swipe inside apps
        if (lower.contains("пролист") || lower.contains("скролл") || lower.contains("прокрут") || lower.contains("свайп")) {
            val dir = when {
                lower.contains("вверх") || lower.contains("наверх") -> "UP"
                lower.contains("влево") -> "LEFT"
                lower.contains("вправо") -> "RIGHT"
                else -> "DOWN"
            }
            actions.add(AndroidActionCommand("SCROLL_SCREEN", dir, ""))
            replies.add("Прокручиваю экран")
        }

        // 4. Click UI Element by voice ("нажми ...", "кликни ...", "поставь лайк", "подпишись")
        if (lower.contains("поставь лайк") || lower == "лайк") {
            actions.add(AndroidActionCommand("CLICK_UI", "лайк", ""))
            replies.add("Ставлю лайк!")
        } else if (lower.contains("подпишись") || lower.contains("нажми подписаться")) {
            actions.add(AndroidActionCommand("CLICK_UI", "подписаться", ""))
            replies.add("Нажимаю кнопку Подписаться!")
        } else if (lower.startsWith("зажми ") || lower.startsWith("удерживай ") || lower.contains("долгое нажатие")) {
            val target = query.replace(Regex("(?i)^(зажми|удерживай|долгое нажатие на|долгое нажатие)\\s*"), "").trim()
            if (target.isNotEmpty()) {
                actions.add(AndroidActionCommand("LONG_CLICK_UI", target, ""))
                replies.add("Удерживаю элемент «$target»")
            }
        } else if (lower.startsWith("нажми ") || lower.startsWith("кликни ") || lower.contains("нажми на ") || lower.startsWith("выбери ")) {
            val buttonTarget = query
                .replace(Regex("(?i)^(нажми на кнопку|нажми на|нажми кнопку|нажми|кликни на|кликни|выбери)\\s*"), "")
                .trim()
            if (buttonTarget.isNotEmpty()) {
                val idxMatch = Regex("^#?(\\d{1,2})$").find(buttonTarget)
                if (idxMatch != null) {
                    val idx = idxMatch.groupValues[1]
                    actions.add(AndroidActionCommand("CLICK_INDEX", idx, ""))
                    replies.add("Нажимаю на элемент номер $idx!")
                } else {
                    actions.add(AndroidActionCommand("CLICK_UI", buttonTarget, ""))
                    replies.add("Нажимаю на «$buttonTarget»")
                }
            }
        }

        // 5. Type text into app ("введи текст ...", "напечатай ...")
        if (lower.startsWith("введи ") || lower.startsWith("напечатай ")) {
            val textVal = query
                .replace(Regex("(?i)^(введи текст|введи|напечатай текст|напечатай)\\s*"), "")
                .trim()
            if (textVal.isNotEmpty()) {
                actions.add(AndroidActionCommand("INPUT_TEXT", "", textVal))
                replies.add("Ввожу текст «$textVal» и отправляю")
            }
        }

        // 6. Media playback control
        if (lower.contains("пауз") || lower.contains("останови музыку") || lower.contains("останови видео")) {
            actions.add(AndroidActionCommand("MEDIA_CONTROL", "PAUSE", ""))
            replies.add("Ставлю воспроизведение на паузу")
        } else if (lower.contains("следующ") && (lower.contains("трек") || lower.contains("песн") || lower.contains("видео"))) {
            actions.add(AndroidActionCommand("MEDIA_CONTROL", "NEXT", ""))
            replies.add("Включаю следующий трек!")
        } else if (lower.contains("предыдущ") && (lower.contains("трек") || lower.contains("песн"))) {
            actions.add(AndroidActionCommand("MEDIA_CONTROL", "PREVIOUS", ""))
            replies.add("Возвращаю предыдущий трек")
        } else if (lower.contains("продолжи воспроизведен") || lower.contains("включи плеер")) {
            actions.add(AndroidActionCommand("MEDIA_CONTROL", "PLAY", ""))
            replies.add("Продолжаю воспроизведение")
        }

        // 7. Cross-App Search & In-App Actions (YouTube, Telegram, WhatsApp, Google, Maps)
        if (lower.contains("ютуб") || lower.contains("youtube")) {
            val searchPart = query
                .replace(Regex("(?i)(открой|запусти|включи|найди|на|в|ютубе|ютуб|youtube|и|поищи|видео)"), "")
                .trim()
            if (searchPart.isNotEmpty()) {
                actions.add(AndroidActionCommand("SEARCH_IN_APP", "YouTube", searchPart))
                replies.add("С удовольствием! Открываю YouTube и ищу «$searchPart»")
            } else {
                actions.add(AndroidActionCommand("OPEN_APP", "YouTube", ""))
                replies.add("Запускаю YouTube!")
            }
        } else if ((lower.contains("телеграм") || lower.contains("telegram")) && (lower.contains("напиши") || lower.contains("отправь"))) {
            val msgPart = query
                .replace(Regex("(?i)(открой|в|телеграм|телеграме|telegram|и|напиши|отправь|сообщение)"), "")
                .trim()
            actions.add(AndroidActionCommand("SEARCH_IN_APP", "Telegram", msgPart.ifBlank { "Привет!" }))
            replies.add("Открываю Telegram и отправляю сообщение!")
        } else if ((lower.contains("ватсап") || lower.contains("whatsapp")) && (lower.contains("напиши") || lower.contains("отправь"))) {
            val msgPart = query
                .replace(Regex("(?i)(открой|в|ватсап|ватсапе|whatsapp|и|напиши|отправь|сообщение)"), "")
                .trim()
            actions.add(AndroidActionCommand("SEARCH_IN_APP", "WhatsApp", msgPart.ifBlank { "Привет!" }))
            replies.add("Перехожу в WhatsApp с вашим сообщением!")
        } else if (lower.startsWith("найди ") || lower.startsWith("поищи ") || lower.contains("загугли")) {
            val searchQ = query
                .replace(Regex("(?i)^(найди в интернете|найди в гугле|загугли|найди|поищи)\\s*"), "")
                .trim()
            if (searchQ.isNotEmpty()) {
                actions.add(AndroidActionCommand("SEARCH_IN_APP", "Google", searchQ))
                replies.add("Ищу для вас «$searchQ»!")
            }
        }

        // 8. Torch / Flashlight
        if (lower.contains("фонар") || lower.contains("вспышк")) {
            if (lower.contains("выкл") || lower.contains("погас") || lower.contains("откл")) {
                actions.add(AndroidActionCommand("TORCH_OFF", "FLASHLIGHT", "OFF"))
                replies.add("Готово, выключаю фонарик")
            } else {
                actions.add(AndroidActionCommand("TORCH_ON", "FLASHLIGHT", "ON"))
                replies.add("Конечно, включаю фонарик!")
            }
        }

        // 9. Volume
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
            actions.add(AndroidActionCommand("SET_VOLUME", stream, level.toString()))
            replies.add("Ставлю громкость на $level процентов")
        }

        // 10. Brightness
        if (lower.contains("яркост") || lower.contains("экран ярч") || lower.contains("тускл")) {
            val numberMatch = Regex("(\\d{1,3})").find(lower)
            val level = numberMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(10, 100)
                ?: if (lower.contains("максим") || lower.contains("ярч")) 100 else if (lower.contains("тускл") || lower.contains("миним")) 20 else 65
            actions.add(AndroidActionCommand("SET_BRIGHTNESS", "SCREEN", level.toString()))
            replies.add("Меняю яркость экрана на $level процентов")
        }

        // 11. Alarm
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
            actions.add(AndroidActionCommand("SET_ALARM", "Aura Alarm", timeStr))
            replies.add("Поставила будильник на $timeStr!")
        }

        // 12. Timer
        if (lower.contains("таймер") || lower.contains("засеки")) {
            val numMatch = Regex("(\\d+)").find(lower)
            val amount = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val seconds = if (lower.contains("сек")) amount else if (lower.contains("час")) amount * 3600 else amount * 60
            actions.add(AndroidActionCommand("SET_TIMER", "Aura Timer", seconds.toString()))
            replies.add("Засекаю таймер на $amount ${if (lower.contains("сек")) "секунд" else "минут"}!")
        }

        // 13. Calls
        if (lower.contains("позвон") || lower.contains("набери")) {
            val target = query.replace(Regex("(?i)(позвони|позвонить|набери|набрать|контакту|по номеру)"), "").trim()
                .ifBlank { "Голосовой вызов" }
            actions.add(AndroidActionCommand("CALL_CONTACT", target, ""))
            replies.add("Сейчас наберу $target")
        }

        // 14. SMS
        if (lower.contains("смс") || (lower.contains("сообщен") && !lower.contains("телеграм") && !lower.contains("ватсап"))) {
            val cleaned = query.replace(Regex("(?i)(отправь|напиши|смс|сообщение)"), "").trim()
            val parts = cleaned.split(" ", limit = 2)
            val recipient = parts.firstOrNull()?.ifBlank { "Контакт" } ?: "Контакт"
            val body = parts.getOrNull(1) ?: "Привет! Я скоро перезвоню."
            actions.add(AndroidActionCommand("SEND_SMS", recipient, body))
            replies.add("Подготовила сообщение для $recipient")
        }

        // 15. Settings & Accessibility
        if (lower.contains("специальн") && lower.contains("возможност")) {
            actions.add(AndroidActionCommand("OPEN_SETTINGS", "ACCESSIBILITY", ""))
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
            actions.add(AndroidActionCommand("OPEN_SETTINGS", panel, ""))
            replies.add("Открываю системные настройки $panel")
        }

        // 16. Tasks / Notes / Reminders
        if (lower.contains("задач") || lower.contains("купить") || lower.contains("список дел")) {
            val taskTitle = query.replace(Regex("(?i)(добавь|создай|задачу|в список дел|нужно)"), "").trim()
                .ifBlank { query }
            actions.add(AndroidActionCommand("CREATE_TASK", taskTitle, "Создано голосовой командой"))
            replies.add("Записала задачу «$taskTitle» в ваш список дел!")
        } else if (lower.contains("заметк") || lower.contains("запиши")) {
            val noteText = query.replace(Regex("(?i)(создай|добавь|заметку|запиши)"), "").trim()
                .ifBlank { query }
            actions.add(AndroidActionCommand("CREATE_NOTE", noteText.take(40), noteText))
            replies.add("Сохранила вашу заметку!")
        } else if (lower.contains("напомн")) {
            val remText = query.replace(Regex("(?i)(напомни|создай напоминание)"), "").trim()
                .ifBlank { query }
            actions.add(AndroidActionCommand("CREATE_REMINDER", remText, "Сегодня"))
            replies.add("Обязательно напомню: «$remText»")
        }

        // 17. Open Any App (with optional follow-up action after " и ")
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
                actions.add(AndroidActionCommand("OPEN_APP", appPart, ""))
                when {
                    secondPart.startsWith("нажми ") -> {
                        val btn = secondPart.removePrefix("нажми ").trim()
                        actions.add(AndroidActionCommand("CLICK_UI", btn, "DELAY"))
                    }
                    secondPart.startsWith("напиши ") || secondPart.startsWith("введи ") -> {
                        val txt = secondPart.replace(Regex("^(напиши|введи)\\s*"), "").trim()
                        actions.add(AndroidActionCommand("INPUT_TEXT", "", txt))
                    }
                    secondPart.startsWith("найди ") -> {
                        val q = secondPart.removePrefix("найди ").trim()
                        actions.add(AndroidActionCommand("SEARCH_IN_APP", appPart, q))
                    }
                    secondPart.contains("пролист") || secondPart.contains("скролл") -> {
                        actions.add(AndroidActionCommand("SCROLL_SCREEN", "DOWN", "DELAY"))
                    }
                }
                replies.add("Открываю $appPart и выполняю действие!")
            } else if (rawTarget.isNotEmpty()) {
                actions.add(AndroidActionCommand("OPEN_APP", rawTarget, ""))
                replies.add("С радостью! Запускаю $rawTarget.")
            }
        }

        if (actions.isEmpty()) {
            return AssistantPlanResponse(
                spokenResponse = "Я вас слушаю! Скажите, какое приложение открыть, что нажать на экране, какой текст ввести или какую функцию телефона переключить.",
                actions = emptyList()
            )
        }

        return AssistantPlanResponse(
            spokenResponse = replies.joinToString(". ") + ".",
            actions = actions
        )
    }
}
