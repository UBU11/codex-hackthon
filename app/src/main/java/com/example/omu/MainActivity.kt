package com.example.omu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider
import com.example.omu.core.Permissions
import com.example.omu.ui.MainViewModel
import com.example.omu.ui.TranslateScreen
import com.example.omu.ui.theme.OmuTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            OmuTheme {
                var hasMicPermission by remember {
                    mutableStateOf(Permissions.hasRecordAudioPermission(this))
                }
                var pendingMicAction by remember {
                    mutableStateOf<MicPermissionAction?>(null)
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasMicPermission = granted
                    if (granted) {
                        when (pendingMicAction) {
                            MicPermissionAction.LISTEN -> viewModel.startListening()
                            MicPermissionAction.ENROLL -> viewModel.startVoiceEnrollment()
                            null -> Unit
                        }
                    }
                    pendingMicAction = null
                }
                val uiState by viewModel.uiState.collectAsState()

                TranslateScreen(
                    uiState = uiState,
                    hasMicPermission = hasMicPermission,
                    onStartListening = {
                        if (hasMicPermission) {
                            viewModel.startListening()
                        } else {
                            pendingMicAction = MicPermissionAction.LISTEN
                            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStopListening = viewModel::stopListening,
                    onClearTranscript = viewModel::clearTranscript,
                    onVoiceSelected = viewModel::selectVoiceProfile,
                    onStartEnrollment = {
                        if (hasMicPermission) {
                            viewModel.startVoiceEnrollment()
                        } else {
                            pendingMicAction = MicPermissionAction.ENROLL
                            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onCancelEnrollment = viewModel::cancelVoiceEnrollment
                )
            }
        }
    }

    private enum class MicPermissionAction {
        LISTEN,
        ENROLL
    }
}
