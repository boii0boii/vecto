package ai.vecto.agent

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small floating pill shown over other apps while Vecto works: current step + a Stop button.
 * Uses an accessibility overlay, so no extra "draw over apps" permission is needed.
 */
class StatusOverlay(private val service: VectoAccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var shown = false

    private val label = TextView(service).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        maxWidth = (service.resources.displayMetrics.widthPixels * 0.6).toInt()
    }

    private val button = Button(service).apply {
        text = "Stop"
        isAllCaps = false
        setOnClickListener { if (text == "Stop") Agent.stop() else hide() }
    }

    private val root = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(12)
        setPadding(pad * 2, pad / 2, pad / 2, pad / 2)
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(Color.parseColor("#E6111111"))
        }
        addView(label)
        addView(button)
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(48)
    }

    fun render(state: AgentState) {
        handler.removeCallbacksAndMessages(null)
        when (state) {
            AgentState.Idle -> hide()
            is AgentState.Running -> show("${state.index}/${state.total} · ${state.label}", "Stop")
            is AgentState.Done -> finish(state.message)
            is AgentState.Failed -> finish(state.message)
            AgentState.Stopped -> finish("Stopped. You're in control.")
        }
    }

    fun hide() {
        if (shown) runCatching { windowManager.removeView(root) }
        shown = false
    }

    private fun finish(message: String) {
        show(message, "OK")
        handler.postDelayed({ Agent.reset() }, 5_000)
    }

    private fun show(text: String, action: String) {
        label.text = text
        button.text = action
        if (!shown) {
            windowManager.addView(root, params)
            shown = true
        }
    }

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
}
