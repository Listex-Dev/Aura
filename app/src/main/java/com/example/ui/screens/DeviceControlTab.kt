package com.example.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwipeDown
import androidx.compose.material.icons.filled.SwipeUp
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.system.AndroidSystemController
import com.example.system.AuraAccessibilityService
import com.example.system.AuraOverlayManager
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

    val isA11yConnected by AuraAccessibilityService.isConnected.collectAsState()
    val lastA11yReport by AuraAccessibilityService.lastActionReport.collectAsState()
    val screenElements by AuraAccessibilityService.screenElements.collectAsState()
    val isOverlayShowing by AuraOverlayManager.isOverlayShowing.collectAsState()
    val overlayVisualState by AuraOverlayManager.visualState.collectAsState()
    val overlayActionText by AuraOverlayManager.currentActionText.collectAsState()
    val lastAiReasoning by AuraOverlayManager.lastAiReasoning.collectAsState()
    val lastTappedCoordinates by AuraOverlayManager.lastTappedCoordinates.collectAsState()
    val a11yActive = isA11yConnected || telemetry.isAccessibilityEnabled

    val allPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        systemController.refreshTelemetry()
        val granted = results.values.count { it }
        onFeedbackSpoken("Разрешения обновлены: активно $granted из ${results.size}")
    }

    var appSearchQuery by remember { mutableStateOf("") }
    var targetAppName by remember { mutableStateOf("YouTube") }
    var inAppActionPayload by remember { mutableStateOf("") }
    var coordXInput by remember { mutableStateOf("540") }
    var coordYInput by remember { mutableStateOf("960") }
    var aiContextPromptInput by remember { mutableStateOf("") }
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
                text = "Полное управление Android",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Все системные разрешения, Специальные возможности (Accessibility) и управление любыми приложениями",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Full System Permissions Hub Card
        item {
            val allRuntimeGranted = telemetry.grantedRuntimePermissionsCount >= telemetry.totalRuntimePermissionsCount
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        RoundedCornerShape(24.dp)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Полный доступ и все разрешения",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Базовые права: ${telemetry.grantedRuntimePermissionsCount}/${telemetry.totalRuntimePermissionsCount} • Спец-привилегии Android",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            allPermissionsLauncher.launch(systemController.getRequiredRuntimePermissions())
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("request_all_permissions_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (allRuntimeGranted) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = if (allRuntimeGranted) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    ) {
                        Icon(
                            imageVector = if (allRuntimeGranted) Icons.Default.CheckCircle else Icons.Default.Security,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (allRuntimeGranted) {
                                "Все базовые разрешения выданы (${telemetry.grantedRuntimePermissionsCount}/${telemetry.totalRuntimePermissionsCount})"
                            } else {
                                "Выдать все разрешения в 1 клик (Звонки, СМС, Контакты, Камера, Микрофон)"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Специальные привилегии для полного контроля:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                val msg = systemController.openAccessibilitySettings()
                                onFeedbackSpoken(msg)
                            },
                            label = {
                                Text(
                                    if (a11yActive) "✓ Accessibility ВКЛ" else "Включить Accessibility"
                                )
                            }
                        )
                        AssistChip(
                            onClick = {
                                val msg = systemController.openWriteSettingsAccess()
                                onFeedbackSpoken(msg)
                            },
                            label = {
                                Text(
                                    if (telemetry.canWriteSettings) "✓ Системные настройки" else "+ Изменение настроек"
                                )
                            }
                        )
                        AssistChip(
                            onClick = {
                                val msg = systemController.openOverlayAccess()
                                onFeedbackSpoken(msg)
                            },
                            label = {
                                Text(
                                    if (telemetry.canDrawOverlays) "✓ Поверх всех окон" else "+ Поверх других окон"
                                )
                            }
                        )
                        AssistChip(
                            onClick = {
                                val msg = systemController.openBatteryOptimizationExemption()
                                onFeedbackSpoken(msg)
                            },
                            label = {
                                Text(
                                    if (telemetry.isBatteryUnrestricted) "✓ Фон 24/7 без сна" else "+ Откл. экономию батареи"
                                )
                            }
                        )
                        AssistChip(
                            onClick = {
                                val msg = systemController.openDndPolicyAccess()
                                onFeedbackSpoken(msg)
                            },
                            label = {
                                Text(
                                    if (telemetry.hasDndAccess) "✓ Доступ к звуку DND" else "+ Доступ к Не беспокоить"
                                )
                            }
                        )
                    }
                }
            }
        }

        // Floating Avatar Overlay & Live Screen Analyzer Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        RoundedCornerShape(24.dp)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_aura_avatar_1792153220147),
                                contentDescription = "Плавающий аватар Ауры",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Аватар Ауры поверх всех окон",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${overlayVisualState.badgeTitle} • $overlayActionText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Switch(
                            checked = isOverlayShowing,
                            onCheckedChange = {
                                val msg = systemController.toggleFloatingAvatarOverlay()
                                onFeedbackSpoken(msg)
                            },
                            modifier = Modifier.testTag("floating_avatar_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val msg = systemController.showFloatingAvatarOverlay()
                                onFeedbackSpoken(msg)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("show_overlay_hud_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isOverlayShowing) "Аватар активен" else "Запустить аватар")
                        }
                        FilledTonalButton(
                            onClick = {
                                val msg = systemController.inspectCurrentScreenElements()
                                onFeedbackSpoken(msg)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("analyze_screen_elements_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Анализ экрана (${screenElements.size})")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Live Gemini AI Context Reasoning & Coordinates Box
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "🧠 ИИ-Контекст Gemini (анализ экрана и координат):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = lastAiReasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (lastTappedCoordinates.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Последняя цель: $lastTappedCoordinates",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Contextual AI Action Input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = aiContextPromptInput,
                            onValueChange = { aiContextPromptInput = it },
                            label = { Text("Команда Gemini по контексту экрана") },
                            placeholder = { Text("Напр.: Найди поиск и введи музыка") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ai_context_command_input"),
                            shape = RoundedCornerShape(14.dp)
                        )
                        Button(
                            onClick = {
                                val cmd = aiContextPromptInput.trim().ifBlank {
                                    "Проанализируй контекст экрана через Gemini и нажми на самый нужный целевой элемент"
                                }
                                AuraOverlayManager.onQuickCommandRequested?.invoke(cmd)
                            },
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("run_ai_context_command_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ИИ-Клик")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Exact Pixel Coordinate Tapper (with Crosshair)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = coordXInput,
                            onValueChange = { coordXInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("X (px)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("coord_x_input"),
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = coordYInput,
                            onValueChange = { coordYInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Y (px)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("coord_y_input"),
                            shape = RoundedCornerShape(14.dp)
                        )
                        FilledTonalButton(
                            onClick = {
                                val x = coordXInput.toIntOrNull() ?: 540
                                val y = coordYInput.toIntOrNull() ?: 960
                                val res = systemController.tapExactPixelInApp(x, y, "Точный тест ($x, $y)")
                                onFeedbackSpoken(res)
                            },
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("tap_exact_coordinates_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Тык (X,Y)")
                        }
                    }

                    if (screenElements.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Интерактивная сетка элементов и пиксельных координат (нажмите для точного клика):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            screenElements.take(25).forEach { el ->
                                AssistChip(
                                    onClick = {
                                        coordXInput = el.centerX.toString()
                                        coordYInput = el.centerY.toString()
                                        val res = systemController.clickElementByIndex(el.index, el.label)
                                        onFeedbackSpoken(res)
                                    },
                                    label = {
                                        Text("#${el.index} ${el.label.take(20)} (${el.centerX},${el.centerY}px • ${el.xPercent}%,${el.yPercent}%)")
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.TouchApp,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Accessibility Service Full Control Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (a11yActive) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
                        },
                        RoundedCornerShape(24.dp)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (a11yActive) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessibilityNew,
                                    contentDescription = null,
                                    tint = if (a11yActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.tertiary
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Специальные возможности Aura",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (a11yActive) {
                                        "АКТИВНО • Полный доступ к экрану и жестам"
                                    } else {
                                        "Включите в настройках для нажатий и ввода в любых приложениях"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (a11yActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val msg = systemController.openAccessibilitySettings()
                            onFeedbackSpoken(msg)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("open_accessibility_settings_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (a11yActive) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = if (a11yActive) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    ) {
                        Icon(Icons.Default.AccessibilityNew, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (a11yActive) {
                                "Настройки специальных возможностей (Включено)"
                            } else {
                                "Включить полное управление (Accessibility)"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Глобальные системные действия:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SystemPanelButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            label = "Назад",
                            active = a11yActive,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val res = systemController.executeGlobalAccessibilityAction("BACK")
                                onFeedbackSpoken(res)
                            }
                        )
                        SystemPanelButton(
                            icon = Icons.Default.Home,
                            label = "Домой",
                            active = a11yActive,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val res = systemController.executeGlobalAccessibilityAction("HOME")
                                onFeedbackSpoken(res)
                            }
                        )
                        SystemPanelButton(
                            icon = Icons.Default.Notifications,
                            label = "Шторка",
                            active = a11yActive,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val res = systemController.executeGlobalAccessibilityAction("NOTIFICATIONS")
                                onFeedbackSpoken(res)
                            }
                        )
                        SystemPanelButton(
                            icon = Icons.Default.CameraAlt,
                            label = "Скриншот",
                            active = a11yActive,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val res = systemController.executeGlobalAccessibilityAction("SCREENSHOT")
                                onFeedbackSpoken(res)
                            }
                        )
                        SystemPanelButton(
                            icon = Icons.Default.Lock,
                            label = "Экран",
                            active = a11yActive,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val res = systemController.executeGlobalAccessibilityAction("LOCK_SCREEN")
                                onFeedbackSpoken(res)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = lastA11yReport,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        // Universal Cross-App Action Executor Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Действия внутри любых приложений",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Поиск, нажатие кнопок, ввод текста, свайпы и чтение экрана",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("YouTube", "Telegram", "WhatsApp", "Google", "Карты", "Chrome").forEach { presetApp ->
                            AssistChip(
                                onClick = { targetAppName = presetApp },
                                label = { Text(presetApp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = targetAppName,
                        onValueChange = { targetAppName = it },
                        label = { Text("Целевое приложение") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cross_app_target_input"),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = inAppActionPayload,
                        onValueChange = { inAppActionPayload = it },
                        label = { Text("Текст для поиска, ввода или название кнопки") },
                        placeholder = { Text("Например: Музыка для работы / Привет! / Подписаться") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cross_app_payload_input"),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val res = systemController.searchInAppOrWeb(
                                    targetAppName.ifBlank { "Google" },
                                    inAppActionPayload.ifBlank { "Популярное" }
                                )
                                onFeedbackSpoken(res)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("execute_in_app_search_button")
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Выполнить в приложении")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (targetAppName.isNotBlank()) {
                                    systemController.openAppByQuery(targetAppName)
                                }
                                val res = systemController.clickUiElementInApp(
                                    inAppActionPayload.ifBlank { "Поиск" },
                                    delayMs = 1300L
                                )
                                onFeedbackSpoken(res)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("click_in_app_button")
                        ) {
                            Text("Нажать кнопку")
                        }
                        FilledTonalButton(
                            onClick = {
                                if (targetAppName.isNotBlank()) {
                                    systemController.openAppByQuery(targetAppName)
                                }
                                val res = systemController.inputTextInApp(
                                    "",
                                    inAppActionPayload.ifBlank { "Привет!" },
                                    delayMs = 1400L
                                )
                                onFeedbackSpoken(res)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("type_in_app_button")
                        ) {
                            Text("Ввести текст")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val res = systemController.scrollInApp("DOWN")
                                onFeedbackSpoken(res)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SwipeDown, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Скролл вниз")
                        }
                        OutlinedButton(
                            onClick = {
                                val res = systemController.scrollInApp("UP")
                                onFeedbackSpoken(res)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SwipeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Скролл вверх")
                        }
                        OutlinedButton(
                            onClick = {
                                val res = systemController.readCurrentScreenContent()
                                onFeedbackSpoken(res)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("read_screen_button")
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Экран")
                        }
                    }
                }
            }
        }

        // Media Playback Universal Controller
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Управление медиа и плеерами",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val res = systemController.controlMediaPlayback("PREVIOUS")
                                onFeedbackSpoken(res)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущий")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Пред.")
                        }
                        Button(
                            onClick = {
                                val res = systemController.controlMediaPlayback("PLAY_PAUSE")
                                onFeedbackSpoken(res)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Пуск / Пауза")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Пуск / Пауза")
                        }
                        FilledTonalButton(
                            onClick = {
                                val res = systemController.controlMediaPlayback("NEXT")
                                onFeedbackSpoken(res)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Следующий")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("След.")
                        }
                    }
                }
            }
        }

        // Quick Hardware Tile Row: Flashlight & Battery
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                        placeholder = { Text("Поиск любого приложения на телефоне...") },
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

        items(filteredApps.take(40), key = { it.packageName }) { appInfo ->
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
