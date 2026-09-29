package ai.vecto.command

import ai.vecto.BuildConfig
import ai.vecto.data.Prefs
import ai.vecto.memory.MemoryStore
import ai.vecto.memory.MemorySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** What the backend understood from the user's command. Mirrors `ParsedCommand` in backend/src/index.ts. */
@Serializable
data class ParsedCommand(
    val action: String, // "book_ride" | "order_food" | "remember" | "unsupported"
    val destination: String? = null,
    val restaurant: String? = null,
    val items: List<String> = emptyList(),
    /** For "remember": the fact to store on the phone. */
    val fact: String? = null,
    val message: String,
)

@Serializable
private data class Places(val home: String, val work: String)

@Serializable
private data class IntentRequest(
    val deviceId: String,
    val text: String,
    val places: Places,
    val memory: MemorySummary,
)

class IntentClient(private val prefs: Prefs, private val memory: MemoryStore) {
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun parse(text: String): Result<ParsedCommand> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(
                IntentRequest.serializer(),
                IntentRequest(prefs.deviceId, text, Places(prefs.home, prefs.work), memory.summary()),
            )
            val request = Request.Builder()
                .url("${BuildConfig.BACKEND_URL}/v1/intent")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val raw = response.body.string()
                when {
                    response.code == 429 -> error("You've hit today's free limit. Try again tomorrow.")
                    !response.isSuccessful -> error("Server error (${response.code})")
                    else -> json.decodeFromString(ParsedCommand.serializer(), raw)
                }
            }
        }
    }
}
