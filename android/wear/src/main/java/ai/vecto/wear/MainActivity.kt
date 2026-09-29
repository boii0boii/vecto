package ai.vecto.wear

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Vecto on the wrist: opens straight into listening, turns speech into text on the watch,
 * and sends it to the phone, which does the work and replies with one status line.
 */
class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private var status by mutableStateOf("Tap to speak")
    private var busy by mutableStateOf(false)
    private var replyTimeout: Job? = null

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (spoken.isNullOrBlank()) status = "Didn't catch that. Tap to try again." else send(spoken)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchScreen(status, busy, onSpeak = ::listen) }
        if (savedInstanceState == null) listen()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.STATUS) return
        replyTimeout?.cancel()
        status = event.data.decodeToString()
        busy = false
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "What should I do?")
        try {
            speech.launch(intent)
        } catch (_: ActivityNotFoundException) {
            status = "Voice input isn't available on this watch."
        }
    }

    private fun send(text: String) {
        busy = true
        status = "“$text”\nSending to phone…"
        lifecycleScope.launch {
            val sent = runCatching {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                    ?: return@runCatching false
                Wearable.getMessageClient(this@MainActivity)
                    .sendMessage(phone.id, WearPaths.COMMAND, text.encodeToByteArray())
                    .await()
                true
            }.getOrDefault(false)

            if (!sent) {
                status = "Can't reach your phone. Is it nearby?"
                busy = false
                return@launch
            }
            status = "“$text”\nWorking on it…"
            replyTimeout = launch {
                delay(20_000)
                status = "Sent. Check your phone."
                busy = false
            }
        }
    }
}

@Composable
private fun WatchScreen(status: String, busy: Boolean, onSpeak: () -> Unit) {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(status, textAlign = TextAlign.Center, maxLines = 4)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onSpeak, enabled = !busy) {
                Text(if (busy) "Working…" else "Speak")
            }
        }
    }
}
