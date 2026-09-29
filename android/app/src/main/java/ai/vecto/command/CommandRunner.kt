package ai.vecto.command

import ai.vecto.actions.ActionRouter
import ai.vecto.actions.RouteResult
import ai.vecto.data.Prefs
import ai.vecto.memory.MemoryStore
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What happened, in one line the user can read on the phone or the watch. */
data class RunResult(
    val message: String,
    /** True when Vecto opened another app (Uber / Uber Eats) and the flow continues there. */
    val handedOff: Boolean,
    val needsAccessibility: Boolean = false,
)

/**
 * The single path every command takes, whether it comes from the widget, the command bar or the watch:
 * text -> backend (with on-phone memory) -> action -> memory update.
 */
class CommandRunner(context: Context) {
    private val app = context.applicationContext
    private val memory = MemoryStore.get(app)
    private val client = IntentClient(Prefs(app), memory)

    suspend fun run(text: String): RunResult {
        val cmd = client.parse(text).getOrElse {
            return RunResult(it.message ?: "Something went wrong. Try again.", handedOff = false)
        }
        val result = withContext(Dispatchers.Main) { ActionRouter.execute(app, cmd) }

        // Rides hand off to Uber ("opened"); food keeps running in the agent, which
        // reports "done" / "failed" / "stopped" when it finishes.
        val outcome = when (result) {
            is RouteResult.Started -> if (cmd.action == "order_food") "started" else "opened"
            is RouteResult.Info -> "done"
            is RouteResult.NeedsAccessibility, is RouteResult.Failed -> "failed"
        }
        memory.recordCommand(text, cmd, outcome)

        return when (result) {
            is RouteResult.Started -> RunResult(result.message, handedOff = true)
            is RouteResult.Info -> RunResult(result.message, handedOff = false)
            is RouteResult.NeedsAccessibility -> RunResult(result.message, handedOff = false, needsAccessibility = true)
            is RouteResult.Failed -> RunResult(result.message, handedOff = false)
        }
    }
}
