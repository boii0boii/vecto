package ai.vecto.command

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private sealed interface UiState {
    data object Idle : UiState
    data object Thinking : UiState
    data class Message(val text: String, val needsAccessibility: Boolean = false) : UiState
}

/** The floating command bar the widget opens: type or speak, and Vecto does it. */
class CommandActivity : ComponentActivity() {

    private var preset by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preset = intent.getStringExtra(EXTRA_PRESET)
        val runner = CommandRunner(this)
        setContent {
            MaterialTheme {
                CommandScreen(
                    runner = runner,
                    preset = preset,
                    onPresetConsumed = { preset = null },
                    onDismiss = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        preset = intent.getStringExtra(EXTRA_PRESET)
    }

    companion object {
        const val EXTRA_PRESET = "preset"
    }
}

@Composable
private fun CommandScreen(
    runner: CommandRunner,
    preset: String?,
    onPresetConsumed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var ui by remember { mutableStateOf<UiState>(UiState.Idle) }
    val focus = remember { FocusRequester() }

    fun submit(command: String) {
        if (command.isBlank() || ui == UiState.Thinking) return
        ui = UiState.Thinking
        scope.launch {
            val result = runner.run(command)
            if (result.handedOff) {
                onDismiss()
                ui = UiState.Idle
            } else {
                ui = UiState.Message(result.message, result.needsAccessibility)
            }
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            text = spoken
            submit(spoken)
        }
    }

    LaunchedEffect(preset) {
        if (preset != null) {
            text = preset
            submit(preset)
            onPresetConsumed()
        } else {
            focus.requestFocus()
        }
    }

    // Tap outside the card to close.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 96.dp)
                .fillMaxWidth()
                .clickable(enabled = false) {},
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("\"Cab to King's Cross\" or \"Order from Dishoom\"") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (val state = ui) {
                        UiState.Thinking -> {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                            Text("Working on it…", Modifier.weight(1f))
                        }
                        is UiState.Message -> Text(state.text, Modifier.weight(1f))
                        UiState.Idle -> Text("", Modifier.weight(1f))
                    }
                    TextButton(onClick = {
                        speech.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                        )
                    }) { Text("Speak") }
                    Button(onClick = { submit(text) }, enabled = ui != UiState.Thinking) { Text("Go") }
                }
                if ((ui as? UiState.Message)?.needsAccessibility == true) {
                    Button(
                        onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open Accessibility settings") }
                }
            }
        }
    }
}
