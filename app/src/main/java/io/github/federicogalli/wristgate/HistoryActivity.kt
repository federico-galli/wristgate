package io.github.federicogalli.wristgate

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/** Everything forwarded to the watch, newest first, grouped by day, with a calls/messages filter. */
class HistoryActivity : Activity() {

    private enum class Filter { ALL, CALLS, MESSAGES }

    private val store by lazy { Store(this) }
    private var filter = Filter.ALL

    private lateinit var days: LinearLayout
    private lateinit var empty: TextView
    private lateinit var clear: View
    private lateinit var chips: Map<Filter, TextView>

    /** Refreshes the list live while the screen is open (a call ringing, a message arriving). */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.KEY_HISTORY) render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        days = findViewById(R.id.days)
        empty = findViewById(R.id.historyEmpty)
        clear = findViewById(R.id.clearHistory)
        chips = mapOf(
            Filter.ALL to findViewById(R.id.filterAll),
            Filter.CALLS to findViewById(R.id.filterCalls),
            Filter.MESSAGES to findViewById(R.id.filterMessages),
        )
        chips.forEach { (f, chip) -> chip.setOnClickListener { filter = f; render() } }

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        clear.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        render()
        store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onPause() {
        super.onPause()
        store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun render() {
        chips.forEach { (f, chip) ->
            val on = f == filter
            chip.setBackgroundResource(if (on) R.drawable.bg_chip else R.drawable.bg_chip_outline)
            chip.isSelected = on
        }

        val all = store.history()
        val shown = all.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.CALLS -> it.kind != KIND_MESSAGE
                Filter.MESSAGES -> it.kind == KIND_MESSAGE
            }
        }
        clear.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = getString(if (all.isEmpty()) R.string.history_empty else R.string.history_empty_filtered)

        days.removeAllViews()
        val now = System.currentTimeMillis()
        shown.groupBy { startOfDay(it.time) }.forEach { (day, entries) ->
            days.addView(TextView(this, null, 0, R.style.SectionLabel).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(24); bottomMargin = dp(10); marginStart = dp(6) }
                text = dayLabel(day, now)
            })
            val card = LinearLayout(this, null, 0, R.style.Card).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                orientation = LinearLayout.VERTICAL
                clipToOutline = true
            }
            entries.forEach { card.addView(historyRow(card, it, now)) }
            days.addView(card)
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_history_title)
            .setMessage(R.string.clear_history_text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_history_confirm) { _, _ -> store.clearHistory() }
            .show()
    }

    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** "Today", "Yesterday", or "Monday 23 November" in the phone's language. */
    private fun dayLabel(day: Long, now: Long): String {
        val today = startOfDay(now)
        val yesterday = startOfDay(today - 1)
        return when (day) {
            today -> getString(R.string.today)
            yesterday -> getString(R.string.yesterday)
            else -> DateUtils.formatDateTime(this, day, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE)
        }
    }
}
