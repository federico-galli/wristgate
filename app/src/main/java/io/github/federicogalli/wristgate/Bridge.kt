package io.github.federicogalli.wristgate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Posts Wristgate's own notifications, which the watch's companion app forwards to the watch. */
object Bridge {
    private const val CALLS = "calls"
    private const val MESSAGES = "messages"

    fun ensureChannels(ctx: Context) {
        val calls = NotificationChannel(CALLS, ctx.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.channel_calls_desc)
            // The phone is already ringing thanks to the original app: no second sound.
            setSound(null, null)
            enableVibration(false)
        }
        val messages = NotificationChannel(MESSAGES, ctx.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.channel_messages_desc)
            // The original app has already made its sound on the phone.
            setSound(null, null)
            enableVibration(false)
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(calls, messages))
    }

    /**
     * Incoming call. [alert] = false for the source app's own updates while it rings (no extra buzz);
     * the service re-posts with alert = true at intervals, so the watch keeps buzzing like a real call.
     */
    fun post(ctx: Context, id: Int, title: String, text: String, timeoutMs: Long = 60_000, alert: Boolean = false) =
        notify(ctx, CALLS, id, title, text, timeoutMs, onlyOnce = !alert)

    /** Test button: always a new id and always alerts, so the watch gets it even on repeated taps. */
    fun postTest(ctx: Context) = notify(
        ctx, CALLS, (System.currentTimeMillis() / 1000).toInt(),
        "📞 " + ctx.getString(R.string.test_title), ctx.getString(R.string.test_text),
        timeoutMs = 30_000, onlyOnce = false,
    )

    fun postMessage(ctx: Context, id: Int, title: String, text: String) =
        notify(ctx, MESSAGES, id, title, text, timeoutMs = 30 * 60_000, onlyOnce = false)

    private fun notify(
        ctx: Context, channel: String, id: Int, title: String, text: String, timeoutMs: Long, onlyOnce: Boolean,
    ) {
        ensureChannels(ctx)
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(ctx.getColor(R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            // Not CATEGORY_CALL, so watches don't treat it as a system "call".
            // On a Garmin Forerunner 265 the call category made no difference to the buzz (tested).
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(onlyOnce)                // ringing calls update their notification many times
            .setAutoCancel(true)
            .setTimeoutAfter(timeoutMs)                // safety net: it goes away anyway
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
