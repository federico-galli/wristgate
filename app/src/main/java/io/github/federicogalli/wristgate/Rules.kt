package io.github.federicogalli.wristgate

import java.text.Normalizer

/** Decides whether a message is "important": it only has to match one rule. */
object Rules {

    private val marks = Regex("\\p{Mn}+")

    /** Lowercase, without accents: "URGÈNTE" → "urgente". */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase().trim()

    /**
     * @return why the message is forwarded, as a code for the history
     *         ([Store.REASON_FROM] or [Store.REASON_KEYWORD] + value), or null if it doesn't match.
     */
    fun match(
        keywords: Set<String>,
        contacts: Set<String>,
        sender: String,
        conversation: String?,
        text: String,
    ): String? {
        val who = norm(sender)
        val group = conversation?.let(::norm)
        contacts.firstOrNull { norm(it).let { c -> c == who || c == group } }
            ?.let { return Store.REASON_FROM + it }

        val body = norm(text)
        keywords.firstOrNull { it.isNotBlank() && body.contains(norm(it)) }
            ?.let { return Store.REASON_KEYWORD + it }

        return null
    }
}
