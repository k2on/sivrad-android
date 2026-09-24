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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.sivrad.assistant.AssistantEngine
import com.sivrad.assistant.models.ModelCatalog
import com.sivrad.assistant.models.ModelDownloader
import com.sivrad.assistant.models.ModelOption
import com.sivrad.assistant.models.ModelSlot
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
    // Bumped when files are deleted, so rows re-check the disk.
    var diskVersion by remember { mutableIntStateOf(0) }
    val missing = remember(state, settings, diskVersion) { app.catalog.missingForSelection() }
    val busy = state is ModelDownloader.State.Running

    Section("2. Models") {
        Text(
            "Pick one model per slot. Download several to compare them: the overlay shows timings under each reply. " +
                "Files go to device-protected storage so the assistant works before the first unlock. Wi-Fi recommended.",
            style = MaterialTheme.typography.bodySmall,
        )
        DownloadProgress(state, onPause = { app.downloader.cancel() })
        if (missing.isNotEmpty() && !busy) {
            val mb = missing.sumOf { it.sizeBytes ?: 0L } / 1_000_000
            Button(onClick = { app.downloader.start(missing) { app.engine.reload() } }) {
                Text("Download selected models (${mb} MB)")
            }
        } else if (missing.isEmpty()) {
            Text("✓ Everything selected is downloaded and verified.", style = MaterialTheme.typography.bodyMedium)
        }
        for (slot in ModelSlot.entries) {
            HorizontalDivider()
            Text(slot.title, style = MaterialTheme.typography.titleSmall)
            val selectedId = app.catalog.selected(slot).id
            for (option in app.catalog.options(slot)) {
                key(option.id, diskVersion, state) {
                    ModelRow(
                        option = option,
                        selected = option.id == selectedId,
                        downloaded = app.catalog.isDownloaded(option),
                        busy = busy,
                        onSelect = {
                            app.settings.select(slot, option.id)
                            app.engine.reload()
                        },
                        onDownload = {
                            app.downloader.start(option.files) {
                                if (app.catalog.selected(slot).id == option.id) app.engine.reload()
                            }
                        },
                        onDelete = {
                            app.catalog.delete(option)
                            diskVersion++
                            if (option.id == selectedId) app.engine.reload()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: ModelDownloader.State, onPause: () -> Unit) {
    when (state) {
        is ModelDownloader.State.Running -> {
            val frac = state.total?.let { (state.bytes.toFloat() / it).coerceIn(0f, 1f) }
            Text(
                "${if (state.verifying) "Verifying" else "Downloading"} ${state.file} (${state.index + 1}/${state.count})" +
                    (state.total?.let { " — ${state.bytes / 1_000_000} / ${it / 1_000_000} MB" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (frac != null && !state.verifying) {
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = onPause) { Text("Pause") }
        }
        is ModelDownloader.State.Failed ->
            Text("Download failed: ${state.message}", color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

@Composable
private fun ModelRow(
    option: ModelOption,
    selected: Boolean,
    downloaded: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.bodyMedium)
            Text(option.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            option.files.isEmpty() -> Unit
            downloaded -> TextButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
            else -> TextButton(onClick = onDownload, enabled = !busy) {
                Text(option.totalBytes?.let { "Get ${it / 1_000_000} MB" } ?: "Get")
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
    var url by rememberSaveable(current) { mutableStateOf(current.customLlmUrl) }
    var sha by rememberSaveable(current) { mutableStateOf(current.customLlmSha256) }
    var noThinking by rememberSaveable(current) { mutableStateOf(current.customLlmNoThinking) }
    var threads by rememberSaveable(current) { mutableStateOf(current.llmThreads.toString()) }
    var showStats by rememberSaveable(current) { mutableStateOf(current.showStats) }
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
            value = threads, onValueChange = { threads = it.filter(Char::isDigit).take(1) },
            label = { Text("Inference threads (1–8)") },
            supportingText = { Text("Tensor G3 has 1 big, 4 medium and 4 small cores; try 3–5 and compare tok/s.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showStats, onCheckedChange = { showStats = it })
            Text("Show timings under replies", style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
        Text("Custom language model", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("GGUF URL (blank = none)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sha, onValueChange = { sha = it },
            label = { Text("SHA-256 (blank = don't verify)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = noThinking, onCheckedChange = { noThinking = it })
            Text("Hybrid thinking model (Qwen3 base releases)", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "It appears under Language model once saved. It must use a chat template llama.cpp recognises " +
                "(ChatML-style works best) and Qwen's Hermes <tool_call> format.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = {
            val reload = threads.toIntOrNull() != current.llmThreads ||
                (current.llmModel == ModelCatalog.CUSTOM_LLM &&
                    (url.trim() != current.customLlmUrl || noThinking != current.customLlmNoThinking))
            app.settings.update {
                it.copy(
                    httpAllowlist = allowlist.lines().map(String::trim).filter(String::isNotEmpty),
                    customLlmUrl = url,
                    customLlmSha256 = sha,
                    customLlmNoThinking = noThinking,
                    llmThreads = threads.toIntOrNull() ?: it.llmThreads,
                    showStats = showStats,
                )
            }
            if (reload) app.engine.reload()
        }) { Text("Save") }
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
