package ai.vecto.agent

import ai.vecto.memory.MemoryStore
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Vecto's "hands": reads the screen of Uber / Uber Eats and taps or types there.
 * It only acts while [Agent] is running a playbook the user started.
 */
class VectoAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: StatusOverlay? = null

    override fun onServiceConnected() {
        instance = this
        overlay = StatusOverlay(this)
        val memory = MemoryStore.get(this)
        scope.launch {
            Agent.state.collect { state ->
                overlay?.render(state)
                when (state) {
                    is AgentState.Done -> memory.recordOutcome("done")
                    is AgentState.Failed -> memory.recordOutcome("failed")
                    AgentState.Stopped -> memory.recordOutcome("stopped")
                    else -> {}
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Playbooks poll the screen tree; events aren't needed yet.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        overlay?.hide()
        instance = null
        super.onDestroy()
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** Taps the first visible, non-editable element whose text or description contains [text]. */
    fun clickText(text: String): Boolean = findVisible(text)?.let(::click) == true

    fun clickTextStartingWith(prefix: String): Boolean {
        val node = allNodes().firstOrNull { n ->
            n.isVisibleToUser && !n.isEditable &&
                (n.text?.toString() ?: n.contentDescription?.toString())
                    ?.trim()?.startsWith(prefix, ignoreCase = true) == true
        }
        return node?.let(::click) == true
    }

    fun typeIntoFirstEditable(text: String): Boolean {
        val field = allNodes().firstOrNull { it.isEditable && it.isVisibleToUser } ?: return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Logs the current screen tree so playbook labels can be tuned. Filter Logcat by "VectoService". */
    fun dumpScreen() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.d(TAG, "No active window")
            return
        }
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            val label = node.text ?: node.contentDescription ?: ""
            Log.d(
                TAG,
                "${"  ".repeat(depth)}${node.className?.toString()?.substringAfterLast('.')} " +
                    "\"$label\" id=${node.viewIdResourceName} click=${node.isClickable} edit=${node.isEditable}",
            )
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
    }

    private fun findVisible(text: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
            ?.firstOrNull { it.isVisibleToUser && !it.isEditable }

    /** Clicks the node, or its nearest clickable ancestor (labels are often inside a clickable row). */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        return current?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return out
    }

    companion object {
        private const val TAG = "VectoService"

        @Volatile
        var instance: VectoAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, VectoAccessibilityService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
