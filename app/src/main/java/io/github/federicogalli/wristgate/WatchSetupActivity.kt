package io.github.federicogalli.wristgate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** How to set up the watch's companion app: allow Wristgate, turn off the apps it already covers. */
class WatchSetupActivity : Activity() {

    private val store by lazy { Store(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch)
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        val links = findViewById<LinearLayout>(R.id.links)
        links.clipToOutline = true
        links.addView(linkRow(links, getString(R.string.more_test), getString(R.string.more_test_sub)) {
            startActivity(Intent(this, TestActivity::class.java))
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Instructions that name exactly the apps to turn off, based on the current selection. */
    private fun render() {
        val apps = installedSources().filter { store.callsEnabled(it) || store.messagesEnabled(it) }.map { it.label }
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
}
