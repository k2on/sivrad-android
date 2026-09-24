package com.sivrad.assistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.sivrad.assistant.service.Phase
import com.sivrad.assistant.service.UiState
import com.sivrad.core.tools.Confirmation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AssistantOverlay(
    state: StateFlow<UiState>,
    visible: StateFlow<Boolean>,
    onStop: () -> Unit,
    onListen: () -> Unit,
    onRetry: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onTypeInstead: () -> Unit,
    onDismiss: () -> Unit,
    onHidden: () -> Unit,
) {
    val s by state.collectAsState()
    val shown by visible.collectAsState()
    var typing by rememberSaveable { mutableStateOf(false) }

    // Enter when the session shows; once the exit has played out, let the
    // session actually hide its window.
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = shown
    val transition = rememberTransition(presence, label = "overlay")
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(presence.currentState, presence.isIdle) {
        if (presence.currentState) appeared = true
        if (appeared && presence.isIdle && !presence.currentState) {
            appeared = false
            typing = false
            onHidden()
        }
    }
    val scrim = transition.animateFloat(
        transitionSpec = { tween(if (targetState) 260 else 180) },
        label = "scrim",
    ) { if (it) 0.35f else 0f }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = scrim.value)) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        transition.AnimatedVisibility(
            visible = { it },
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 2 } +
                scaleIn(spring(dampingRatio = 0.8f, stiffness = 380f), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeIn(tween(160)),
            exit = slideOutVertically(tween(200, easing = FastOutLinearInEasing)) { it / 3 } +
                scaleOut(tween(200, easing = FastOutLinearInEasing), targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                fadeOut(tween(180)),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .navigationBarsPadding()
                    // Rides up with the keyboard when typing.
                    .imePadding()
                    // Swallow taps so they do not reach the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp,
            ) {
                Column(
                    Modifier
                        .animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusLine(s)
                    Conversation(s, typing)
                    AnimatedVisibility(
                        visible = s.confirmation != null,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        // Keeps the last card on screen while it animates away.
                        var last by remember { mutableStateOf(s.confirmation) }
                        s.confirmation?.let { last = it }
                        last?.let { ConfirmationCard(it, onConfirm) }
                    }
                    AnimatedContent(
                        targetState = typing,
                        transitionSpec = {
                            val dir = if (targetState) 1 else -1
                            (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) { dir * it / 2 })
                                .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(160)) { -dir * it / 2 })
                                .using(SizeTransform(clip = false))
                        },
                        contentAlignment = Alignment.BottomEnd,
                        label = "input mode",
                    ) { isTyping ->
                        if (isTyping) {
                            InputBar(
                                s,
                                onSend = onSubmit,
                                onStop = onStop,
                                onSpeak = {
                                    typing = false
                                    onListen()
                                },
                            )
                        } else {
                            Controls(
                                s,
                                onStop,
                                onListen,
                                onRetry,
                                onType = {
                                    onTypeInstead()
                                    typing = true
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(s: UiState) {
    val busy = s.phase in setOf(Phase.Loading, Phase.Thinking, Phase.ExecutingTool, Phase.Unlocking)
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            s.phase == Phase.Listening -> ListeningDot()
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
private fun ListeningDot() {
    val pulse = rememberInfiniteTransition(label = "listening")
    val scale by pulse.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dot",
    )
    Box(
        Modifier
            .size(10.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
    )
}

@Composable
private fun Conversation(s: UiState, typing: Boolean) {
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
        } else if (s.phase == Phase.Listening && !typing) {
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
private fun Controls(s: UiState, onStop: () -> Unit, onListen: () -> Unit, onRetry: () -> Unit, onType: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        when (s.phase) {
            Phase.Listening, Phase.Loading, Phase.Idle, Phase.Error -> TextButton(onClick = onType) { Text("Type") }
            else -> Unit
        }
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

@Composable
private fun InputBar(s: UiState, onSend: (String) -> Unit, onStop: () -> Unit, onSpeak: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val busy = s.phase in setOf(Phase.Loading, Phase.Thinking, Phase.ExecutingTool, Phase.Unlocking, Phase.Confirming)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // Let the bar settle in before the keyboard slides up under it.
        delay(120)
        focus.requestFocus()
        keyboard?.show()
    }
    val send: () -> Unit = {
        if (text.isNotBlank() && !busy) {
            onSend(text)
            text = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
            placeholder = { Text("Ask Sivrad…") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = {
                keyboard?.hide()
                onSpeak()
            }) { Text("Speak") }
            AnimatedContent(
                targetState = busy,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)).togetherWith(fadeOut() + scaleOut(targetScale = 0.9f)) },
                label = "send or stop",
            ) { isBusy ->
                if (isBusy) {
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                } else {
                    Button(onClick = send, enabled = text.isNotBlank()) { Text("Send") }
                }
            }
        }
    }
}
