package ai.vecto.wear

import ai.vecto.agent.VectoAccessibilityService
import ai.vecto.command.CommandRunner
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Receives voice commands from the Vecto watch app over the Wear OS Data Layer, runs them through the
 * same [CommandRunner] as the widget, and replies with one status line for the watch to show.
 *
 * Opening Uber from here is a background activity start, which Android allows because Vecto's
 * accessibility service is bound by the system. So watch commands require that service to be on.
 */
class WearCommandService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.COMMAND) return
        val text = event.data.decodeToString().trim()
        val watch = event.sourceNodeId
        if (text.isEmpty()) return

        // Process-wide scope: the work outlives this short-lived listener binding.
        scope.launch {
            val reply = if (!VectoAccessibilityService.isEnabled(applicationContext)) {
                "Turn on Vecto in your phone's Accessibility settings first."
            } else {
                CommandRunner(applicationContext).run(text).message
            }
            runCatching {
                Wearable.getMessageClient(applicationContext)
                    .sendMessage(watch, WearPaths.STATUS, reply.encodeToByteArray())
                    .await()
            }.onFailure { Log.w(TAG, "Couldn't reply to watch", it) }
        }
    }

    private companion object {
        const val TAG = "VectoWear"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** Message paths shared with the watch app (wear/.../WearPaths.kt). Keep them in sync. */
object WearPaths {
    const val COMMAND = "/vecto/command"
    const val STATUS = "/vecto/status"
}
