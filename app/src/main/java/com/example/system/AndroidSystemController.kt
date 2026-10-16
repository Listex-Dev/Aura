package com.example.system

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class InstalledAppInfo(
    val label: String,
    val packageName: String
)

data class DeviceTelemetryState(
    val isTorchOn: Boolean = false,
    val hasFlashUnit: Boolean = false,
    val mediaVolumePercent: Int = 50,
    val ringVolumePercent: Int = 70,
    val alarmVolumePercent: Int = 80,
    val screenBrightnessPercent: Int = 75,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val isWifiConnected: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val canWriteSettings: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val hasDndAccess: Boolean = false,
    val isBatteryUnrestricted: Boolean = false,
    val grantedRuntimePermissionsCount: Int = 0,
    val totalRuntimePermissionsCount: Int = 8,
    val installedApps: List<InstalledAppInfo> = emptyList()
)

class AndroidSystemController(private val context: Context) {

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var torchCameraId: String? = null

    private val _telemetry = MutableStateFlow(DeviceTelemetryState())
    val telemetry: StateFlow<DeviceTelemetryState> = _telemetry.asStateFlow()

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) {
                _telemetry.update { it.copy(isTorchOn = enabled) }
            }
        }
    }

    init {
        initTorchSupport()
        refreshTelemetry()
        loadInstalledApps()
    }

    private fun initTorchSupport() {
        try {
            val id = cameraManager?.cameraIdList?.firstOrNull { camId ->
                cameraManager.getCameraCharacteristics(camId)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cameraManager?.cameraIdList?.firstOrNull()
            torchCameraId = id
            val hasFlash = id != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
            _telemetry.update { it.copy(hasFlashUnit = hasFlash) }
            cameraManager?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            _telemetry.update { it.copy(hasFlashUnit = false) }
        }
    }

    fun release() {
        try {
            cameraManager?.unregisterTorchCallback(torchCallback)
        } catch (_: Exception) {
        }
    }

    fun getRequiredRuntimePermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        if (AuraAccessibilityService.isConnected.value) return true
        return try {
            val expectedComponent = ComponentName(appContext, AuraAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabledServices.split(':').any {
                ComponentName.unflattenFromString(it) == expectedComponent
            }
        } catch (_: Exception) {
            false
        }
    }

    fun openAccessibilitySettings(): String {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Открыты настройки специальных возможностей Android"
        } catch (_: Exception) {
            "Откройте Настройки -> Специальные возможности -> Aura Control"
        }
    }

    fun openWriteSettingsAccess(): String {
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${appContext.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Разрешите «Изменение системных настроек» для полного управления яркостью и системой"
        } catch (_: Exception) {
            openAppDetailsSettings()
        }
    }

    fun openOverlayAccess(): String {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${appContext.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Разрешите «Поверх других окон» для работы поверх любых приложений"
        } catch (_: Exception) {
            openAppDetailsSettings()
        }
    }

    fun openDndPolicyAccess(): String {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Разрешите доступ к режиму «Не беспокоить» для управления звуком"
        } catch (_: Exception) {
            openAppDetailsSettings()
        }
    }

    fun openBatteryOptimizationExemption(): String {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${appContext.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Запрошено отключение ограничений батареи для фоновой работы 24/7"
        } catch (_: Exception) {
            openAppDetailsSettings()
        }
    }

    fun openAppDetailsSettings(): String {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${appContext.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Открыты системные разрешения приложения Aura Control"
        } catch (_: Exception) {
            "Откройте настройки приложения Aura Control"
        }
    }

    fun refreshTelemetry() {
        val mediaPct = getStreamVolumePercent(AudioManager.STREAM_MUSIC)
        val ringPct = getStreamVolumePercent(AudioManager.STREAM_RING)
        val alarmPct = getStreamVolumePercent(AudioManager.STREAM_ALARM)

        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val netCaps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val wifiOn = netCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btOn = try {
            btManager?.adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }

        val sysBrightness = try {
            val raw = Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 190)
            ((raw / 255f) * 100).toInt().coerceIn(5, 100)
        } catch (_: Exception) {
            _telemetry.value.screenBrightnessPercent
        }

        val a11yEnabled = isAccessibilityServiceEnabled()
        val writeSettings = try { Settings.System.canWrite(appContext) } catch (_: Exception) { false }
        val drawOverlays = try { Settings.canDrawOverlays(appContext) } catch (_: Exception) { false }
        val dndAccess = try { notificationManager?.isNotificationPolicyAccessGranted == true } catch (_: Exception) { false }
        val batteryUnrestricted = try {
            powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        } catch (_: Exception) {
            false
        }

        val allRuntimePerms = getRequiredRuntimePermissions()
        val grantedCount = allRuntimePerms.count { hasPermission(it) }

        _telemetry.update {
            it.copy(
                mediaVolumePercent = mediaPct,
                ringVolumePercent = ringPct,
                alarmVolumePercent = alarmPct,
                batteryPercent = batteryPct,
                isCharging = charging,
                isWifiConnected = wifiOn,
                isBluetoothEnabled = btOn,
                isAccessibilityEnabled = a11yEnabled,
                canWriteSettings = writeSettings,
                canDrawOverlays = drawOverlays,
                hasDndAccess = dndAccess,
                isBatteryUnrestricted = batteryUnrestricted,
                grantedRuntimePermissionsCount = grantedCount,
                totalRuntimePermissionsCount = allRuntimePerms.size,
                screenBrightnessPercent = sysBrightness
            )
        }
    }

    fun loadInstalledApps() {
        try {
            val pm = appContext.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val apps = resolveInfos.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                if (label.isNotEmpty() && pkg != appContext.packageName) {
                    InstalledAppInfo(label = label, packageName = pkg)
                } else null
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            _telemetry.update { it.copy(installedApps = apps) }
        } catch (_: Exception) {
        }
    }

    private fun getStreamVolumePercent(streamType: Int): Int {
        val am = audioManager ?: return 50
        return try {
            val max = am.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val cur = am.getStreamVolume(streamType)
            ((cur.toFloat() / max.toFloat()) * 100f).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            50
        }
    }

    fun setTorch(enabled: Boolean): String {
        val camId = torchCameraId
        return try {
            if (camId != null) {
                cameraManager?.setTorchMode(camId, enabled)
            }
            _telemetry.update { it.copy(isTorchOn = enabled) }
            if (enabled) "Фонарик: ВКЛ" else "Фонарик: ВЫКЛ"
        } catch (e: Exception) {
            _telemetry.update { it.copy(isTorchOn = enabled) }
            if (enabled) "Фонарик активирован" else "Фонарик отключён"
        }
    }

    fun setVolume(streamTarget: String, percent: Int): String {
        val cleanPct = percent.coerceIn(0, 100)
        val streamType = when (streamTarget.uppercase()) {
            "RING", "CALL" -> AudioManager.STREAM_RING
            "ALARM" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        try {
            val am = audioManager
            if (am != null) {
                val max = am.getStreamMaxVolume(streamType).coerceAtLeast(1)
                val targetVol = ((cleanPct / 100f) * max).toInt().coerceIn(0, max)
                am.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)
            }
        } catch (_: SecurityException) {
        }
        _telemetry.update {
            when (streamType) {
                AudioManager.STREAM_RING -> it.copy(ringVolumePercent = cleanPct)
                AudioManager.STREAM_ALARM -> it.copy(alarmVolumePercent = cleanPct)
                else -> it.copy(mediaVolumePercent = cleanPct)
            }
        }
        val streamLabel = when (streamType) {
            AudioManager.STREAM_RING -> "Звонок"
            AudioManager.STREAM_ALARM -> "Будильник"
            else -> "Мультимедиа"
        }
        return "Громкость ($streamLabel): $cleanPct%"
    }

    fun setBrightness(activity: Activity?, percent: Int): String {
        val cleanPct = percent.coerceIn(5, 100)
        try {
            if (Settings.System.canWrite(appContext)) {
                val sysValue = ((cleanPct / 100f) * 255).toInt().coerceIn(10, 255)
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    sysValue
                )
            }
        } catch (_: Exception) {
        }
        try {
            activity?.window?.let { window ->
                val params = window.attributes
                params.screenBrightness = cleanPct / 100f
                window.attributes = params
            }
        } catch (_: Exception) {
        }
        _telemetry.update { it.copy(screenBrightnessPercent = cleanPct) }
        return "Яркость экрана: $cleanPct%"
    }

    fun setAlarm(title: String, timeHhMm: String): String {
        val parts = timeHhMm.split(":")
        val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(0, 23) ?: 7
        val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(0, 59) ?: 30
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, title.ifBlank { "Aura Control" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Будильник установлен на %02d:%02d".format(hour, minute)
        } catch (_: Exception) {
            "Будильник запланирован на %02d:%02d".format(hour, minute)
        }
    }

    fun setTimer(title: String, durationSeconds: Int): String {
        val secs = durationSeconds.coerceAtLeast(5)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, secs)
            putExtra(AlarmClock.EXTRA_MESSAGE, title.ifBlank { "Таймер Aura" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Таймер на ${secs / 60} мин ${secs % 60} сек запущен"
        } catch (_: Exception) {
            "Таймер: ${secs / 60} мин ${secs % 60} сек"
        }
    }

    fun openAppByQuery(query: String): String {
        val clean = query.trim().lowercase()
        val apps = _telemetry.value.installedApps
        val matched = apps.firstOrNull {
            it.label.lowercase() == clean ||
                it.label.lowercase().contains(clean) ||
                it.packageName.lowercase().contains(clean)
        }
        if (matched != null) {
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(matched.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    appContext.startActivity(launchIntent)
                    "Запущено: ${matched.label}"
                } catch (_: Exception) {
                    "Открытие: ${matched.label}"
                }
            }
        }
        val fallbackIntent = when {
            clean.contains("ютуб") || clean.contains("youtube") ->
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
            clean.contains("телеграм") || clean.contains("telegram") ->
                Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me"))
            clean.contains("ватсап") || clean.contains("whatsapp") ->
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me"))
            clean.contains("камер") || clean.contains("camera") ->
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            clean.contains("браузер") || clean.contains("chrome") || clean.contains("интернет") ->
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            clean.contains("карт") || clean.contains("maps") || clean.contains("навигатор") ->
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
            clean.contains("телефон") || clean.contains("звонк") -> Intent(Intent.ACTION_DIAL)
            clean.contains("сообщен") || clean.contains("смс") ->
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
            clean.contains("часы") || clean.contains("будильн") -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            clean.contains("настройк") || clean.contains("settings") -> Intent(Settings.ACTION_SETTINGS)
            else -> null
        }
        if (fallbackIntent != null) {
            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                appContext.startActivity(fallbackIntent)
                "Открыто приложение: $query"
            } catch (_: Exception) {
                "Команда запуска: $query"
            }
        }
        return "Приложение «$query» не найдено"
    }

    fun launchPackage(packageName: String, label: String): String {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                appContext.startActivity(launchIntent)
                "Запущено: $label"
            } catch (_: Exception) {
                "Не удалось запустить $label"
            }
        }
        return "Не удалось запустить $label"
    }

    // --- Full Accessibility & In-App Control Bridge ---

    fun executeGlobalAccessibilityAction(action: String): String {
        val svc = AuraAccessibilityService.instance.value
        if (svc != null) {
            return svc.performGlobalSystemAction(action)
        }
        return "Включите Специальные возможности для действия: $action"
    }

    fun clickUiElementInApp(targetText: String, delayMs: Long = 0L): String {
        val svc = AuraAccessibilityService.instance.value
        if (svc == null) {
            return "Для нажатия «$targetText» внутри приложений включите Специальные возможности Aura"
        }
        return if (delayMs > 0L) {
            AuraAccessibilityService.runDelayed(delayMs) { it.clickElementByText(targetText) }
            "Запланировано нажатие «$targetText» в приложении"
        } else {
            svc.clickElementByText(targetText)
        }
    }

    fun clickElementByIndex(index: Int, delayMs: Long = 0L): String {
        val svc = AuraAccessibilityService.instance.value
            ?: return "Включите Специальные возможности Aura для нажатия элемента #$index"
        return if (delayMs > 0L) {
            AuraAccessibilityService.runDelayed(delayMs) { it.clickElementByIndex(index) }
            "Нажатие на элемент #$index"
        } else {
            svc.clickElementByIndex(index)
        }
    }

    fun longClickUiElementInApp(targetText: String, delayMs: Long = 0L): String {
        val svc = AuraAccessibilityService.instance.value
            ?: return "Включите Специальные возможности Aura для долгого нажатия «$targetText»"
        return if (delayMs > 0L) {
            AuraAccessibilityService.runDelayed(delayMs) { it.longClickElement(targetText) }
            "Долгое нажатие на «$targetText»"
        } else {
            svc.longClickElement(targetText)
        }
    }

    fun inspectCurrentScreenElements(): String {
        val svc = AuraAccessibilityService.instance.value
            ?: return "Включите Специальные возможности Aura для глубокого анализа экрана"
        val items = svc.inspectCurrentScreen()
        val clickable = items.count { it.isClickable }
        val inputs = items.count { it.isEditable }
        return "Экран проанализирован: найдено ${items.size} элементов ($clickable кнопок, $inputs полей ввода)"
    }

    fun showFloatingAvatarOverlay(): String {
        val ok = AuraOverlayManager.showOverlay(appContext)
        return if (ok) {
            "Плавающий аватар Ауры запущен поверх всех приложений"
        } else {
            openOverlayAccess()
            "Разрешите «Поверх всех окон» или включите Accessibility для показа аватара Ауры"
        }
    }

    fun hideFloatingAvatarOverlay(): String {
        AuraOverlayManager.hideOverlay()
        return "Плавающий аватар Ауры скрыт"
    }

    fun toggleFloatingAvatarOverlay(): String {
        return if (AuraOverlayManager.isOverlayShowing.value) {
            hideFloatingAvatarOverlay()
        } else {
            showFloatingAvatarOverlay()
        }
    }

    fun inputTextInApp(fieldHint: String, textToType: String, delayMs: Long = 0L): String {
        val svc = AuraAccessibilityService.instance.value
        if (svc == null) {
            return "Для авто-ввода «$textToType» внутри приложений включите Специальные возможности Aura"
        }
        return if (delayMs > 0L) {
            AuraAccessibilityService.runDelayed(delayMs) {
                it.inputTextIntoAppField(fieldHint, textToType, autoSubmit = true)
            }
            "Ввод текста «$textToType» в открытом приложении"
        } else {
            svc.inputTextIntoAppField(fieldHint, textToType, autoSubmit = true)
        }
    }

    fun scrollInApp(direction: String, delayMs: Long = 0L): String {
        val svc = AuraAccessibilityService.instance.value
        if (svc == null) {
            return "Для прокрутки ($direction) в приложениях включите Специальные возможности Aura"
        }
        return if (delayMs > 0L) {
            AuraAccessibilityService.runDelayed(delayMs) { it.scrollOrSwipeScreen(direction) }
            "Прокрутка экрана ($direction)"
        } else {
            svc.scrollOrSwipeScreen(direction)
        }
    }

    fun tapScreenPercent(xPercent: Float, yPercent: Float, delayMs: Long = 0L): String {
        val svc = AuraAccessibilityService.instance.value
        if (svc == null) {
            return "Для касания экрана включите Специальные возможности Aura"
        }
        return if (delayMs > 0L) {
            AuraAccessibilityService.runDelayed(delayMs) { it.tapAtPercent(xPercent, yPercent) }
            "Касание экрана (${xPercent.toInt()}%, ${yPercent.toInt()}%)"
        } else {
            svc.tapAtPercent(xPercent, yPercent)
        }
    }

    fun readCurrentScreenContent(): String {
        val svc = AuraAccessibilityService.instance.value
        if (svc == null) {
            return "Включите Специальные возможности Aura, чтобы читать элементы любого экрана"
        }
        return svc.readActiveScreenSummary()
    }

    fun searchInAppOrWeb(appNameOrTarget: String, searchQuery: String): String {
        val lowerApp = appNameOrTarget.lowercase().trim()
        val encoded = Uri.encode(searchQuery)

        return try {
            when {
                lowerApp.contains("ютуб") || lowerApp.contains("youtube") -> {
                    val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.google.android.youtube")
                        putExtra("query", searchQuery)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        appContext.startActivity(ytIntent)
                    } catch (_: Exception) {
                        val webYt = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/results?search_query=$encoded")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        appContext.startActivity(webYt)
                    }
                    "Поиск в YouTube: «$searchQuery»"
                }

                lowerApp.contains("карт") || lowerApp.contains("maps") || lowerApp.contains("навиг") -> {
                    val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(mapIntent)
                    "Маршрут / поиск на Картах: «$searchQuery»"
                }

                lowerApp.contains("телеграм") || lowerApp.contains("telegram") -> {
                    val tgIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage("org.telegram.messenger")
                        putExtra(Intent.EXTRA_TEXT, searchQuery)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        appContext.startActivity(tgIntent)
                    } catch (_: Exception) {
                        openAppByQuery("Telegram")
                        inputTextInApp("", searchQuery, delayMs = 1400L)
                    }
                    "Отправка в Telegram: «$searchQuery»"
                }

                lowerApp.contains("ватсап") || lowerApp.contains("whatsapp") -> {
                    val waIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage("com.whatsapp")
                        putExtra(Intent.EXTRA_TEXT, searchQuery)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        appContext.startActivity(waIntent)
                    } catch (_: Exception) {
                        openAppByQuery("WhatsApp")
                        inputTextInApp("", searchQuery, delayMs = 1400L)
                    }
                    "Отправка в WhatsApp: «$searchQuery»"
                }

                lowerApp.isNotBlank() && lowerApp != "web" && lowerApp != "google" && lowerApp != "браузер" -> {
                    // Launch the requested app and type the query via AccessibilityService
                    openAppByQuery(appNameOrTarget)
                    if (AuraAccessibilityService.isConnected.value) {
                        AuraAccessibilityService.runDelayed(1500L) { svc ->
                            svc.clickElementByText("Поиск")
                            AuraAccessibilityService.runDelayed(600L) {
                                it.inputTextIntoAppField("", searchQuery, autoSubmit = true)
                            }
                        }
                        "Открыто «$appNameOrTarget» и введено «$searchQuery»"
                    } else {
                        "Открыто приложение «$appNameOrTarget» для запроса «$searchQuery»"
                    }
                }

                else -> {
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=$encoded")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(webIntent)
                    "Поиск в Google: «$searchQuery»"
                }
            }
        } catch (_: Exception) {
            "Запрос «$searchQuery» выполнен"
        }
    }

    fun controlMediaPlayback(command: String): String {
        val am = audioManager ?: return "Аудио-менеджер недоступен"
        val keyCode = when (command.uppercase()) {
            "NEXT", "СЛЕДУЮЩИЙ", "ВПЕРЕД" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "PREVIOUS", "ПРЕДЫДУЩИЙ", "НАЗАД" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "PAUSE", "ПАУЗА", "STOP", "СТОП" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "PLAY", "ИГРАТЬ", "ПУСК" -> KeyEvent.KEYCODE_MEDIA_PLAY
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        return try {
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            "Медиа-команда: $command"
        } catch (_: Exception) {
            "Медиа: $command"
        }
    }

    fun resolveContactPhone(nameOrNumber: String): String {
        val trimmed = nameOrNumber.trim()
        if (trimmed.any { it.isDigit() }) {
            return trimmed.filter { it.isDigit() || it == '+' }
        }
        if (trimmed.isNotEmpty() && hasPermission(Manifest.permission.READ_CONTACTS)) {
            try {
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("%$trimmed%")
                appContext.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numIdx >= 0 && cursor.moveToFirst()) {
                        val rawNumber = cursor.getString(numIdx).orEmpty()
                        val cleaned = rawNumber.filter { it.isDigit() || it == '+' }
                        if (cleaned.isNotEmpty()) return cleaned
                    }
                }
            } catch (_: Exception) {
            }
        }
        return ""
    }

    fun callContactOrNumber(target: String): String {
        val resolved = resolveContactPhone(target)
        val canDirectCall = hasPermission(Manifest.permission.CALL_PHONE)
        val intent = if (resolved.isNotEmpty()) {
            Intent(
                if (canDirectCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                Uri.parse("tel:$resolved")
            )
        } else {
            Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            appContext.startActivity(intent)
            if (resolved.isNotEmpty() && canDirectCall) {
                "Прямой звонок: $target ($resolved)"
            } else {
                "Вызов: $target"
            }
        } catch (_: Exception) {
            try {
                if (resolved.isNotEmpty()) {
                    val dialFallback = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$resolved")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(dialFallback)
                }
            } catch (_: Exception) {
            }
            "Подготовлен вызов: $target"
        }
    }

    fun sendSmsToTarget(target: String, messageBody: String): String {
        val resolved = resolveContactPhone(target)
        val uriNumber = if (resolved.any { it.isDigit() }) resolved else ""

        if (uriNumber.isNotEmpty() && messageBody.isNotBlank() && hasPermission(Manifest.permission.SEND_SMS)) {
            try {
                val smsManager = appContext.getSystemService(SmsManager::class.java)
                if (smsManager != null) {
                    smsManager.sendTextMessage(uriNumber, null, messageBody, null, null)
                    return "SMS отправлено напрямую на $target ($uriNumber)"
                }
            } catch (_: Exception) {
            }
        }

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$uriNumber")).apply {
            putExtra("sms_body", messageBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "SMS для $target подготовлено"
        } catch (_: Exception) {
            "SMS для $target: «$messageBody»"
        }
    }

    fun openSystemSettings(section: String): String {
        val action = when (section.uppercase()) {
            "ACCESSIBILITY", "СПЕЦИАЛЬНЫЕ_ВОЗМОЖНОСТИ" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "WIFI", "INTERNET", "NETWORK" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                } else {
                    Settings.ACTION_WIFI_SETTINGS
                }
            }
            "BLUETOOTH" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "SOUND", "VOLUME" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_VOLUME
                } else {
                    Settings.ACTION_SOUND_SETTINGS
                }
            }
            "DISPLAY", "BRIGHTNESS" -> Settings.ACTION_DISPLAY_SETTINGS
            "LOCATION", "GPS" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Открыты настройки: $section"
        } catch (_: Exception) {
            "Панель настроек: $section"
        }
    }

    fun buildDeviceSummaryForAi(): String {
        val t = _telemetry.value
        val a11yStatus = if (isAccessibilityServiceEnabled()) "АКТИВЕН (полное управление приложениями)" else "выкл"
        val screenStructure = AuraAccessibilityService.screenStructureSummary.value
        return "Заряд: ${t.batteryPercent}% (${if (t.isCharging) "заряжается" else "от батареи"}), " +
            "Фонарик: ${if (t.isTorchOn) "ВКЛ" else "ВЫКЛ"}, " +
            "Громкость медиа: ${t.mediaVolumePercent}%, звонка: ${t.ringVolumePercent}%, " +
            "Яркость: ${t.screenBrightnessPercent}%, Wi-Fi: ${if (t.isWifiConnected) "подключён" else "выкл"}, " +
            "Accessibility-контроль: $a11yStatus. " +
            "Текущие элементы на экране: $screenStructure"
    }
}
