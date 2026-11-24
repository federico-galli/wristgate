package io.github.federicogalli.wristgate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Settings, rules and forwarding history, stored locally on the device. */
class Store(private val context: Context) {

    companion object {
        const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 30
        private const val MAX_RECENT_SENDERS = 15

        // History outcomes are stored as codes and localized only when displayed.
        const val OUTCOME_ANSWERED = "answered"
        const val OUTCOME_MISSED = "missed"
        const val REASON_FROM = "from:"
        const val REASON_KEYWORD = "kw:"
    }

    /** A history entry: a forwarded call or message. Message text is never stored. */
    data class Call(
        val id: String,
        val time: Long,
        val sourceId: String,
        val caller: String,
        val outcome: String? = null,
        val kind: String = KIND_CALL,
    )

    val prefs = context.getSharedPreferences("wristgate", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ per-app switches

    // Calls and messages are chosen separately for each app.
    // Defaults: on for the core apps, off for the others.

    fun callsEnabled(s: Source) = s.calls && prefs.getBoolean("calls_${s.id}", s.core)
    fun setCalls(s: Source, on: Boolean) = prefs.edit().putBoolean("calls_${s.id}", on).apply()

    fun messagesEnabled(s: Source) = prefs.getBoolean("messages_${s.id}", s.core)
    fun setMessages(s: Source, on: Boolean) = prefs.edit().putBoolean("messages_${s.id}", on).apply()

    // ------------------------------------------------------------ important-message rules

    /** Until the user edits them, the keywords follow the phone's language. */
    var keywords: Set<String>
        get() = prefs.getStringSet("keywords", null)?.toSet()
            ?: context.resources.getStringArray(R.array.default_keywords).toSet()
        set(v) = prefs.edit().putStringSet("keywords", v).apply()

    var contacts: Set<String>
        get() = prefs.getStringSet("contacts", null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("contacts", v).apply()

    /** Recently seen senders/groups, so they can be added with one tap (no contacts permission needed). */
    fun recentSenders(): List<String> {
        val arr = JSONArray(prefs.getString("recent_senders", "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun noteSender(name: String) {
        if (name.isBlank()) return
        val list = listOf(name) + recentSenders().filter { it != name }
        prefs.edit().putString("recent_senders", JSONArray(list.take(MAX_RECENT_SENDERS)).toString()).apply()
    }

    // ------------------------------------------------------------ history

    fun history(): List<Call> {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Call(
                id = o.getString("id"),
                time = o.getLong("time"),
                sourceId = o.getString("source"),
                caller = o.getString("caller"),
                outcome = o.optString("outcome").ifEmpty { null },
                kind = o.optString("kind").ifEmpty { KIND_CALL },
            )
        }
    }

    fun addCall(c: Call) = save(listOf(c) + history())

    fun setOutcome(id: String, outcome: String) =
        save(history().map { if (it.id == id) it.copy(outcome = outcome) else it })

    fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    private fun save(calls: List<Call>) {
        val arr = JSONArray()
        calls.take(MAX_HISTORY).forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("time", it.time)
                    .put("source", it.sourceId)
                    .put("caller", it.caller)
                    .put("outcome", it.outcome ?: "")
                    .put("kind", it.kind)
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }
}

const val KIND_CALL = "call"
const val KIND_MESSAGE = "message"
