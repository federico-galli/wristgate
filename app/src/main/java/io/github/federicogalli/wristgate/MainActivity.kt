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
    private lateinit var callsGrid: LinearLayout
    private lateinit var messagesGrid: LinearLayout
    private lateinit var messages: LinearLayout
    private lateinit var statusLast: TextView
    private lateinit var more: LinearLayout

    /** Refreshes the "last forwarded" line and the history count live while the app is open. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.KEY_HISTORY) {
            renderLast()
            renderMore()
        }
    }

    private data class Check(val title: String, val subtitle: String, val ok: Boolean, val fix: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        edgeToEdge()

        statusCard = findViewById(R.id.statusCard)
        statusIcon = findViewById(R.id.statusIcon)
        statusTitle = findViewById(R.id.statusTitle)
        statusText = findViewById(R.id.statusText)
        callsGrid = findViewById(R.id.callsGrid)
        messagesGrid = findViewById(R.id.messagesGrid)
        messages = findViewById(R.id.messages)
        statusLast = findViewById(R.id.statusLast)
        more = findViewById(R.id.more)

        // Cards clip their rows' ripple to the rounded corners.
        listOf(callsGrid, messages, more).forEach { it.clipToOutline = true }

        renderAbout()
    }

    override fun onResume() {
        super.onResume()
        renderGrids()
        renderMessages()
        renderStatus()
        renderLast()
        renderMore()
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
                openBatterySettings()
            },
        )
    }

    /**
     * Straight to Wristgate's battery page (Unrestricted / Optimized / Restricted) where the phone has it,
     * otherwise the app's info page (Battery → Unrestricted). Asking for the exemption directly would need
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Google Play allows only for a few kinds of apps.
     * The battery page's action isn't public API, but the system Settings app exports it (checked on Samsung).
     */
    private fun openBatterySettings() {
        val pkg = Uri.parse("package:$packageName")
        // Highlighting isn't reliable across phones, so a toast also says what to tap.
        val hint = try {
            startActivity(
                Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", pkg)
                    // Asks Settings to highlight the option. Stock Android may honour it; Samsung ignores it.
                    .putExtra(":settings:fragment_args_key", "unrestricted_pref")
            )
            R.string.hint_battery_page
        } catch (e: RuntimeException) { // ActivityNotFoundException, SecurityException
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            R.string.hint_app_page
        }
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
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
        val installed = installedSources()
        val callApps = installed.filter { store.callsEnabled(it) }.map { it.label }
        val messageApps = installed.filter { store.messagesEnabled(it) }.map { it.label }
        val enabled = (callApps + messageApps).distinct()

        val ok = missing.isEmpty() && enabled.isNotEmpty()
        when {
            missing.isNotEmpty() -> {
                statusTitle.text = getString(R.string.status_almost)
                // Say what's missing first: tapping the card opens exactly that setting.
                statusText.text = getString(R.string.status_missing_first, missing.first().title) + "\n" +
                    missing.first().subtitle + "\n" +
                    (if (missing.size > 1) resources.getQuantityString(R.plurals.missing_permissions, missing.size, missing.size) + " · " else "") +
                    getString(R.string.status_tap_to_fix)
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
        // The card is what people tap: when something is missing, it opens the first fix.
        statusCard.isClickable = missing.isNotEmpty()
        statusCard.clipToOutline = true // keep the press highlight inside the rounded corners
        statusCard.foreground = if (missing.isNotEmpty()) getDrawable(android.R.drawable.list_selector_background) else null
        if (missing.isNotEmpty()) statusCard.setOnClickListener { missing.first().fix() } else statusCard.setOnClickListener(null)
        statusCard.backgroundTintList = ColorStateList.valueOf(getColor(if (ok) R.color.ok_container else R.color.warn_container))
        statusIcon.backgroundTintList = ColorStateList.valueOf(getColor(if (ok) R.color.accent else R.color.warn))
        statusIcon.setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_warning)
    }

    // ---------------------------------------------------------------- source apps

    private fun renderGrids() {
        val installed = installedSources()
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

    // ---------------------------------------------------------------- last forwarded, other screens

    /** "Last: 📞 Mom · 2 min ago" in the status card. */
    private fun renderLast() {
        val last = store.history().firstOrNull()
        statusLast.visibility = if (last == null) View.GONE else View.VISIBLE
        if (last == null) return
        val whenText = DateUtils.getRelativeTimeSpanString(last.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        statusLast.text = getString(R.string.status_last, last.label().replace("  ", " "), whenText)
    }

    private fun renderMore() {
        more.removeAllViews()
        val history = store.history()
        val today = history.count { DateUtils.isToday(it.time) }
        val historySub = when {
            history.isEmpty() -> getString(R.string.history_empty)
            today == 0 -> getString(R.string.history_none_today)
            else -> resources.getQuantityString(R.plurals.history_today, today, today)
        }
        more.addView(linkRow(more, getString(R.string.more_history), historySub) {
            startActivity(Intent(this, HistoryActivity::class.java))
        })
        more.addView(linkRow(more, getString(R.string.more_test), getString(R.string.more_test_sub)) {
            startActivity(Intent(this, TestActivity::class.java))
        })
        more.addView(linkRow(more, getString(R.string.more_watch), getString(R.string.more_watch_sub)) {
            startActivity(Intent(this, WatchSetupActivity::class.java))
        })
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

    private fun inflate(layout: Int, parent: ViewGroup): View = layoutInflater.inflate(layout, parent, false)
}
