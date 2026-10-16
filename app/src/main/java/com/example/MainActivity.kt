package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.auth.AuthScreen
import com.example.auth.signOutUser
import com.example.data.repository.AssistantRepository
import com.example.ui.AssistantViewModel
import com.example.ui.screens.DeviceControlTab
import com.example.ui.screens.OrganizerTab
import com.example.ui.screens.RoutinesTab
import com.example.ui.screens.VoiceOrbTab
import com.example.ui.theme.MyApplicationTheme
import com.example.voice.VoiceOrbMode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AppNavigation()
            }
        }
    }
}

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@Composable
fun AppNavigation() {
    val auth = remember { FirebaseAuth.getInstance() }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(
            onAuthSuccess = {}
        )
    } else {
        MainAssistantScaffold(
            currentUserId = user.uid,
            userEmail = user.email ?: user.displayName ?: "Google Аккаунт"
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAssistantScaffold(
    currentUserId: String,
    userEmail: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: AssistantViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                AssistantRepository(db, FirebaseAuth.getInstance()).let { repo ->
                    AssistantViewModel(repo, currentUserId, app)
                }
            }
        }
    )

    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val orbMode by viewModel.orbMode.collectAsStateWithLifecycle()
    val liveTranscript by viewModel.liveTranscript.collectAsStateWithLifecycle()
    val rmsAmplitude by viewModel.rmsAmplitude.collectAsStateWithLifecycle()
    val isTtsEnabled by viewModel.isTtsEnabled.collectAsStateWithLifecycle()
    val voiceStatusMessage by viewModel.voiceStatusMessage.collectAsStateWithLifecycle()
    val lastExecution by viewModel.lastExecution.collectAsStateWithLifecycle()
    val smartItemsState by viewModel.smartItemsState.collectAsStateWithLifecycle()
    val routinesState by viewModel.routinesState.collectAsStateWithLifecycle()
    val historyState by viewModel.historyState.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var pendingCallTarget by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissBanner()
        }
    }

    val speechRecognizerIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spokenList = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
        val bestTranscript = spokenList?.firstOrNull()?.trim().orEmpty()
        if (bestTranscript.isNotEmpty()) {
            viewModel.executeVoiceOrTextCommand(bestTranscript, context as? android.app.Activity)
        } else {
            viewModel.voiceEngine.stopListening()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(
                                text = stringResource(id = R.string.app_name),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = userEmail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${telemetry.batteryPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            signOutUser(
                                credentialManager = credentialManager,
                                onSignOutComplete = {},
                                scope = scope
                            )
                        },
                        modifier = Modifier.testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Выйти из аккаунта"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                    label = { Text(stringResource(id = R.string.tab_assistant)) },
                    modifier = Modifier.testTag("nav_tab_assistant")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        viewModel.systemController.refreshTelemetry()
                        selectedTab = 1
                    },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text(stringResource(id = R.string.tab_device)) },
                    modifier = Modifier.testTag("nav_tab_device")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Checklist, contentDescription = null) },
                    label = { Text(stringResource(id = R.string.tab_organizer)) },
                    modifier = Modifier.testTag("nav_tab_organizer")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                    label = { Text(stringResource(id = R.string.tab_routines)) },
                    modifier = Modifier.testTag("nav_tab_routines")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> VoiceOrbTab(
                    orbMode = orbMode,
                    liveTranscript = liveTranscript,
                    rmsAmplitude = rmsAmplitude,
                    isTtsEnabled = isTtsEnabled,
                    voiceStatusMessage = voiceStatusMessage,
                    lastExecution = lastExecution,
                    historyState = historyState,
                    onMicToggleClick = {
                        if (orbMode == VoiceOrbMode.LISTENING) {
                            viewModel.voiceEngine.stopListening()
                        } else {
                            try {
                                val speechIntent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Скажите команду для Aura Control")
                                }
                                speechRecognizerIntentLauncher.launch(speechIntent)
                            } catch (_: Exception) {
                                viewModel.voiceEngine.startListening()
                            }
                        }
                    },
                    onToggleTts = { viewModel.voiceEngine.toggleTtsEnabled() },
                    onReplaySpeech = { text -> viewModel.voiceEngine.speak(text) },
                    onSubmitCommand = { query, activity ->
                        viewModel.executeVoiceOrTextCommand(query, activity)
                    },
                    onDeleteLog = { logId -> viewModel.deleteHistoryLog(logId) }
                )

                1 -> DeviceControlTab(
                    telemetry = telemetry,
                    systemController = viewModel.systemController,
                    onRequestCallPermissionAndDial = { target ->
                        val res = viewModel.systemController.callContactOrNumber(target)
                        viewModel.voiceEngine.speak(res)
                    },
                    onFeedbackSpoken = { spokenText ->
                        viewModel.voiceEngine.speak(spokenText)
                    }
                )

                2 -> OrganizerTab(
                    smartItemsState = smartItemsState,
                    onCreateItem = { title, content, cat, time ->
                        viewModel.addSmartItem(title, content, cat, time)
                    },
                    onToggleCompleted = { item -> viewModel.toggleSmartItem(item) },
                    onDeleteItem = { itemId -> viewModel.deleteSmartItem(itemId) }
                )

                3 -> RoutinesTab(
                    routinesState = routinesState,
                    onRunRoutine = { routine, activity ->
                        viewModel.runAutomationRoutine(routine, activity)
                    },
                    onCreateRoutine = { title, phrase, icon, steps ->
                        viewModel.createRoutine(title, phrase, icon, steps)
                    },
                    onSeedStarterRoutines = { viewModel.seedStarterRoutinesIfNeeded() },
                    onDeleteRoutine = { routineId -> viewModel.deleteRoutine(routineId) }
                )
            }
        }
    }
}
