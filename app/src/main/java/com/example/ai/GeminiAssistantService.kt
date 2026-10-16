package com.example.ai

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.TimeUnit

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
                put("description", "Natural, concise Russian spoken response to be read aloud via TTS.")
            }
            putJsonObject("actions") {
                put("type", "ARRAY")
                put("description", "Ordered list of Android system actions to execute immediately.")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("actionType") {
                            put("type", "STRING")
                            put(
                                "description",
                                "One of: TORCH_ON, TORCH_OFF, SET_VOLUME, SET_BRIGHTNESS, SET_ALARM, SET_TIMER, OPEN_APP, CALL_CONTACT, SEND_SMS, OPEN_SETTINGS, CREATE_NOTE, CREATE_TASK, CREATE_REMINDER, RUN_ROUTINE, NONE"
                            )
                        }
                        putJsonObject("target") {
                            put("type", "STRING")
                            put(
                                "description",
                                "Target app name, contact name or phone number, settings panel (WIFI, BLUETOOTH, SOUND, DISPLAY, LOCATION, GENERAL), or note/task/routine title."
                            )
                        }
                        putJsonObject("value") {
                            put("type", "STRING")
                            put(
                                "description",
                                "Action parameter: volume/brightness 0-100, alarm HH:MM, timer seconds, SMS message text, or note/task description."
                            )
                        }
                    }
                    putJsonArray("required") {
                        add(kotlinx.serialization.json.JsonPrimitive("actionType"))
                        add(kotlinx.serialization.json.JsonPrimitive("target"))
                        add(kotlinx.serialization.json.JsonPrimitive("value"))
                    }
                }
            }
        }
        putJsonArray("required") {
            add(kotlinx.serialization.json.JsonPrimitive("spokenResponse"))
            add(kotlinx.serialization.json.JsonPrimitive("actions"))
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
            Ты — голосовой ИИ-ассистент Aura Control для управления смартфоном Android.
            Отвечай ВСЕГДА на русском языке, кратко, дружелюбно и естественно для озвучивания голосом (1-2 предложения в поле spokenResponse).
            Проанализируй запрос пользователя и сформируй список системных действий в массиве actions:
            - TORCH_ON / TORCH_OFF: включить или выключить фонарик.
            - SET_VOLUME: изменить громкость (target: "MEDIA", "RING" или "ALARM", value: число от "0" до "100").
            - SET_BRIGHTNESS: изменить яркость экрана (target: "SCREEN", value: число от "10" до "100").
            - SET_ALARM: поставить будильник (target: название будильника, value: время в формате "HH:MM", например "07:30").
            - SET_TIMER: поставить таймер (target: название таймера, value: длительность в секундах, например "300" для 5 минут).
            - OPEN_APP: открыть приложение (target: точное название приложения из списка установленных или его часть).
            - CALL_CONTACT: позвонить контакту или по номеру (target: имя контакта или номер телефона).
            - SEND_SMS: отправить сообщение (target: имя контакта или номер телефона, value: текст сообщения).
            - OPEN_SETTINGS: открыть системные настройки (target: "WIFI", "BLUETOOTH", "SOUND", "DISPLAY", "LOCATION", "GENERAL").
            - CREATE_NOTE: сохранить заметку в облако (target: заголовок, value: текст заметки).
            - CREATE_TASK: добавить задачу в чек-лист (target: название задачи, value: детали задачи).
            - CREATE_REMINDER: добавить напоминание (target: текст напоминания, value: время или дата).
            - RUN_ROUTINE: запустить пользовательский сценарий (target: название сценария).
            - NONE: если пользователь просто задал вопрос или общается без системного действия.
            
            Текущее состояние устройства: $deviceContextSummary
            Доступные приложения: ${installedAppNames.take(35).joinToString(", ")}
            Доступные сценарии: ${routineNames.joinToString(", ")}
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = userQuery)))),
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseSchema = responseSchema,
                temperature = 0.2f
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
        } catch (e: Exception) {
            parseCommandLocally(userQuery)
        }
    }

    fun parseCommandLocally(query: String): AssistantPlanResponse {
        val lower = query.lowercase().trim()
        val actions = mutableListOf<AndroidActionCommand>()
        val replies = mutableListOf<String>()

        // 1. Torch / Flashlight
        if (lower.contains("фонар") || lower.contains("свет") || lower.contains("вспышк")) {
            if (lower.contains("выкл") || lower.contains("погас") || lower.contains("откл")) {
                actions.add(AndroidActionCommand("TORCH_OFF", "FLASHLIGHT", "OFF"))
                replies.add("Выключаю фонарик")
            } else {
                actions.add(AndroidActionCommand("TORCH_ON", "FLASHLIGHT", "ON"))
                replies.add("Включаю фонарик")
            }
        }

        // 2. Volume
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
            replies.add("Устанавливаю громкость на $level процентов")
        }

        // 3. Brightness
        if (lower.contains("яркост") || lower.contains("экран ярч") || lower.contains("тускл")) {
            val numberMatch = Regex("(\\d{1,3})").find(lower)
            val level = numberMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(10, 100)
                ?: if (lower.contains("максим") || lower.contains("ярч")) 100 else if (lower.contains("тускл") || lower.contains("миним")) 20 else 65
            actions.add(AndroidActionCommand("SET_BRIGHTNESS", "SCREEN", level.toString()))
            replies.add("Меняю яркость экрана на $level процентов")
        }

        // 4. Alarm
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
            replies.add("Ставлю будильник на $timeStr")
        }

        // 5. Timer
        if (lower.contains("таймер") || lower.contains("засеки")) {
            val numMatch = Regex("(\\d+)").find(lower)
            val amount = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val seconds = if (lower.contains("сек")) amount else if (lower.contains("час")) amount * 3600 else amount * 60
            actions.add(AndroidActionCommand("SET_TIMER", "Aura Timer", seconds.toString()))
            replies.add("Запускаю таймер на $amount ${if (lower.contains("сек")) "секунд" else "минут"}")
        }

        // 6. Calls
        if (lower.contains("позвон") || lower.contains("набери")) {
            val target = query.replace(Regex("(?i)(позвони|позвонить|набери|набрать|контакту|по номеру)"), "").trim()
                .ifBlank { "Голосовой вызов" }
            actions.add(AndroidActionCommand("CALL_CONTACT", target, ""))
            replies.add("Набираю $target")
        }

        // 7. SMS
        if (lower.contains("смс") || lower.contains("сообщен") || lower.contains("напиши")) {
            val cleaned = query.replace(Regex("(?i)(отправь|напиши|смс|сообщение)"), "").trim()
            val parts = cleaned.split(" ", limit = 2)
            val recipient = parts.firstOrNull()?.ifBlank { "Контакт" } ?: "Контакт"
            val body = parts.getOrNull(1) ?: "Привет! Я скоро перезвоню."
            actions.add(AndroidActionCommand("SEND_SMS", recipient, body))
            replies.add("Открываю отправку сообщения для $recipient")
        }

        // 8. Settings
        if (lower.contains("настройк") || lower.contains("вайфай") || lower.contains("wi-fi") || lower.contains("блютуз") || lower.contains("bluetooth")) {
            val panel = when {
                lower.contains("вайфай") || lower.contains("wi-fi") || lower.contains("интернет") || lower.contains("сет") -> "WIFI"
                lower.contains("блютуз") || lower.contains("bluetooth") -> "BLUETOOTH"
                lower.contains("звук") -> "SOUND"
                lower.contains("экран") -> "DISPLAY"
                lower.contains("гео") || lower.contains("локац") || lower.contains("gps") -> "LOCATION"
                else -> "GENERAL"
            }
            actions.add(AndroidActionCommand("OPEN_SETTINGS", panel, ""))
            replies.add("Открываю панель настроек $panel")
        }

        // 9. Tasks / Notes / Reminders
        if (lower.contains("задач") || lower.contains("купить") || lower.contains("сделать") || lower.contains("список дел")) {
            val taskTitle = query.replace(Regex("(?i)(добавь|создай|задачу|в список дел|нужно)"), "").trim()
                .ifBlank { query }
            actions.add(AndroidActionCommand("CREATE_TASK", taskTitle, "Создано голосовой командой"))
            replies.add("Задача «$taskTitle» добавлена в органайзер")
        } else if (lower.contains("заметк") || lower.contains("запиши")) {
            val noteText = query.replace(Regex("(?i)(создай|добавь|заметку|запиши)"), "").trim()
                .ifBlank { query }
            actions.add(AndroidActionCommand("CREATE_NOTE", noteText.take(40), noteText))
            replies.add("Заметка сохранена в облаке")
        } else if (lower.contains("напомн")) {
            val remText = query.replace(Regex("(?i)(напомни|создай напоминание)"), "").trim()
                .ifBlank { query }
            actions.add(AndroidActionCommand("CREATE_REMINDER", remText, "Сегодня"))
            replies.add("Напоминание «$remText» сохранено")
        }

        // 10. Open App
        if ((lower.contains("открой") || lower.contains("запусти")) && !lower.contains("настройк")) {
            val appTarget = query.replace(Regex("(?i)(открой|запусти|приложение)"), "").trim()
            if (appTarget.isNotEmpty()) {
                actions.add(AndroidActionCommand("OPEN_APP", appTarget, ""))
                replies.add("Запускаю $appTarget")
            }
        }

        if (actions.isEmpty()) {
            return AssistantPlanResponse(
                spokenResponse = "Я готов управлять вашим Android: скажите, например, «включи фонарик», «поставь будильник на 7:30», «громкость 80%» или «добавь задачу».",
                actions = emptyList()
            )
        }

        return AssistantPlanResponse(
            spokenResponse = replies.joinToString(". ") + ".",
            actions = actions
        )
    }
}
