package io.github.federicogalli.wristgate

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Watches the notifications of the supported apps and re-posts, as Wristgate's own notification:
 * - incoming calls;
 * - "important" messages (matching keywords or chosen contacts).
 * In the watch's companion app you then leave only Wristgate on, and the watch gets only what matters.
 */
class WristgateService : NotificationListenerService() {

    companion object {
        private const val TAG = "Wristgate"
        private const val CALL_STYLE = "android.app.Notification\$CallStyle"
    }

    private data class Msg(val sender: String, val text: String, val time: Long)

    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val store by lazy { Store(this) }

    /** source notification key -> history entry id, for calls that are ringing (the buzzing is Ringer's job) */
    private val ringing = mutableMapOf<String, String>()

    /** source notification key -> timestamp of the newest message already examined */
    private val lastSeen = mutableMapOf<String, Long>()

    /** source notification key -> last text examined (for apps without MessagingStyle) */
    private val lastText = mutableMapOf<String, String>()

    /** source notification key -> ids of our copies, removed when you read the message on the phone */
    private val forwarded = mutableMapOf<String, MutableSet<Int>>()

    override fun onCreate() {
        super.onCreate()
        Bridge.ensureChannels(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val source = Sources.forPackage(sbn.packageName) ?: return
        val n = sbn.notification

        // Debug log: shows what each app posts (adb logcat -s Wristgate). No message content is logged.
        Log.d(
            TAG,
            "posted ${sbn.packageName} key=${sbn.key} cat=${n.category} fsi=${n.fullScreenIntent != null} " +
                "ongoing=${sbn.isOngoing} template=${n.extras.getString(Notification.EXTRA_TEMPLATE)} " +
                "callType=${n.extras.getInt(Notification.EXTRA_CALL_TYPE, -1)}"
        )

        when {
            isIncomingCall(sbn) -> onIncomingCall(sbn, source)
            // Same notification updated to "call in progress": you answered.
            sbn.key in ringing && isOngoingCall(sbn) -> endCall(sbn, Store.OUTCOME_ANSWERED)
            // Any other update of a ringing call (or another call notification): not a message, keep ringing.
            sbn.key in ringing || n.category == Notification.CATEGORY_CALL ->
                Log.d(TAG, "call update ignored key=${sbn.key}")
            else -> onMessage(sbn, source)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (Sources.forPackage(sbn.packageName) == null) return
        Log.d(TAG, "removed ${sbn.packageName} key=${sbn.key}")
        // Call declined or missed.
        endCall(sbn, Store.OUTCOME_MISSED)
        // Messages read on the phone: remove our copies from the watch too.
        forwarded.remove(sbn.key)?.forEach { nm.cancel(it) }
        lastSeen.remove(sbn.key)
        lastText.remove(sbn.key)
    }

    // ---------------------------------------------------------------- calls

    private fun isIncomingCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val extras = n.extras

        // Android 12+: modern apps use CallStyle, which states the call type explicitly.
        if (Build.VERSION.SDK_INT >= 31 && extras.getString(Notification.EXTRA_TEMPLATE) == CALL_STYLE) {
            return extras.getInt(Notification.EXTRA_CALL_TYPE) == Notification.CallStyle.CALL_TYPE_INCOMING
        }
        // Fallback: CALL category + full-screen intent = incoming-call screen.
        return n.category == Notification.CATEGORY_CALL && n.fullScreenIntent != null
    }

    /** The call notification now says "call in progress" (you answered), not "incoming". */
    private fun isOngoingCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val extras = n.extras
        if (Build.VERSION.SDK_INT >= 31 && extras.getString(Notification.EXTRA_TEMPLATE) == CALL_STYLE) {
            return extras.getInt(Notification.EXTRA_CALL_TYPE) == Notification.CallStyle.CALL_TYPE_ONGOING
        }
        return n.category == Notification.CATEGORY_CALL && n.fullScreenIntent == null && sbn.isOngoing
    }

