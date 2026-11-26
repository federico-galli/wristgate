package io.github.federicogalli.wristgate

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Keeps the watch buzzing while a call rings.
 *
 * A forwarded notification buzzes the watch once; a real call keeps buzzing. So while the call rings,
 * post a fresh copy every few seconds. Each copy has a new id and a different text ("ringing for 9 s"):
 * companion apps such as Garmin Connect treat a re-post with the same id or the same text as an update
 * and don't buzz again.
 *
 * The copies are all removed together when the call ends, not while it rings. The phone-to-watch
 * Bluetooth link is slow (on a Garmin Forerunner 265, about 5 writes per second, and every post and every
 * removal costs several): removing old copies while ringing doubled the traffic, built a queue, and buzzes
 * arrived late or not at all. Measured on Garmin: every 3 s without removals arrives in full; every 1-2 s,
 * or removing as we go, does not.
 *
 * Used by real calls (WristgateService) and by the "test call" button, so the test shows exactly what a call does.
 */
object Ringer {
    private const val TAG = "Wristgate"

    /** Buzz again this often… */
    private const val EVERY_MS = 3_000L
    /** …for at most this long (safety net if the end of the call is never seen). */
    const val MAX_MS = 60_000L

    private class Ring(
        /** ids of our copies on the watch, oldest first */
        val ids: MutableList<Int>,
        var title: String,
        /** the calling app's name, first part of the text */
        val app: String,
        val start: Long = SystemClock.elapsedRealtime(),
        var task: Runnable? = null,
    )

    private val rings = mutableMapOf<String, Ring>()
    private val handler = Handler(Looper.getMainLooper())

    private fun idFor(key: String, count: Int) = "$key#$count".hashCode()

    /**
     * Starts ringing for [key]: buzzes now, then every few seconds for [forMs] or until [stop].
     * On the watch: "📞 Mom" / "WhatsApp · incoming call", then "WhatsApp · ringing for 3 s"…
     * [removeAtEnd]: remove the copies when [forMs] runs out (tests: no call end will come to clean up).
     */
    fun start(
        context: Context, key: String, title: String, app: String,
        forMs: Long = MAX_MS, removeAtEnd: Boolean = false,
    ) {
        val c = context.applicationContext
        stop(c, key)
        val r = Ring(mutableListOf(idFor(key, 0)), title, app)
        rings[key] = r
        Bridge.post(c, r.ids.last(), title, c.getString(R.string.ring_first, app), alert = true)
        Log.d(TAG, "ringing key=$key")

        val task = object : Runnable {
            override fun run() {
                if (rings[key] !== r) return
                val elapsed = SystemClock.elapsedRealtime() - r.start
                if (elapsed > forMs) {
                    Log.d(TAG, "ringing ended (time limit) key=$key after ${r.ids.size} buzzes")
                    // Otherwise stop buzzing but remember the copies: stop() removes them when the call ends.
                    if (removeAtEnd) stop(c, key) else r.task = null
                    return
                }
                r.ids += idFor(key, r.ids.size)
                val text = c.getString(R.string.ring_later, r.app, ((elapsed + 500) / 1000).toInt())
                Bridge.post(c, r.ids.last(), r.title, text, alert = true)
                Log.d(TAG, "buzz #${r.ids.size} key=$key")
                handler.postDelayed(this, EVERY_MS)
            }
        }
        r.task = task
        handler.postDelayed(task, EVERY_MS)
    }

    /** The app updated its ringing notification: new caller name from the next buzz, no extra buzz now. */
    fun update(key: String, title: String) {
        rings[key]?.title = title
    }

    /** Stops ringing for [key] and removes all its copies from the watch. */
    fun stop(ctx: Context, key: String) {
        val r = rings.remove(key) ?: return
        r.task?.let { handler.removeCallbacks(it) }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        r.ids.forEach { nm.cancel(it) }
        Log.d(TAG, "ringing stopped key=$key, ${r.ids.size} copies removed")
    }
}
