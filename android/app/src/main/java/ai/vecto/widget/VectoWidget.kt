package ai.vecto.widget

import ai.vecto.command.CommandActivity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/** Home-screen widget: a command bar plus one-tap shortcuts. Everything opens [CommandActivity]. */
class VectoWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { Content() } }
    }

    @Composable
    private fun Content() {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(28.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tell Vecto…",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp),
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<CommandActivity>()),
            )
            Chip("Cab home", "cab home")
            Spacer(GlanceModifier.width(8.dp))
            Chip("Food", null)
        }
    }

    @Composable
    private fun Chip(label: String, preset: String?) {
        val action = if (preset == null) {
            actionStartActivity<CommandActivity>()
        } else {
            actionStartActivity<CommandActivity>(actionParametersOf(PresetKey to preset))
        }
        Box(
            modifier = GlanceModifier
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(16.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable(action),
        ) {
            Text(
                text = label,
                style = TextStyle(
                    color = GlanceTheme.colors.onSecondaryContainer,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }

    companion object {
        /** Glance passes action parameters to the activity as intent extras keyed by name. */
        val PresetKey = ActionParameters.Key<String>(CommandActivity.EXTRA_PRESET)
    }
}

class VectoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VectoWidget()
}
