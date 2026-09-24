package com.sivrad.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sivrad.assistant.service.Phase
import com.sivrad.assistant.service.UiState
import com.sivrad.core.tools.Confirmation
import kotlinx.coroutines.flow.StateFlow

@Composable
fun AssistantOverlay(
    state: StateFlow<UiState>,
    onStop: () -> Unit,
    onListen: () -> Unit,
    onRetry: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val s by state.collectAsState()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .navigationBarsPadding()
                // Swallow taps so they do not reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusLine(s)
                Conversation(s)
                s.confirmation?.let { ConfirmationCard(it, onConfirm) }
                Controls(s, onStop, onListen, onRetry)
            }
        }
    }
}

@Composable
private fun StatusLine(s: UiState) {
    val busy = s.phase in setOf(Phase.Loading, Phase.Thinking, Phase.ExecutingTool, Phase.Unlocking)
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            s.phase == Phase.Listening -> Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.error),
            )
            busy -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        if (s.phase == Phase.Listening || busy) Spacer(Modifier.width(8.dp))
        val label = s.status.ifEmpty {
            when (s.phase) {
                Phase.Idle -> "Sivrad"
                Phase.Thinking -> "Thinking…"
                Phase.ExecutingTool -> "Running tool…"
                else -> ""
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (s.phase == Phase.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Conversation(s: UiState) {
    val scroll = rememberScrollState()
    LaunchedEffect(s.transcript, s.response, s.earlier.size) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        Modifier
            .heightIn(max = 360.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (e in s.earlier) {
            Text(e.user, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(e.reply, style = MaterialTheme.typography.bodyMedium)
        }
        if (s.transcript.isNotEmpty()) {
            Text(
                s.transcript,
                style = MaterialTheme.typography.titleMedium,
                color = if (s.phase == Phase.Listening) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        } else if (s.phase == Phase.Listening) {
            Text("Say something…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
        }
        if (s.response.isNotEmpty()) {
            Text(s.response, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ConfirmationCard(c: Confirmation, onConfirm: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium)
            for ((k, v) in c.fields) {
                Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, style = MaterialTheme.typography.bodyLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onConfirm(false) }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onConfirm(true) }) { Text(c.confirmLabel) }
            }
        }
    }
}

@Composable
private fun Controls(s: UiState, onStop: () -> Unit, onListen: () -> Unit, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        when (s.phase) {
            Phase.Listening -> Button(onClick = onStop) { Text("Done") }
            Phase.Thinking, Phase.ExecutingTool, Phase.Unlocking, Phase.Confirming, Phase.Loading ->
                OutlinedButton(onClick = onStop) { Text("Stop") }
            Phase.Idle, Phase.Error -> {
                if (s.canRetry) OutlinedButton(onClick = onRetry) { Text("Retry") }
                Button(onClick = onListen) { Text("Speak") }
            }
        }
    }
}
