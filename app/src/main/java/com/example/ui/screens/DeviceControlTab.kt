package com.example.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.system.AndroidSystemController
import com.example.system.DeviceTelemetryState
import kotlin.math.roundToInt

@Composable
fun DeviceControlTab(
    telemetry: DeviceTelemetryState,
    systemController: AndroidSystemController,
    onRequestCallPermissionAndDial: (String) -> Unit,
    onFeedbackSpoken: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var appSearchQuery by remember { mutableStateOf("") }
    var contactTarget by remember { mutableStateOf("") }
    var smsText by remember { mutableStateOf("") }
    var alarmTimeInput by remember { mutableStateOf("07:30") }
    var timerMinutesInput by remember { mutableStateOf("5") }

    val filteredApps = remember(telemetry.installedApps, appSearchQuery) {
        if (appSearchQuery.isBlank()) {
            telemetry.installedApps
        } else {
            telemetry.installedApps.filter {
                it.label.contains(appSearchQuery, ignoreCase = true) ||
                    it.packageName.contains(appSearchQuery, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Аппаратный пульт Android",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Прямое управление системными функциями, связью и приложениями",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Quick Hardware Tile Row: Flashlight, Battery, Wi-Fi, Bluetooth
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Torch Tile
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable {
                            val msg = systemController.setTorch(!telemetry.isTorchOn)
                            onFeedbackSpoken(msg)
                        }
                        .testTag("torch_toggle_card"),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (telemetry.isTorchOn) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (telemetry.isTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                                contentDescription = "Фонарик",
                                tint = if (telemetry.isTorchOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Switch(
                                checked = telemetry.isTorchOn,
                                onCheckedChange = {
                                    val msg = systemController.setTorch(it)
                                    onFeedbackSpoken(msg)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Фонарик",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (telemetry.isTorchOn) "Включён" else "Выключен",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Telemetry & Battery Tile
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatteryChargingFull,
                                contentDescription = "Заряд батареи",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "${telemetry.batteryPercent}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Питание ОС",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (telemetry.isCharging) "Заряжается" else "От аккумулятора",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // System Settings Panels Row (Wi-Fi, Bluetooth, GPS, Settings)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Быстрые системные панели Android",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SystemPanelButton(
                            icon = Icons.Default.Wifi,
                            label = "Интернет",
                            active = telemetry.isWifiConnected,
                            modifier = Modifier.weight(1f),
                            onClick = { systemController.openSystemSettings("WIFI") }
                        )
                        SystemPanelButton(
                            icon = Icons.Default.Bluetooth,
                            label = "Bluetooth",
                            active = telemetry.isBluetoothEnabled,
                            modifier = Modifier.weight(1f),
                            onClick = { systemController.openSystemSettings("BLUETOOTH") }
                        )
                        SystemPanelButton(
                            icon = Icons.Default.LocationOn,
                            label = "Гео / GPS",
                            active = true,
                            modifier = Modifier.weight(1f),
                            onClick = { systemController.openSystemSettings("LOCATION") }
                        )
                        SystemPanelButton(
                            icon = Icons.Default.Settings,
                            label = "Настройки",
                            active = false,
                            modifier = Modifier.weight(1f),
                            onClick = { systemController.openSystemSettings("GENERAL") }
                        )
                    }
                }
            }
        }

        // Sliders for Media Volume, Ring Volume, and Screen Brightness
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Звук и яркость дисплея",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    SliderControlRow(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        label = "Мультимедиа",
                        valuePercent = telemetry.mediaVolumePercent,
                        onValueChange = { pct -> systemController.setVolume("MEDIA", pct) }
                    )
                    SliderControlRow(
                        icon = Icons.Default.NotificationsActive,
                        label = "Громкость звонка",
                        valuePercent = telemetry.ringVolumePercent,
                        onValueChange = { pct -> systemController.setVolume("RING", pct) }
                    )
                    SliderControlRow(
                        icon = Icons.Default.BrightnessHigh,
                        label = "Яркость экрана",
                        valuePercent = telemetry.screenBrightnessPercent,
                        onValueChange = { pct -> systemController.setBrightness(activity, pct) }
                    )
                }
            }
        }

        // Quick Alarm & Timer Setter
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Будильник и таймер",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = alarmTimeInput,
                            onValueChange = { alarmTimeInput = it },
                            label = { Text("Время (ЧЧ:ММ)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        FilledTonalButton(
                            onClick = {
                                val msg = systemController.setAlarm("Aura Будильник", alarmTimeInput)
                                onFeedbackSpoken(msg)
                            },
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("set_alarm_button")
                        ) {
                            Icon(Icons.Default.Alarm, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Будильник")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = timerMinutesInput,
                            onValueChange = { timerMinutesInput = it },
                            label = { Text("Минуты таймера") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        FilledTonalButton(
                            onClick = {
                                val mins = timerMinutesInput.filter { it.isDigit() }.toIntOrNull() ?: 5
                                val msg = systemController.setTimer("Таймер Aura", mins * 60)
                                onFeedbackSpoken(msg)
                            },
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("set_timer_button")
                        ) {
                            Icon(Icons.Default.Timer, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Таймер")
                        }
                    }
                }
            }
        }

        // Phone Call & SMS Quick Bridge
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Звонки и быстрые сообщения",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = contactTarget,
                        onValueChange = { contactTarget = it },
                        label = { Text("Имя контакта или номер телефона") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("contact_target_input"),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = smsText,
                        onValueChange = { smsText = it },
                        label = { Text("Текст SMS сообщения (необязательно)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (contactTarget.isNotBlank()) {
                                    onRequestCallPermissionAndDial(contactTarget)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("call_contact_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Позвонить")
                        }
                        FilledTonalButton(
                            onClick = {
                                val msg = systemController.sendSmsToTarget(
                                    contactTarget.ifBlank { "Контакт" },
                                    smsText.ifBlank { "Привет!" }
                                )
                                onFeedbackSpoken(msg)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("send_sms_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Отправить SMS")
                        }
                    }
                }
            }
        }

        // Installed Applications Launcher
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Установленные приложения (${telemetry.installedApps.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        placeholder = { Text("Поиск приложения для запуска...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("app_search_input"),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
        }

        items(filteredApps.take(25), key = { it.packageName }) { appInfo ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val msg = systemController.launchPackage(appInfo.packageName, appInfo.label)
                        onFeedbackSpoken(msg)
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = appInfo.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = appInfo.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Запустить ${appInfo.label}",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun SystemPanelButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SliderControlRow(
    icon: ImageVector,
    label: String,
    valuePercent: Int,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "$valuePercent%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = valuePercent.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..100f
        )
    }
}
