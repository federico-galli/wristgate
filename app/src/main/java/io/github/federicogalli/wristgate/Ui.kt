package io.github.federicogalli.wristgate

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/** Small UI helpers shared by Wristgate's screens. */

/** The installed package of [s] (it may have several variants), or null. */
fun Context.installedPackage(s: Source): String? = s.packages.firstOrNull {
    try {
        packageManager.getApplicationInfo(it, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

fun Context.installedSources(): List<Source> = Sources.ALL.filter { installedPackage(it) != null }

/** The app's own icon, or a neutral phone icon if it's not installed. */
fun Context.iconFor(s: Source?): Drawable {
    val pkg = s?.let { installedPackage(it) }
    if (pkg != null) return packageManager.getApplicationIcon(pkg)
    return getDrawable(R.drawable.ic_call)!!.mutate().apply { setTint(getColor(R.color.text_secondary)) }
}

/** "A, B and C", with the "and" in the phone's language. */
fun Context.joinList(items: List<String>) =
    if (items.size <= 1) items.joinToString()
    else items.dropLast(1).joinToString(", ") + getString(R.string.list_and) + items.last()

/** Turns a stored outcome/reason code into text in the phone's language. */
fun Context.describeOutcome(code: String?): String? = when {
    code == null -> null
    code == Store.OUTCOME_ANSWERED -> getString(R.string.outcome_answered)
    code == Store.OUTCOME_MISSED -> getString(R.string.outcome_missed)
    code.startsWith(Store.REASON_FROM) -> getString(R.string.reason_from, code.removePrefix(Store.REASON_FROM))
    code.startsWith(Store.REASON_KEYWORD) -> getString(R.string.reason_keyword, code.removePrefix(Store.REASON_KEYWORD))
    else -> code
}

/** "📞  Mom" / "💬  Mom" */
fun Store.Call.label() = (if (kind == KIND_MESSAGE) "💬  " else "📞  ") + caller

/** "WhatsApp · 2 min ago · Missed" */
fun Context.subtitleFor(c: Store.Call, now: Long = System.currentTimeMillis()): String {
    val isMessage = c.kind == KIND_MESSAGE
    val outcome = describeOutcome(c.outcome)
        ?: if (!isMessage && now - c.time < Ringer.MAX_MS + 30_000) getString(R.string.ringing) else null
    val whenText = DateUtils.getRelativeTimeSpanString(c.time, now, DateUtils.MINUTE_IN_MILLIS)
    return listOfNotNull(Sources.byId(c.sourceId)?.label, whenText, outcome).joinToString(" · ")
}

/** A history row (icon, who, app · when · outcome). */
fun Activity.historyRow(parent: ViewGroup, c: Store.Call, now: Long = System.currentTimeMillis()): View {
    val row = layoutInflater.inflate(R.layout.item_history, parent, false)
    row.findViewById<ImageView>(R.id.icon).setImageDrawable(iconFor(Sources.byId(c.sourceId)))
    row.findViewById<TextView>(R.id.title).text = c.label()
    row.findViewById<TextView>(R.id.subtitle).text = subtitleFor(c, now)
    return row
}

/** A "title / subtitle  ›" row that opens another screen. */
fun Activity.linkRow(parent: ViewGroup, title: String, subtitle: String?, onClick: () -> Unit): View {
    val row = layoutInflater.inflate(R.layout.item_link, parent, false)
    row.findViewById<TextView>(R.id.title).text = title
    row.findViewById<TextView>(R.id.subtitle).apply {
        text = subtitle
        visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
    row.setOnClickListener { onClick() }
    return row
}

/** False (and a hint) if Wristgate may not post notifications, so a test would show nothing. */
fun Activity.canNotify(): Boolean {
    if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return true
    Toast.makeText(this, R.string.toast_allow_first, Toast.LENGTH_LONG).show()
    return false
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/**
 * Draws the screen edge to edge and keeps its content clear of the status bar, the navigation bar,
 * camera cutouts and the keyboard. Android 15+ forces edge to edge on apps targeting it; doing it from
 * Android 11 makes every phone behave the same (and testable on older ones). Older versions keep the
 * classic layout. Call after setContentView.
 */
fun Activity.edgeToEdge() {
    if (Build.VERSION.SDK_INT < 30) return
    window.setDecorFitsSystemWindows(false)
    val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    val (l, t, r, b) = listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
    root.setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
        )
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        insets
    }
}
