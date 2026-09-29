package ai.vecto.memory

import ai.vecto.command.ParsedCommand
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryEntry(
    val at: Long,
    val text: String,
    val action: String,
    val destination: String? = null,
    val restaurant: String? = null,
    val items: List<String> = emptyList(),
    /** "opened" | "started" | "done" | "failed" | "stopped" */
    val outcome: String,
)

@Serializable
data class Fact(val text: String, val at: Long)

@Serializable
data class Memory(
    val history: List<HistoryEntry> = emptyList(),
    val facts: List<Fact> = emptyList(),
)

/** Compact view of memory sent with each command. Mirrors `MemorySummary` in backend/src/index.ts. */
@Serializable
data class MemorySummary(
    val facts: List<String>,
    val frequentDestinations: List<String>,
    val restaurants: List<RestaurantMemory>,
)

@Serializable
data class RestaurantMemory(val name: String, val timesOrdered: Int, val lastItems: List<String>)

/**
 * Vecto's memory. Lives only on the phone, in app-private storage. The backend receives just the
 * [summary] with each command and doesn't store it.
 */
class MemoryStore private constructor(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _memory = MutableStateFlow(load())
    val memory: StateFlow<Memory> = _memory

    fun recordCommand(text: String, cmd: ParsedCommand, outcome: String) = update { m ->
        val entry = HistoryEntry(
            at = System.currentTimeMillis(),
            text = text,
            action = cmd.action,
            destination = cmd.destination,
            restaurant = cmd.restaurant,
            items = cmd.items,
            outcome = outcome,
        )
        m.copy(history = (m.history + entry).takeLast(MAX_HISTORY))
    }

    /** Updates the outcome of the latest command (e.g. when the agent finishes or is stopped). */
    fun recordOutcome(outcome: String) = update { m ->
        val last = m.history.lastOrNull() ?: return@update m
        m.copy(history = m.history.dropLast(1) + last.copy(outcome = outcome))
    }

    fun remember(fact: String) = update { m ->
        val clean = fact.trim().take(MAX_FACT_LENGTH)
        if (clean.isEmpty() || m.facts.any { it.text.equals(clean, ignoreCase = true) }) return@update m
        m.copy(facts = (m.facts + Fact(clean, System.currentTimeMillis())).takeLast(MAX_FACTS))
    }

    fun forget(fact: Fact) = update { m -> m.copy(facts = m.facts - fact) }

    fun forgetEverything() = update { Memory() }

    fun summary(): MemorySummary {
        val m = _memory.value
        val succeeded = m.history.filter { it.outcome == "opened" || it.outcome == "done" }

        val destinations = succeeded
            .mapNotNull { it.destination }
            .groupingBy { it.lowercase() }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(TOP_N)
            .map { entry -> succeeded.last { it.destination?.lowercase() == entry.key }.destination!! }

        val restaurants = succeeded
            .filter { it.restaurant != null }
            .groupBy { it.restaurant!!.lowercase() }
            .map { (_, orders) ->
                RestaurantMemory(
                    name = orders.last().restaurant!!,
                    timesOrdered = orders.size,
                    lastItems = orders.lastOrNull { it.items.isNotEmpty() }?.items ?: emptyList(),
                )
            }
            .sortedByDescending { it.timesOrdered }
            .take(TOP_N)

        return MemorySummary(m.facts.map { it.text }, destinations, restaurants)
    }

    @Synchronized
    private fun update(block: (Memory) -> Memory) {
        val next = block(_memory.value)
        if (next == _memory.value) return
        _memory.value = next
        // Write to a temp file then rename, so a crash mid-write can't corrupt memory.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(Memory.serializer(), next))
        tmp.renameTo(file)
    }

    private fun load(): Memory = runCatching {
        json.decodeFromString(Memory.serializer(), file.readText())
    }.getOrDefault(Memory())

    companion object {
        private const val MAX_HISTORY = 200
        private const val MAX_FACTS = 50
        private const val MAX_FACT_LENGTH = 200
        private const val TOP_N = 5

        @Volatile
        private var instance: MemoryStore? = null

        fun get(context: Context): MemoryStore = instance ?: synchronized(this) {
            instance ?: MemoryStore(File(context.applicationContext.filesDir, "memory.json")).also { instance = it }
        }
    }
}
