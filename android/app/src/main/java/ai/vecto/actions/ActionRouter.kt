package ai.vecto.actions

import ai.vecto.agent.Agent
import ai.vecto.agent.Playbooks
import ai.vecto.agent.VectoAccessibilityService
import ai.vecto.command.ParsedCommand
import ai.vecto.memory.MemoryStore
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

sealed interface RouteResult {
    /** Handed off to another app; the agent (if any) takes it from here. */
    data class Started(val message: String) : RouteResult
    /** Done inside Vecto (e.g. remembered a fact); show the message and stay open. */
    data class Info(val message: String) : RouteResult
    data class NeedsAccessibility(val message: String) : RouteResult
    data class Failed(val message: String) : RouteResult
}

/**
 * Turns a parsed command into an action: deep link first (fast, reliable),
 * then an accessibility playbook for the steps a deep link can't do.
 */
object ActionRouter {
    const val UBER = "com.ubercab"
    const val UBER_EATS = "com.ubercab.eats"

    fun execute(context: Context, cmd: ParsedCommand): RouteResult = when (cmd.action) {
        "book_ride" -> bookRide(context, cmd)
        "order_food" -> orderFood(context, cmd)
        "remember" -> remember(context, cmd)
        else -> RouteResult.Failed(cmd.message)
    }

    private fun remember(context: Context, cmd: ParsedCommand): RouteResult {
        val fact = cmd.fact ?: return RouteResult.Failed("What should I remember?")
        MemoryStore.get(context).remember(fact)
        return RouteResult.Info(cmd.message)
    }

    private fun bookRide(context: Context, cmd: ParsedCommand): RouteResult {
        val destination = cmd.destination
            ?: return RouteResult.Failed("Where to? Try \"cab to King's Cross\".")
        // Uber universal link: opens the app with the drop-off filled in, pickup = current location.
        // The user picks the ride type and taps Confirm. We never book on their behalf.
        val uri = Uri.parse("https://m.uber.com/ul/").buildUpon()
            .appendQueryParameter("action", "setPickup")
            .appendQueryParameter("pickup", "my_location")
            .appendQueryParameter("dropoff[formatted_address]", destination)
            .build()
        return if (open(context, uri, UBER)) {
            RouteResult.Started(cmd.message)
        } else {
            RouteResult.Failed("Install Uber and log in first.")
        }
    }

    private fun orderFood(context: Context, cmd: ParsedCommand): RouteResult {
        val restaurant = cmd.restaurant
            ?: return RouteResult.Failed("Which restaurant? Try \"order from Dishoom\".")
        if (!VectoAccessibilityService.isEnabled(context)) {
            return RouteResult.NeedsAccessibility("Turn on Vecto in Accessibility settings so I can order for you.")
        }
        val launched = context.packageManager.getLaunchIntentForPackage(UBER_EATS)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { context.startActivity(it) }.isSuccess } == true
        if (!launched) return RouteResult.Failed("Install Uber Eats and log in first.")

        Agent.run(Playbooks.orderFood(restaurant, cmd.items))
        return RouteResult.Started(cmd.message)
    }

    /** Opens [uri] in [pkg] if installed. Returns false if the app isn't there. */
    private fun open(context: Context, uri: Uri, pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
