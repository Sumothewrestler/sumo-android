package `in`.rfidpro.sumo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import `in`.rfidpro.sumo.api.ChatReq
import `in`.rfidpro.sumo.api.PentadClient
import `in`.rfidpro.sumo.service.AudioPlayback
import `in`.rfidpro.sumo.service.DueTaskService
import `in`.rfidpro.sumo.service.SpeechCapture
import `in`.rfidpro.sumo.ui.SumoCyan500
import `in`.rfidpro.sumo.ui.SumoRose500
import `in`.rfidpro.sumo.ui.SumoSlate900
import `in`.rfidpro.sumo.ui.SumoTeal600
import `in`.rfidpro.sumo.ui.SumoTheme
import `in`.rfidpro.sumo.ui.SumoZinc300
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The single screen of the app.
 *
 * Lifecycle:
 *   - onCreate → not logged in → LoginActivity
 *   - onCreate → logged in + intent extra auto_listen=true (default) →
 *     auto-trigger the voice loop
 *   - Voice loop: Listening → Thinking → Speaking → Idle
 *   - Tapping the big mic chip restarts the voice loop (manual retrigger)
 */
class MainActivity : ComponentActivity() {

    private val viewModel by lazy { VoiceViewModel(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PentadClient.tokens.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        val autoListen = intent?.getBooleanExtra("auto_listen", true) ?: true
        setContent {
            SumoTheme {
                MainScreen(
                    viewModel = viewModel,
                    autoListenOnFirstCompose = autoListen,
                    onLogout = {
                        viewModel.stop()
                        DueTaskService.stop(this)
                        PentadClient.logout()
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-launch from launcher / Assistant → kick the voice loop again
        if (intent.getBooleanExtra("auto_listen", true)) {
            viewModel.startListening()
        }
    }

    override fun onDestroy() {
        viewModel.stop()
        super.onDestroy()
    }
}


// ----- Voice state ----------------------------------------------------

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }


class VoiceViewModel(val activity: ComponentActivity) {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state = _state.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText = _statusText.asStateFlow()

    private val speech = SpeechCapture(activity)
    private val playback = AudioPlayback(activity)
    private val tokens = PentadClient.tokens

    fun startListening() {
        // Permission check — if mic permission isn't granted yet, we
        // can't start the recognizer. The caller's permission launcher
        // should have already prompted; if it hasn't, we go to ERROR
        // with a message.
        if (!hasMicPermission()) {
            _state.value = VoiceState.ERROR
            _statusText.value = "Microphone permission denied — enable it in Settings."
            return
        }
        _state.value = VoiceState.LISTENING
        _statusText.value = null
        val lang = tokens.voiceLanguage
        speech.start(
            languageTag = lang,
            onResult = { transcript -> processTranscript(transcript) },
            onError = { _, message ->
                _state.value = VoiceState.ERROR
                _statusText.value = message
            },
        )
    }

    private fun processTranscript(transcript: String) {
        _state.value = VoiceState.THINKING
        activity.lifecycleScope.launch {
            try {
                val chatResp = PentadClient.api.chat(ChatReq(message = transcript))
                val body = chatResp.body()
                if (!chatResp.isSuccessful || body?.reply.isNullOrBlank()) {
                    _state.value = VoiceState.ERROR
                    _statusText.value = "Couldn't reach Sumo's brain."
                    return@launch
                }
                val reply = body!!.reply!!
                _statusText.value = reply
                _state.value = VoiceState.SPEAKING
                val lang = tokens.voiceLanguage
                val translateFrom = if (lang == "en-IN") null else "en-IN"
                playback.speak(reply, languageCode = lang, translateFrom = translateFrom)
                _state.value = VoiceState.IDLE
            } catch (_: IOException) {
                _state.value = VoiceState.ERROR
                _statusText.value = "Network error — try again."
            } catch (t: Throwable) {
                _state.value = VoiceState.ERROR
                _statusText.value = "Unexpected: ${t.message}"
            }
        }
    }

    fun stop() {
        speech.stop()
        playback.stop()
        _state.value = VoiceState.IDLE
        _statusText.value = null
    }

    private fun hasMicPermission(): Boolean =
        ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

// Convenience — lifecycleScope from a ComponentActivity in Kotlin.
private fun ComponentActivity.lifecycleScope() =
    androidx.lifecycle.lifecycleScope


// ----- Compose UI -----------------------------------------------------

@Composable
private fun MainScreen(
    viewModel: VoiceViewModel,
    autoListenOnFirstCompose: Boolean,
    onLogout: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val statusText by viewModel.statusText.collectAsStateWithLifecycle()
    val tokens = PentadClient.tokens

    var currentLang by remember { mutableStateOf(tokens.voiceLanguage) }
    var remindersOn by remember { mutableStateOf(tokens.remindersEnabled) }

    // Permission launchers — we ask for mic first, then notification
    // (Android 13+) when the user enables reminders.
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startListening()
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            remindersOn = true
            tokens.remindersEnabled = true
            DueTaskService.start(viewModel.activity)
        }
    }

    // Auto-listen on first compose. Only fires once per Activity instance
    // (onNewIntent handles re-launches).
    LaunchedEffect(Unit) {
        if (autoListenOnFirstCompose) {
            val granted = ActivityCompat.checkSelfPermission(
                viewModel.activity,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.startListening()
            else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = SumoSlate900,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))

            // Big mic chip — silver-bezel + teal-cyan, same vocabulary
            // as the webapp launcher.
            MicChip(state = state, onClick = {
                if (state == VoiceState.LISTENING || state == VoiceState.SPEAKING) {
                    viewModel.stop()
                } else {
                    viewModel.startListening()
                }
            })

            // Big status line — what's happening right now.
            Text(
                text = when (state) {
                    VoiceState.IDLE -> "Tap to talk · or say \"Hey Google, open Sumo\""
                    VoiceState.LISTENING -> "Listening…"
                    VoiceState.THINKING -> "Thinking…"
                    VoiceState.SPEAKING -> "Speaking…"
                    VoiceState.ERROR -> "Something went wrong"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                fontWeight = FontWeight.Medium,
            )

            // Last reply / error text — surfaces transcripts + errors
            statusText?.takeIf { it.isNotBlank() }?.let {
                Box(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state == VoiceState.ERROR) SumoRose500
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Controls row — language + reminders + logout
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LanguageRow(
                    current = currentLang,
                    onChange = {
                        currentLang = it
                        tokens.voiceLanguage = it
                    },
                )
                RemindersRow(
                    enabled = remindersOn,
                    onToggle = {
                        if (remindersOn) {
                            DueTaskService.stop(viewModel.activity)
                            tokens.remindersEnabled = false
                            remindersOn = false
                        } else {
                            // Notification permission only matters on 13+
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val granted = ActivityCompat.checkSelfPermission(
                                    viewModel.activity,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    tokens.remindersEnabled = true
                                    remindersOn = true
                                    DueTaskService.start(viewModel.activity)
                                } else {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            } else {
                                tokens.remindersEnabled = true
                                remindersOn = true
                                DueTaskService.start(viewModel.activity)
                            }
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Logout (${tokens.username ?: "unknown"})")
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Sumo · Pentad",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun MicChip(state: VoiceState, onClick: () -> Unit) {
    val (innerStart, innerEnd) = when (state) {
        VoiceState.LISTENING -> SumoRose500 to Color(0xFFE11D48)
        VoiceState.THINKING, VoiceState.SPEAKING -> SumoCyan500 to SumoTeal600
        VoiceState.ERROR -> Color(0xFF7F1D1D) to Color(0xFF991B1B)
        VoiceState.IDLE -> SumoTeal600 to SumoCyan500
    }
    val label = when (state) {
        VoiceState.LISTENING -> "Stop"
        VoiceState.THINKING -> "…"
        VoiceState.SPEAKING -> "🔊"
        VoiceState.ERROR -> "Retry"
        VoiceState.IDLE -> "Tap"
    }
    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(SumoZinc300, Color.White, SumoZinc300)
                )
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(innerStart, innerEnd)))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(onClick = onClick) {
                Text(
                    label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
            }
        }
    }
}


@Composable
private fun LanguageRow(
    current: String,
    onChange: (String) -> Unit,
) {
    val options = listOf(
        "en-IN" to "English",
        "hi-IN" to "हिंदी",
        "ta-IN" to "தமிழ்",
        "te-IN" to "తెలుగు",
        "kn-IN" to "ಕನ್ನಡ",
        "ml-IN" to "മലയാളം",
        "bn-IN" to "বাংলা",
        "mr-IN" to "मराठी",
        "gu-IN" to "ગુજરાતી",
        "pa-IN" to "ਪੰਜਾਬੀ",
    )
    var open by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == current }?.second ?: current
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            shape = RoundedCornerShape(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Voice: $currentLabel", color = MaterialTheme.colorScheme.onBackground)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            options.forEach { (code, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onChange(code)
                        open = false
                    },
                )
            }
        }
    }
}


@Composable
private fun RemindersRow(enabled: Boolean, onToggle: () -> Unit) {
    Button(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.error
            else SumoTeal600,
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(vertical = 12.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Icon(
            if (enabled) Icons.Default.NotificationsOff else Icons.Default.Notifications,
            contentDescription = null,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            if (enabled) "Stop spoken reminders" else "Start spoken reminders",
            fontWeight = FontWeight.SemiBold,
        )
    }
}
