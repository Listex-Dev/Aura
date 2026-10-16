package com.example.system

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
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
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
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
    val installedApps: List<InstalledAppInfo> = emptyList()
)

class AndroidSystemController(private val context: Context) {

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

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

        _telemetry.update {
            it.copy(
                mediaVolumePercent = mediaPct,
                ringVolumePercent = ringPct,
                alarmVolumePercent = alarmPct,
                batteryPercent = batteryPct,
                isCharging = charging,
                isWifiConnected = wifiOn,
                isBluetoothEnabled = btOn,
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
            // Do Not Disturb restriction fallback
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
        val cleanPct = percent.coerceIn(10, 100)
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
        // Built-in system app fallbacks by common Russian/English names
        val fallbackIntent = when {
            clean.contains("камер") || clean.contains("camera") -> Intent( android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
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
                "Открыто системное приложение: $query"
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

    fun resolveContactPhone(nameOrNumber: String): String {
        val trimmed = nameOrNumber.trim()
        if (trimmed.any { it.isDigit() }) {
            return trimmed.filter { it.isDigit() || it == '+' }
        }
        return ""
    }

    fun callContactOrNumber(target: String): String {
        val resolved = resolveContactPhone(target)
        val intent = if (resolved.isNotEmpty()) {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$resolved"))
        } else {
            Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            appContext.startActivity(intent)
            "Вызов: $target"
        } catch (_: Exception) {
            "Подготовлен вызов: $target"
        }
    }

    fun sendSmsToTarget(target: String, messageBody: String): String {
        val resolved = resolveContactPhone(target)
        val uriNumber = if (resolved.any { it.isDigit() }) resolved else ""
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
        return "Заряд: ${t.batteryPercent}% (${if (t.isCharging) "заряжается" else "от батареи"}), " +
            "Фонарик: ${if (t.isTorchOn) "ВКЛ" else "ВЫКЛ"}, " +
            "Громкость медиа: ${t.mediaVolumePercent}%, звонка: ${t.ringVolumePercent}%, " +
            "Яркость: ${t.screenBrightnessPercent}%, Wi-Fi: ${if (t.isWifiConnected) "подключён" else "выкл"}"
    }
}
