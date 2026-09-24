package com.sivrad.assistant.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sivrad.assistant.AssistantEngine
import com.sivrad.assistant.models.ModelDownloader
import com.sivrad.assistant.service.AssistantService
import com.sivrad.assistant.sivrad
import androidx.compose.runtime.LaunchedEffect

/**
 * First-run and settings screen: permissions, model download, choosing
 * Sivrad as the default assistant, and the few settings there are.
 */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SivradTheme {
                Surface(Modifier.fillMaxSize()) { SetupScreen() }
            }
        }
    }
}

private val RUNTIME_PERMISSIONS = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.SEND_SMS,
)

@Composable
private fun SetupScreen() {
    val ctx = LocalContext.current
    val app = ctx.sivrad
    // Re-checked whenever the screen resumes (e.g. back from Settings).
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { resumes++ }
    }

    Column(
        Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sivrad", style = MaterialTheme.typography.headlineMedium)
        Text(
            "An offline voice assistant. Speech recognition and the language model run on this phone; nothing is sent anywhere unless you allowlist a URL below.",
            style = MaterialTheme.typography.bodyMedium,
        )
        PermissionsSection(resumes)
        ModelsSection()
        AssistantSection(resumes)
        SettingsSection()
        EngineStatus(app.engine)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PermissionsSection(resumes: Int) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val granted = remember(tick, resumes) {
        RUNTIME_PERMISSIONS.associateWith { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    Section("1. Permissions") {
        for ((perm, ok) in granted) {
            Text("${if (ok) "✓" else "✗"}  ${perm.substringAfterLast('.')}", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Microphone is required. Contacts and SMS are only used by the send_sms tool, which always asks you to unlock and confirm.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (granted.values.any { !it }) {
            Button(onClick = { launcher.launch(RUNTIME_PERMISSIONS) }) { Text("Grant permissions") }
        }
    }
}

@Composable
private fun ModelsSection() {
    val app = LocalContext.current.sivrad
    val state by app.downloader.state.collectAsState()
    val settings by app.settings.values.collectAsState()
    val missing = remember(state, settings) { app.catalog.missing() }
    Section("2. Models") {
        Text(
            "About 2.6 GB from Hugging Face: streaming Zipformer ASR, Silero VAD and the GGUF language model. " +
                "Stored in device-protected storage so the assistant works before the first unlock. Wi-Fi recommended.",
            style = MaterialTheme.typography.bodySmall,
        )
        for (m in app.catalog.all()) {
            val present = m !in missing
            Text("${if (present) "✓" else "·"}  ${m.relativePath}", style = MaterialTheme.typography.bodyMedium)
        }
        when (val s = state) {
            is ModelDownloader.State.Running -> {
                val frac = s.total?.let { (s.bytes.toFloat() / it).coerceIn(0f, 1f) }
                Text(
                    "${if (s.verifying) "Verifying" else "Downloading"} ${s.file} (${s.index + 1}/${s.count})" +
                        (s.total?.let { " — ${s.bytes / 1_000_000} / ${it / 1_000_000} MB" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (frac != null && !s.verifying) {
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = { app.downloader.cancel() }) { Text("Pause") }
            }
            is ModelDownloader.State.Failed -> {
                Text("Failed: ${s.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = { app.downloader.start { app.engine.reload() } }) { Text("Retry download") }
            }
            else -> if (missing.isNotEmpty()) {
                Button(onClick = { app.downloader.start { app.engine.reload() } }) { Text("Download models") }
            } else {
                Text("All models present and verified.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AssistantSection(resumes: Int) {
    val ctx = LocalContext.current
    val active = remember(resumes) {
        VoiceInteractionService.isActiveService(ctx, ComponentName(ctx, AssistantService::class.java))
    }
    Section("3. Default assistant") {
        Text(
            if (active) "✓ Sivrad is the default digital assistant." else "Sivrad is not the default assistant yet.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }) {
            Text("Choose default assistant")
        }
        Text(
            "In the screen that opens, set \"Digital assistant app\" to Sivrad.\n\n" +
                "Then pick a gesture:\n" +
                "• Power button: Settings → System → Gestures → Press and hold power button → Digital assistant.\n" +
                "• Corner swipe (gesture navigation): Settings → System → Navigation mode → gear icon → Swipe to invoke assistant.\n\n" +
                "The overlay also opens over the lock screen. Timers and alarms work there; " +
                "texting, HTTP requests and opening apps ask you to unlock first.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingsSection() {
    val app = LocalContext.current.sivrad
    val current by app.settings.values.collectAsState()
    var allowlist by rememberSaveable(current) { mutableStateOf(current.httpAllowlist.joinToString("\n")) }
    var url by rememberSaveable(current) { mutableStateOf(current.llmUrl) }
    var sha by rememberSaveable(current) { mutableStateOf(current.llmSha256) }
    var threads by rememberSaveable(current) { mutableStateOf(current.llmThreads.toString()) }
    Section("4. Settings") {
        OutlinedTextField(
            value = allowlist,
            onValueChange = { allowlist = it },
            label = { Text("HTTP allowlist (one base URL per line)") },
            placeholder = { Text("https://homeassistant.lan:8123/api") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        HorizontalDivider()
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Language model GGUF URL") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sha, onValueChange = { sha = it },
            label = { Text("GGUF SHA-256 (blank = don't verify)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = threads, onValueChange = { threads = it.filter(Char::isDigit).take(1) },
            label = { Text("Inference threads (1–8)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "The model must use a chat template llama.cpp recognises (ChatML-style works best) and the Hermes <tool_call> format, like Qwen3.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row {
            Button(onClick = {
                val modelChanged = url.trim() != current.llmUrl || threads.toIntOrNull() != current.llmThreads
                app.settings.update {
                    it.copy(
                        httpAllowlist = allowlist.lines().map(String::trim).filter(String::isNotEmpty),
                        llmUrl = url,
                        llmSha256 = sha,
                        llmThreads = threads.toIntOrNull() ?: it.llmThreads,
                    )
                }
                if (modelChanged) app.engine.reload()
            }) { Text("Save") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                url = com.sivrad.assistant.settings.AppSettings.DEFAULT_LLM_URL
                sha = com.sivrad.assistant.settings.AppSettings.DEFAULT_LLM_SHA256
            }) { Text("Default model") }
        }
    }
}

@Composable
private fun EngineStatus(engine: AssistantEngine) {
    val status by engine.status.collectAsState()
    val text = when (val s = status) {
        AssistantEngine.Status.NotLoaded -> "Models not loaded (they load when Sivrad is the active assistant)."
        AssistantEngine.Status.MissingModels -> "Waiting for models."
        AssistantEngine.Status.Loading -> "Loading models…"
        AssistantEngine.Status.Ready -> "Ready. Use the assistant gesture to talk."
        is AssistantEngine.Status.Failed -> "Model loading failed: ${s.message}"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
