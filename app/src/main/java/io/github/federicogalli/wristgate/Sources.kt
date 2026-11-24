package io.github.federicogalli.wristgate

/**
 * An app whose calls and important messages can be forwarded (it may have several variants/packages).
 * @param core the main apps: always listed and on by default. The others are listed only when
 *             installed and start off: users turn them on only if they want them.
 */
data class Source(
    val id: String,
    val label: String,
    val packages: List<String>,
    val core: Boolean = false,
    /** false for apps without calls (e.g. SMS: watches already get regular phone calls on their own) */
    val calls: Boolean = true,
)

object Sources {
    val ALL = listOf(
        Source("signal", "Signal", listOf("org.thoughtcrime.securesms"), core = true),
        Source("whatsapp", "WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b"), core = true),
        Source(
            "telegram", "Telegram",
            listOf(
                "org.telegram.messenger",
                "org.telegram.messenger.web",   // APK downloaded from telegram.org
                "org.thunderdog.challegram",    // Telegram X
            ),
            core = true,
        ),
        Source("sms", "SMS", listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"), calls = false),
        Source("messenger", "Messenger", listOf("com.facebook.orca")),
        Source("instagram", "Instagram", listOf("com.instagram.android")),
        Source("discord", "Discord", listOf("com.discord")),
        Source("slack", "Slack", listOf("com.Slack")),
        Source("teams", "Teams", listOf("com.microsoft.teams")),
        Source("zoom", "Zoom", listOf("us.zoom.videomeetings")),
        Source("viber", "Viber", listOf("com.viber.voip")),
        Source("line", "LINE", listOf("jp.naver.line.android")),
        Source("threema", "Threema", listOf("ch.threema.app", "ch.threema.app.libre")),
        Source("element", "Element", listOf("im.vector.app")),
        Source("wechat", "WeChat", listOf("com.tencent.mm")),
    )

    private val byPackage = ALL.flatMap { s -> s.packages.map { it to s } }.toMap()

    fun forPackage(pkg: String): Source? = byPackage[pkg]
    fun byId(id: String): Source? = ALL.firstOrNull { it.id == id }
}
