package io.github.federicogalli.wristgate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast

/** Tests on the watch: a single notification, and a simulated ringing call (same code as real calls). */
class TestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)
        edgeToEdge()

        findViewById<View>(R.id.back).setOnClickListener { finish() }

        findViewById<View>(R.id.testButton).setOnClickListener {
            if (!canNotify()) return@setOnClickListener
            Bridge.postTest(this)
            Toast.makeText(this, R.string.toast_test_sent, Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.testCallButton).setOnClickListener {
            if (!canNotify()) return@setOnClickListener
            Ringer.start(
                this, "test-call", "📞 " + getString(R.string.test_call_title), "Wristgate",
                forMs = 20_000, removeAtEnd = true,
            )
            Toast.makeText(this, R.string.toast_test_call, Toast.LENGTH_SHORT).show()
        }

        val links = findViewById<LinearLayout>(R.id.links)
        links.clipToOutline = true
        links.addView(linkRow(links, getString(R.string.more_watch), getString(R.string.more_watch_sub)) {
            startActivity(Intent(this, WatchSetupActivity::class.java))
        })
    }
}
