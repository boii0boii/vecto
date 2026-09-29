package ai.vecto

import ai.vecto.actions.ActionRouter
import ai.vecto.agent.VectoAccessibilityService
import ai.vecto.command.CommandActivity
import ai.vecto.data.Prefs
import ai.vecto.memory.MemoryStore
import ai.vecto.widget.VectoWidgetReceiver
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Onboarding + settings: add the widget, enable the agent, check apps, save places. */
class MainActivity : ComponentActivity() {

    /** Bumped on every resume so status rows re-check (e.g. after returning from Settings). */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent { MaterialTheme { SetupScreen(prefs, resumeTick) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

@Composable
private fun SetupScreen(prefs: Prefs, resumeTick: Int) {
    val context = LocalContext.current
    val agentOn = remember(resumeTick) { VectoAccessibilityService.isEnabled(context) }
    val hasUber = remember(resumeTick) { isInstalled(context, ActionRouter.UBER) }
    val hasEats = remember(resumeTick) { isInstalled(context, ActionRouter.UBER_EATS) }
    var home by remember { mutableStateOf(prefs.home) }
    var work by remember { mutableStateOf(prefs.work) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Vecto", style = MaterialTheme.typography.headlineLarge)
        Text("Tell your phone what you want. It does the tapping.", style = MaterialTheme.typography.bodyLarge)

        SetupCard("1. Add the widget", "Put Vecto on your home screen.") {
            Button(onClick = { pinWidget(context) }) { Text("Add to home screen") }
        }

        SetupCard(
            "2. Let Vecto tap for you" + if (agentOn) "  ✓" else "",
            "Vecto uses Android's Accessibility service to read and tap inside Uber and Uber Eats, " +
                "only when you give it a command. It can't see other apps, and it never pays for you: " +
                "it always stops so you can confirm.",
        ) {
            if (!agentOn) {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                    Text("Turn on in Settings")
                }
            }
        }

        SetupCard(
            "3. Log in to your apps",
            "Uber: ${if (hasUber) "installed ✓" else "not installed"}\n" +
                "Uber Eats: ${if (hasEats) "installed ✓" else "not installed"}\n" +
                "Open each once and make sure you're logged in.",
        ) {}

        SetupCard("Saved places", "So you can just say \"cab home\".") {
            OutlinedTextField(
                value = home,
                onValueChange = { home = it; prefs.home = it },
                label = { Text("Home address") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = work,
                onValueChange = { work = it; prefs.work = it },
                label = { Text("Work address") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        MemoryCard(MemoryStore.get(context))

        OutlinedButton(
            onClick = { context.startActivity(Intent(context, CommandActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Try a command") }
    }
}

/** Shows what Vecto has learned. Everything here lives only on this phone. */
@Composable
private fun MemoryCard(store: MemoryStore) {
    val memory by store.memory.collectAsState()
    val summary = remember(memory) { store.summary() }
    var confirmWipe by remember { mutableStateOf(false) }

    SetupCard(
        "What Vecto remembers",
        "Stored only on this phone. Say \"remember I'm vegetarian\" to teach it something.",
    ) {
        memory.facts.forEach { fact ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("• ${fact.text}", Modifier.weight(1f))
                TextButton(onClick = { store.forget(fact) }) { Text("Forget") }
            }
        }
        if (summary.frequentDestinations.isNotEmpty()) {
            Text("Places you go: ${summary.frequentDestinations.joinToString()}")
        }
        summary.restaurants.forEach { r ->
            val usual = if (r.lastItems.isEmpty()) "" else ": ${r.lastItems.joinToString()}"
            Text("${r.name} (${r.timesOrdered}×)$usual")
        }
        Text("${memory.history.size} commands in history", style = MaterialTheme.typography.bodySmall)
        if (memory.history.isNotEmpty() || memory.facts.isNotEmpty()) {
            if (confirmWipe) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { store.forgetEverything(); confirmWipe = false }) { Text("Yes, forget all") }
                    TextButton(onClick = { confirmWipe = false }) { Text("Cancel") }
                }
            } else {
                TextButton(onClick = { confirmWipe = true }) { Text("Forget everything") }
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, body: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

private fun isInstalled(context: Context, pkg: String): Boolean =
    context.packageManager.getLaunchIntentForPackage(pkg) != null

private fun pinWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(ComponentName(context, VectoWidgetReceiver::class.java), null, null)
    }
}