    private fun onIncomingCall(sbn: StatusBarNotification, source: Source) {
        if (!store.callsEnabled(source)) return

        val extras = sbn.notification.extras
        val caller = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: getString(R.string.unknown_caller)
        // The caller first: it's what you look at on the watch. The app goes in the text ("WhatsApp · incoming call").
        val title = "📞 $caller"

        if (sbn.key in ringing) {
            // The app updated its ringing notification: new name from the next buzz, no extra buzz now.
            Ringer.update(sbn.key, title)
            return
        }

        val entry = "${sbn.key}@${sbn.postTime}"
        ringing[sbn.key] = entry
        Log.d(TAG, "call ringing from ${source.label} key=${sbn.key}")
        Ringer.start(this, sbn.key, title, source.label)
        store.addCall(Store.Call(entry, System.currentTimeMillis(), source.id, caller))
    }

    private fun endCall(sbn: StatusBarNotification, outcome: String) {
        val entry = ringing.remove(sbn.key) ?: return
        Ringer.stop(this, sbn.key)
        store.setOutcome(entry, outcome)
        Log.d(TAG, "call ended ($outcome) key=${sbn.key}")
    }

    // ---------------------------------------------------------------- important messages

    private fun onMessage(sbn: StatusBarNotification, source: Source) {
        val n = sbn.notification
        if (!store.messagesEnabled(source)) return
        // Group summaries repeat messages already seen; ongoing notifications are not messages.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return

        val conversation = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val fresh = newMessages(sbn)
        if (fresh.isEmpty()) return

        conversation?.let { store.noteSender(it) }
        val keywords = store.keywords
        val contacts = store.contacts

        for (m in fresh) {
            store.noteSender(m.sender)
            val reason = Rules.match(keywords, contacts, m.sender, conversation, m.text) ?: continue

            val who = if (conversation != null && conversation != m.sender) "${m.sender} · $conversation" else m.sender
            val id = "${sbn.key}|${m.time}|${m.text}".hashCode()
            forwarded.getOrPut(sbn.key) { mutableSetOf() }.add(id)

            Log.d(TAG, "important message from ${source.label}")
            Bridge.postMessage(this, id, "💬 ${source.label}: $who", m.text)
            store.addCall(Store.Call(id.toString(), m.time, source.id, who, reason, KIND_MESSAGE))
        }
    }

    /** The messages in this notification that we haven't examined yet. */
    private fun newMessages(sbn: StatusBarNotification): List<Msg> {
        val n = sbn.notification
        val all = messagingStyle(n.extras)

        if (all.isNotEmpty()) {
            // The first time we see a conversation, only consider the last 2 minutes,
            // so old messages aren't forwarded again (e.g. after the service restarts).
            val since = lastSeen[sbn.key] ?: (System.currentTimeMillis() - 2 * 60_000)
            lastSeen[sbn.key] = maxOf(since, all.maxOf { it.time })
            return all.filter { it.time > since }
        }

        // Fallback for plain notifications: title = sender, text = message.
        val sender = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return emptyList()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return emptyList()
        if (lastText[sbn.key] == text) return emptyList()
        lastText[sbn.key] = text
        return listOf(Msg(sender, text, sbn.postTime))
    }

    /** Reads the messages of a MessagingStyle notification (used by WhatsApp, Signal, Telegram and others). */
    private fun messagingStyle(extras: Bundle): List<Msg> {
        @Suppress("DEPRECATION")
        val arr = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        // Your own name in the conversation: messages you wrote are never forwarded.
        @Suppress("DEPRECATION")
        val me = (if (Build.VERSION.SDK_INT >= 28) extras.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)?.name else null)
            ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)
        return arr.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            val sender = b.getCharSequence("sender")?.toString()
                ?: if (Build.VERSION.SDK_INT >= 28) {
                    @Suppress("DEPRECATION")
                    (b.getParcelable<Person>("sender_person"))?.name?.toString()
                } else null
            // No sender (or yourself) = a message you wrote.
            if (sender == null || sender == me?.toString()) return@mapNotNull null
            Msg(sender, text, b.getLong("time"))
        }
    }
}
