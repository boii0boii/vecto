package ai.vecto.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One step of a playbook. [attempt] is retried until it returns true or [timeoutMs] runs out. */
class Step(
    val label: String,
    val timeoutMs: Long = 15_000,
    /** Pause after success so the next screen can load. */
    val settleMs: Long = 800,
    val attempt: (VectoAccessibilityService) -> Boolean,
)

class Playbook(val steps: List<Step>, val doneMessage: String)

sealed interface AgentState {
    data object Idle : AgentState
    data class Running(val label: String, val index: Int, val total: Int) : AgentState
    data class Done(val message: String) : AgentState
    data class Failed(val message: String) : AgentState
    data object Stopped : AgentState
}

/**
 * Runs scripted playbooks against other apps' UI via [VectoAccessibilityService].
 * Scripted steps are fast and free; an LLM fallback for "stuck" steps plugs in where a step times out.
 */
object Agent {
    private const val TAG = "VectoAgent"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state

    fun run(playbook: Playbook) {
        job?.cancel()
        job = scope.launch {
            val total = playbook.steps.size
            for ((i, step) in playbook.steps.withIndex()) {
                _state.value = AgentState.Running(step.label, i + 1, total)
                val ok = withTimeoutOrNull(step.timeoutMs) { retryUntilTrue(step) } ?: false
                if (!ok) {
                    Log.w(TAG, "Step failed: ${step.label}")
                    VectoAccessibilityService.instance?.dumpScreen()
                    _state.value = AgentState.Failed("Got stuck at \"${step.label}\". You can finish from here.")
                    return@launch
                }
                delay(step.settleMs)
            }
            _state.value = AgentState.Done(playbook.doneMessage)
        }
    }

    fun stop() {
        job?.cancel()
        _state.value = AgentState.Stopped
    }

    fun reset() {
        _state.value = AgentState.Idle
    }

    private suspend fun retryUntilTrue(step: Step): Boolean {
        while (true) {
            val service = VectoAccessibilityService.instance
            if (service != null && step.attempt(service)) return true
            delay(500)
        }
    }
}
