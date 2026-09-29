package ai.vecto.data

import android.content.Context
import java.util.UUID

/** Tiny local store: anonymous device ID (no login) and saved places. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("vecto", Context.MODE_PRIVATE)

    val deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { sp.edit().putString(KEY_DEVICE_ID, it).apply() }

    var home: String
        get() = sp.getString(KEY_HOME, "") ?: ""
        set(value) = sp.edit().putString(KEY_HOME, value.trim()).apply()

    var work: String
        get() = sp.getString(KEY_WORK, "") ?: ""
        set(value) = sp.edit().putString(KEY_WORK, value.trim()).apply()

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_HOME = "home"
        const val KEY_WORK = "work"
    }
}
