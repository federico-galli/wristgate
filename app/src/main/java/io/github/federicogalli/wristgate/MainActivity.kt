package io.github.federicogalli.wristgate

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Html
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val COLUMNS = 5
        private const val SOURCE_URL = "https://github.com/federico-galli/wristgate"
        private const val AUTHOR_URL = "https://www.sideralis.org"
    }

    private val store by lazy { Store(this) }

    private lateinit var statusCard: View
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var permissionsLabel: View
    private lateinit var permissions: LinearLayout
    private lateinit var callsGrid: LinearLayout
    private lateinit var messagesGrid: LinearLayout
    private lateinit var messages: LinearLayout
    private lateinit var history: LinearLayout
    private lateinit var historyEmpty: View
    private lateinit var clearHistory: View

    /** Refreshes the history live while the app is open. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.KEY_HISTORY) renderHistory()
    }

    private data class Check(val title: String, val subtitle: String, val ok: Boolean, val fix: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusCard = findViewById(R.id.statusCard)
        statusIcon = findViewById(R.id.statusIcon)
        statusTitle = findViewById(R.id.statusTitle)
        statusText = findViewById(R.id.statusText)
        permissionsLabel = findViewById(R.id.permissionsLabel)
        permissions = findViewById(R.id.permissions)
        callsGrid = findViewById(R.id.callsGrid)
        messagesGrid = findViewById(R.id.messagesGrid)
        messages = findViewById(R.id.messages)
        history = findViewById(R.id.history)
        historyEmpty = findViewById(R.id.historyEmpty)
        clearHistory = findViewById(R.id.clearHistory)

        // Cards clip their rows' ripple to the rounded corners.
        listOf(permissions, callsGrid, messages, history).forEach { it.clipToOutline = true }

        findViewById<View>(R.id.testButton).setOnClickListener { sendTest() }
        clearHistory.setOnClickListener { store.clearHistory() }
        renderAbout()
    }

    override fun onResume() {
        super.onResume()
        renderGrids()
        renderMessages()
        renderStatus()
        renderHistory()
        store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onPause() {
        super.onPause()
        store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // ---------------------------------------------------------------- status and permissions

    private fun checks(): List<Check> {
        val listenerOn = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(this, WristgateService::class.java).flattenToString()) == true
        val notifOn = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        return listOf(
            Check(getString(R.string.check_listener_title), getString(R.string.check_listener_sub), listenerOn) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            Check(getString(R.string.check_post_title), getString(R.string.check_post_sub), notifOn) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                } else {
                    openNotificationSettings()
                }
            },
            Check(getString(R.string.check_battery_title), getString(R.string.check_battery_sub), batteryOk) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            },
        )
    }

    private fun openNotificationSettings() = startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    )

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<out String>, results: IntArray) {
        // If the user already declined, Android won't show the prompt again: open the settings.
        if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) openNotificationSettings()
        renderStatus()
    }

    private fun renderStatus() {
        val missing = checks().filterNot { it.ok }
        val installed = Sources.ALL.filter { installedPackage(it) != null }
        val callApps = installed.filter { store.callsEnabled(it) }.map { it.label }
        val messageApps = installed.filter { store.messagesEnabled(it) }.map { it.label }
        val enabled = (callApps + messageApps).distinct()

        renderWatchSetup(installed.filter { store.callsEnabled(it) || store.messagesEnabled(it) }.map { it.label })

        val ok = missing.isEmpty() && enabled.isNotEmpty()
        when {
            missing.isNotEmpty() -> {
                statusTitle.text = getString(R.string.status_almost)
                statusText.text = resources.getQuantityString(R.plurals.missing_permissions, missing.size, missing.size)
            }
            enabled.isEmpty() -> {
                statusTitle.text = getString(R.string.status_paused)
                statusText.text = getString(R.string.status_no_apps)
            }
            else -> {
                statusTitle.text = getString(R.string.status_active)
                statusText.text = when {
                    messageApps.isEmpty() -> getString(R.string.status_calls, joinList(callApps))
                    callApps.isEmpty() -> getString(R.string.status_messages, joinList(messageApps))
                    callApps == messageApps -> getString(R.string.status_both, joinList(callApps))
                    else -> getString(R.string.status_calls, joinList(callApps)) + "\n" +
                        getString(R.string.status_messages, joinList(messageApps))
                }
            }
        }
        statusCard.backgroundTintList = ColorStateList.valueOf(getColor(if (ok) R.color.ok_container else R.color.warn_container))
        statusIcon.backgroundTintList = ColorStateList.valueOf(getColor(if (ok) R.color.accent else R.color.warn))
        statusIcon.setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_warning)

        permissions.removeAllViews()
        missing.forEach { c ->
            val row = inflate(R.layout.item_permission, permissions)
            row.findViewById<TextView>(R.id.title).text = c.title
            row.findViewById<TextView>(R.id.subtitle).text = c.subtitle
            row.setOnClickListener { c.fix() }
            permissions.addView(row)
        }
        val vis = if (missing.isEmpty()) View.GONE else View.VISIBLE
        permissionsLabel.visibility = vis
        permissions.visibility = vis
    }

    /** Watch-app instructions that name exactly the apps to turn off, based on the current selection. */
    private fun renderWatchSetup(apps: List<String>) {
        val html = buildString {
            append(TextUtils.htmlEncode(getString(R.string.watch_intro)))
            append("<br><br>").append(getString(R.string.watch_allow))
            if (apps.isNotEmpty()) {
                append("<br>").append(getString(R.string.watch_turn_off, TextUtils.htmlEncode(joinList(apps))))
            }
            append("<br><br>").append(TextUtils.htmlEncode(getString(R.string.watch_outro)))
        }
        findViewById<TextView>(R.id.watchSetup).text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
    }

    // ---------------------------------------------------------------- source apps

    private fun installedPackage(s: Source): String? = s.packages.firstOrNull {
        try {
            packageManager.getApplicationInfo(it, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun iconFor(s: Source?): Drawable {
        val pkg = s?.let { installedPackage(it) }
        if (pkg != null) return packageManager.getApplicationIcon(pkg)
        return getDrawable(R.drawable.ic_call)!!.mutate().apply { setTint(getColor(R.color.text_secondary)) }
    }

    private fun renderGrids() {
        val installed = Sources.ALL.filter { installedPackage(it) != null }
        fillGrid(callsGrid, installed.filter { it.calls }, store::callsEnabled, store::setCalls)
        fillGrid(messagesGrid, installed, store::messagesEnabled, store::setMessages)
    }

    /** A grid of app icons, 4 per row: tap to toggle. On = full color with a check, off = faded gray. */
    private fun fillGrid(
        grid: LinearLayout,
        apps: List<Source>,
        isOn: (Source) -> Boolean,
        set: (Source, Boolean) -> Unit,
    ) {
        grid.removeAllViews()
        if (apps.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = getString(R.string.none_installed)
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            return
        }
        apps.chunked(COLUMNS).forEach { rowApps ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowApps.forEach { s -> row.addView(tile(row, s, isOn, set)) }
            // Empty cells keep the last row aligned with the ones above.
            repeat(COLUMNS - rowApps.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            grid.addView(row)
        }
    }

    private fun tile(parent: ViewGroup, s: Source, isOn: (Source) -> Boolean, set: (Source, Boolean) -> Unit): View {
        val v = inflate(R.layout.item_tile, parent)
        val icon = v.findViewById<ImageView>(R.id.icon)
        val check = v.findViewById<View>(R.id.check)
        val label = v.findViewById<TextView>(R.id.label)
        icon.setImageDrawable(iconFor(s))
        label.text = s.label

        fun paint() {
            val on = isOn(s)
            check.visibility = if (on) View.VISIBLE else View.INVISIBLE
            icon.colorFilter = if (on) null else grayscale
            icon.alpha = if (on) 1f else 0.45f
            label.setTextColor(getColor(if (on) R.color.text else R.color.text_secondary))
            v.contentDescription = "${s.label}: " + getString(if (on) R.string.state_on else R.string.state_off)
        }
        paint()
        v.setOnClickListener {
            set(s, !isOn(s))
            paint()
            // Small "press" bounce, so the tap feels acknowledged.
            icon.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70).withEndAction {
                icon.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
            renderStatus()
        }
        return v
    }

    private val grayscale by lazy {
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    // ---------------------------------------------------------------- important messages

    private fun renderMessages() {
        // Keep the icon grid (first child), rebuild the rules row below it.
        while (messages.childCount > 1) messages.removeViewAt(1)

        // Rules summary → rules screen.
        run {
            val link = inflate(R.layout.item_link, messages)
            val keywords = store.keywords.sorted()
            val contacts = store.contacts.size
            link.findViewById<TextView>(R.id.title).text = getString(R.string.rules)
            link.findViewById<TextView>(R.id.subtitle).text = listOfNotNull(
                keywords.takeIf { it.isNotEmpty() }?.joinToString(", ") { getString(R.string.reason_keyword, it) },
                if (contacts == 0) null else resources.getQuantityString(R.plurals.contacts_count, contacts, contacts),
            ).joinToString(" · ").ifEmpty { getString(R.string.rules_none) }
            link.setOnClickListener { startActivity(Intent(this, RulesActivity::class.java)) }
            messages.addView(link)
        }
    }

    // ---------------------------------------------------------------- history

    private fun renderHistory() {
        val calls = store.history()
        // Keep only the "empty" text, drop the previous rows.
        while (history.childCount > 1) history.removeViewAt(1)
        historyEmpty.visibility = if (calls.isEmpty()) View.VISIBLE else View.GONE
        clearHistory.visibility = if (calls.isEmpty()) View.INVISIBLE else View.VISIBLE

        val now = System.currentTimeMillis()
        calls.take(10).forEach { c ->
            val source = Sources.byId(c.sourceId)
            val row = inflate(R.layout.item_history, history)
            row.findViewById<ImageView>(R.id.icon).setImageDrawable(iconFor(source))
            val isMessage = c.kind == KIND_MESSAGE
            row.findViewById<TextView>(R.id.title).text = (if (isMessage) "💬  " else "📞  ") + c.caller

            val outcome = describeOutcome(c.outcome)
                ?: if (!isMessage && now - c.time < 90_000) getString(R.string.ringing) else null
            val whenText = DateUtils.getRelativeTimeSpanString(c.time, now, DateUtils.MINUTE_IN_MILLIS)
            row.findViewById<TextView>(R.id.subtitle).text =
                listOfNotNull(source?.label, whenText, outcome).joinToString(" · ")
            history.addView(row)
        }
    }

    // ---------------------------------------------------------------- test

    private fun sendTest() {
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            Toast.makeText(this, R.string.toast_allow_first, Toast.LENGTH_LONG).show()
            return
        }
        Bridge.postTest(this)
        Toast.makeText(this, R.string.toast_test_sent, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- about

    private fun renderAbout() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        // Two lines: name/version on top, links below (one line looked off-centre, links weigh more)
        val html = "Wristgate $version<br>" +
            "<a href=\"$SOURCE_URL\">${TextUtils.htmlEncode(getString(R.string.about_source))}</a> · " +
            "<a href=\"$AUTHOR_URL\">sideralis.org</a>"
        findViewById<TextView>(R.id.about).apply {
            text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    // ---------------------------------------------------------------- utilities

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun inflate(layout: Int, parent: ViewGroup): View = layoutInflater.inflate(layout, parent, false)

    /** "A, B and C", with the "and" in the phone's language. */
    private fun joinList(items: List<String>) =
        if (items.size <= 1) items.joinToString()
        else items.dropLast(1).joinToString(", ") + getString(R.string.list_and) + items.last()

    /** Turns a stored outcome code into text in the phone's language. */
    private fun describeOutcome(code: String?): String? = when {
        code == null -> null
        code == Store.OUTCOME_ANSWERED -> getString(R.string.outcome_answered)
        code == Store.OUTCOME_MISSED -> getString(R.string.outcome_missed)
        code.startsWith(Store.REASON_FROM) -> getString(R.string.reason_from, code.removePrefix(Store.REASON_FROM))
        code.startsWith(Store.REASON_KEYWORD) -> getString(R.string.reason_keyword, code.removePrefix(Store.REASON_KEYWORD))
        else -> code
    }
}
