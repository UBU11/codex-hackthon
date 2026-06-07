package com.example.omu.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.omu.pipeline.TurnState
import com.example.omu.ui.theme.OmuTheme
import java.util.Locale

@Composable
fun TranslateScreen(
    uiState: TranslateUiState,
    hasMicPermission: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onClearTranscript: () -> Unit,
    onVoiceSelected: (String) -> Unit,
    onStartEnrollment: () -> Unit,
    onCancelEnrollment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AgentHeader(uiState = uiState)
                PipelineRail(uiState = uiState)
                VoiceProfileSettings(
                    voices = uiState.availableVoices,
                    selectedVoiceId = uiState.selectedVoiceId,
                    selectedVoiceLabel = uiState.selectedVoiceLabel,
                    isVoiceLoading = uiState.isVoiceLoading,
                    onVoiceSelected = onVoiceSelected,
                    modifier = Modifier.fillMaxWidth()
                )
                EnrollmentScreen(
                    script = uiState.enrollmentScript,
                    durationCapturedMs = uiState.enrollmentDurationMs,
                    targetDurationMs = uiState.enrollmentTargetMs,
                    isRecording = uiState.isEnrollmentRecording,
                    isProcessing = uiState.isEnrollmentProcessing,
                    hasMicPermission = hasMicPermission,
                    onStartEnrollment = onStartEnrollment,
                    onCancelEnrollment = onCancelEnrollment,
                    modifier = Modifier.fillMaxWidth()
                )
                SignalGrid(uiState = uiState)
                TranslationPanel(
                    uiState = uiState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(290.dp)
                )
                uiState.error?.let { error ->
                    ErrorBanner(error = error)
                }
            }

            CommandBar(
                hasMicPermission = hasMicPermission,
                uiState = uiState,
                onStartListening = onStartListening,
                onStopListening = onStopListening,
                onClearTranscript = onClearTranscript
            )
        }
    }
}

@Composable
private fun AgentHeader(uiState: TranslateUiState) {
    val phase = phaseLabel(uiState)
    val accent = phaseColor(uiState)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Omu",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusDot(color = accent)
                    Text(
                        text = uiState.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            StatusBadge(
                label = phase,
                color = accent
            )
        }
    }
}

@Composable
private fun PipelineRail(uiState: TranslateUiState) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PipelineStep(
                label = "Mic",
                value = if (uiState.isListening) "Open" else "Idle",
                active = uiState.isListening &&
                    uiState.pipelineState == TurnState.IDLE &&
                    !uiState.isTranslating &&
                    !uiState.isSpeaking,
                modifier = Modifier.weight(1f)
            )
            PipelineStep(
                label = "VAD",
                value = when (uiState.pipelineState) {
                    TurnState.SPEAKING -> "Voice"
                    TurnState.TURN_COMPLETE -> "Turn"
                    else -> "Watch"
                },
                active = uiState.pipelineState == TurnState.SPEAKING,
                modifier = Modifier.weight(1f)
            )
            PipelineStep(
                label = "Gemma",
                value = if (uiState.isTranslating) "Run" else "Ready",
                active = uiState.isTranslating,
                modifier = Modifier.weight(1f)
            )
            PipelineStep(
                label = "TTS",
                value = if (uiState.isSpeaking) "Voice" else "Ready",
                active = uiState.isSpeaking || uiState.pipelineState == TurnState.SYSTEM_SPEAKING,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PipelineStep(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val background = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusDot(color = color, size = 8)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SignalGrid(uiState: TranslateUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                label = "VAD",
                value = String.format(Locale.US, "%.2f", uiState.vadConfidence),
                progress = uiState.vadConfidence.coerceIn(0f, 1f),
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "Pause",
                value = "${uiState.pauseMs} ms",
                progress = (uiState.pauseMs / 400f).coerceIn(0f, 1f),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                label = "Turns",
                value = uiState.turnsDetected.toString(),
                progress = if (uiState.turnsDetected > 0) 1f else 0f,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "Turn",
                value = "${uiState.currentTurnDurationMs} ms",
                progress = (uiState.currentTurnDurationMs / 5000f).coerceIn(0f, 1f),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TranslationPanel(
    uiState: TranslateUiState,
    modifier: Modifier = Modifier
) {
    val textScrollState = rememberScrollState()

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Translation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusBadge(
                    label = outputLabel(uiState),
                    color = phaseColor(uiState)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .verticalScroll(textScrollState)
                    .padding(14.dp)
            ) {
                Text(
                    text = uiState.translatedText.ifBlank { "Waiting for speech..." },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (uiState.translatedText.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
    ) {
        Text(
            text = error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CommandBar(
    hasMicPermission: Boolean,
    uiState: TranslateUiState,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onClearTranscript: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onStartListening,
                enabled = !uiState.isListening &&
                    !uiState.isEnrollmentRecording &&
                    !uiState.isEnrollmentProcessing,
                modifier = Modifier
                    .weight(1.2f)
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = if (hasMicPermission) "Start" else "Allow Mic",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = onStopListening,
                enabled = uiState.isListening &&
                    !uiState.isEnrollmentRecording &&
                    !uiState.isEnrollmentProcessing,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = "Stop", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onClearTranscript,
                enabled = uiState.translatedText.isNotBlank() || uiState.turnsDetected > 0,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = "Clear", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(78.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            ProgressTrack(
                progress = progress,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val normalizedProgress = progress.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (normalizedProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(normalizedProgress)
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun StatusBadge(
    label: String,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusDot(
    color: Color,
    size: Int = 10
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun phaseColor(uiState: TranslateUiState): Color {
    return when {
        uiState.isSpeaking || uiState.pipelineState == TurnState.SYSTEM_SPEAKING ->
            MaterialTheme.colorScheme.tertiary
        uiState.isTranslating -> MaterialTheme.colorScheme.secondary
        uiState.pipelineState == TurnState.SPEAKING -> MaterialTheme.colorScheme.primary
        uiState.isListening -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
}

private fun phaseLabel(uiState: TranslateUiState): String {
    return when {
        uiState.isSpeaking || uiState.pipelineState == TurnState.SYSTEM_SPEAKING -> "Speaking"
        uiState.isTranslating -> "Translating"
        uiState.pipelineState == TurnState.SPEAKING -> "Hearing"
        uiState.isListening -> "Listening"
        else -> "Standby"
    }
}

private fun outputLabel(uiState: TranslateUiState): String {
    return when {
        uiState.error != null -> "Error"
        uiState.isSpeaking -> "Voicing"
        uiState.isTranslating -> "Streaming"
        uiState.translatedText.isNotBlank() -> "Ready"
        else -> "Empty"
    }
}

@Preview(showBackground = true)
@Composable
private fun TranslateScreenPreview() {
    OmuTheme {
        TranslateScreen(
            uiState = TranslateUiState(
                status = "Speaking...",
                vadConfidence = 0.82f,
                pauseMs = 64,
                turnsDetected = 2,
                currentTurnDurationMs = 1536,
                pipelineState = TurnState.SPEAKING,
                isListening = true,
                translatedText = "I will be there in ten minutes."
            ),
            hasMicPermission = true,
            onStartListening = {},
            onStopListening = {},
            onClearTranscript = {},
            onVoiceSelected = {},
            onStartEnrollment = {},
            onCancelEnrollment = {}
        )
    }
}
