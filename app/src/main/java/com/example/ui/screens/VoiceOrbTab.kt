package com.example.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.CommandLog
import com.example.ui.LastExecutionCard
import com.example.ui.UiState
import com.example.voice.VoiceOrbMode
import kotlin.math.cos
import kotlin.math.sin

private val QUICK_VOICE_COMMANDS = listOf(
    "Включи фонарик",
    "Выключи фонарик",
    "Поставь будильник на 07:30",
    "Таймер на 5 минут",
    "Громкость на 80%",
    "Яркость экрана 90%",
    "Открой настройки Wi-Fi",
    "Добавь задачу купить кофе",
    "Запиши заметку идеи для проекта",
    "Позвони маме"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceOrbTab(
    orbMode: VoiceOrbMode,
    liveTranscript: String,
    rmsAmplitude: Float,
    isTtsEnabled: Boolean,
    voiceStatusMessage: String,
    lastExecution: LastExecutionCard,
    historyState: UiState<List<CommandLog>>,
    onMicToggleClick: () -> Unit,
    onToggleTts: () -> Unit,
    onReplaySpeech: (String) -> Unit,
    onSubmitCommand: (String, Activity?) -> Unit,
    onDeleteLog: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var textCommand by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            // Voice Orb Interactive Stage
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = when (orbMode) {
                                VoiceOrbMode.LISTENING -> MaterialTheme.colorScheme.primaryContainer
                                VoiceOrbMode.SPEAKING -> MaterialTheme.colorScheme.secondaryContainer
                                VoiceOrbMode.THINKING -> MaterialTheme.colorScheme.tertiaryContainer
                                VoiceOrbMode.IDLE -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = when (orbMode) {
                                        VoiceOrbMode.LISTENING -> MaterialTheme.colorScheme.primary
                                        VoiceOrbMode.SPEAKING -> MaterialTheme.colorScheme.secondary
                                        VoiceOrbMode.THINKING -> MaterialTheme.colorScheme.tertiary
                                        VoiceOrbMode.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when (orbMode) {
                                        VoiceOrbMode.IDLE -> "ГОЛОСОВОЙ ПУЛЬТ ГОТОВ"
                                        VoiceOrbMode.LISTENING -> "СЛУШАЮ МИКРОФОН..."
                                        VoiceOrbMode.THINKING -> "GEMINI ВЫПОЛНЯЕТ..."
                                        VoiceOrbMode.SPEAKING -> "ОЗВУЧИВАНИЕ ОТВЕТА..."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        IconButton(
                            onClick = onToggleTts,
                            modifier = Modifier.testTag("toggle_tts_button")
                        ) {
                            Icon(
                                imageVector = if (isTtsEnabled) {
                                    Icons.AutoMirrored.Filled.VolumeUp
                                } else {
                                    Icons.AutoMirrored.Filled.VolumeOff
                                },
                                contentDescription = if (isTtsEnabled) "Озвучивание включено" else "Озвучивание выключено",
                                tint = if (isTtsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Animated Energy Orb
                    InteractiveVoiceOrb(
                        orbMode = orbMode,
                        rmsAmplitude = rmsAmplitude,
                        onClick = onMicToggleClick
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (liveTranscript.isNotBlank() && orbMode == VoiceOrbMode.LISTENING) {
                            "«$liveTranscript»"
                        } else {
                            voiceStatusMessage
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (orbMode == VoiceOrbMode.LISTENING) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Instant Voice Command Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QUICK_VOICE_COMMANDS.forEachIndexed { index, cmd ->
                            AssistChip(
                                onClick = { onSubmitCommand(cmd, activity) },
                                label = { Text(cmd) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = when {
                                            cmd.contains("фонарик") -> Icons.Default.FlashlightOn
                                            cmd.contains("будильник") || cmd.contains("Таймер") -> Icons.Default.Alarm
                                            cmd.contains("Позвони") -> Icons.Default.Phone
                                            else -> Icons.Default.Mic
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    leadingIconContentColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.testTag("quick_cmd_chip_$index")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Fallback or precision text command bar
                    OutlinedTextField(
                        value = textCommand,
                        onValueChange = { textCommand = it },
                        placeholder = { Text("Скажите или введите команду для Android...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("assistant_command_input"),
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (textCommand.isNotBlank()) {
                                    onSubmitCommand(textCommand, activity)
                                    textCommand = ""
                                }
                            }
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (textCommand.isNotBlank()) {
                                        onSubmitCommand(textCommand, activity)
                                        textCommand = ""
                                    }
                                },
                                modifier = Modifier.testTag("send_command_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Отправить команду",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            }
        }

        // Last Spoken Response & Executed Android Actions Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        RoundedCornerShape(24.dp)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ПОСЛЕДНИЙ ОТВЕТ AURA",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        IconButton(
                            onClick = { onReplaySpeech(lastExecution.assistantReply) },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("replay_tts_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay,
                                contentDescription = "Озвучить снова",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    Text(
                        text = "Вы: «${lastExecution.userQuery}»",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = lastExecution.assistantReply,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        lastExecution.actionBadges.forEach { badge ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "✓ $badge",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Cloud Synced Command History
        item {
            Text(
                text = "Журнал голосовых команд (Облако)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        when (historyState) {
            is UiState.Loading -> {
                item {
                    Text(
                        text = "Синхронизация журнала команд...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is UiState.Error -> {
                item {
                    Text(
                        text = historyState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            is UiState.Success -> {
                if (historyState.data.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Text(
                                text = "История пока пуста. Произнесите команду или нажмите на любой быстрый чип выше.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(historyState.data.take(15), key = { it.id }) { log ->
                        CommandHistoryCard(
                            log = log,
                            onReplay = { onSubmitCommand(log.userQuery, activity) },
                            onSpeak = { onReplaySpeech(log.assistantReply) },
                            onDelete = { onDeleteLog(log.id) }
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun InteractiveVoiceOrb(
    orbMode: VoiceOrbMode,
    rmsAmplitude: Float,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbPulse")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbPhase"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbScale"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    val activeColor = when (orbMode) {
        VoiceOrbMode.IDLE -> primaryColor
        VoiceOrbMode.LISTENING -> primaryColor
        VoiceOrbMode.THINKING -> tertiaryColor
        VoiceOrbMode.SPEAKING -> secondaryColor
    }

    Box(
        modifier = Modifier
            .size(190.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("voice_orb_button"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.28f
            val dynamicBoost = when (orbMode) {
                VoiceOrbMode.LISTENING -> 1f + (rmsAmplitude * 0.45f)
                VoiceOrbMode.SPEAKING -> pulseScale
                VoiceOrbMode.THINKING -> 1.05f
                VoiceOrbMode.IDLE -> 0.95f + (sin(phase) * 0.04f)
            }

            // Outer ambient halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activeColor.copy(alpha = 0.36f),
                        secondaryColor.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.75f * dynamicBoost
                ),
                radius = baseRadius * 1.75f * dynamicBoost,
                center = center
            )

            // Acoustic wave rings
            for (ring in 1..3) {
                val ringRadius = baseRadius * (1.05f + ring * 0.18f) * dynamicBoost
                drawCircle(
                    color = activeColor.copy(alpha = (0.4f / ring)),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = (3.5f - ring * 0.7f).dp.toPx())
                )
            }

            // Orbiting energy nodes when Thinking or Speaking
            val nodeCount = 8
            for (i in 0 until nodeCount) {
                val angle = phase + (i * (2 * Math.PI / nodeCount)).toFloat()
                val orbitR = baseRadius * 1.25f * dynamicBoost
                val nx = center.x + cos(angle) * orbitR
                val ny = center.y + sin(angle) * orbitR
                drawCircle(
                    color = if (i % 2 == 0) activeColor else secondaryColor,
                    radius = 4.dp.toPx(),
                    center = Offset(nx, ny)
                )
            }

            // Inner glowing core
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(activeColor, secondaryColor)
                ),
                radius = baseRadius * 0.92f,
                center = center
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = when (orbMode) {
                    VoiceOrbMode.LISTENING -> Icons.Default.Stop
                    VoiceOrbMode.SPEAKING -> Icons.AutoMirrored.Filled.VolumeUp
                    else -> Icons.Default.Mic
                },
                contentDescription = "Голосовое управление",
                tint = Color(0xFF001F15),
                modifier = Modifier.size(38.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (orbMode) {
                    VoiceOrbMode.LISTENING -> "СТОП"
                    VoiceOrbMode.SPEAKING -> "ГОВОРИТ"
                    VoiceOrbMode.THINKING -> "ИИ..."
                    VoiceOrbMode.IDLE -> "ГОЛОС"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF001F15)
            )
        }
    }
}

@Composable
private fun CommandHistoryCard(
    log: CommandLog,
    onReplay: () -> Unit,
    onSpeak: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "«${log.userQuery}»",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    IconButton(onClick = onSpeak, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Озвучить ответ",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onReplay, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Повторить команду",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Удалить запись",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Text(
                text = log.assistantReply,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            AnimatedVisibility(visible = log.executedActions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    log.executedActions.forEach { act ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = act,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
