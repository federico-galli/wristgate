package io.github.federicogalli.wristgate

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView

/** Rules for important messages: keywords and contacts/groups. */
class RulesActivity : Activity() {

    companion object {
        private const val PICK_CONTACT = 1
    }

    private val store by lazy { Store(this) }

    private lateinit var keywordChips: FlowLayout
    private lateinit var contactChips: FlowLayout
    private lateinit var recentChips: FlowLayout
    private lateinit var keywordEmpty: View
    private lateinit var contactEmpty: View
    private lateinit var recentLabel: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        keywordChips = findViewById(R.id.keywordChips)
        contactChips = findViewById(R.id.contactChips)
        recentChips = findViewById(R.id.recentChips)
        keywordEmpty = findViewById(R.id.keywordEmpty)
        contactEmpty = findViewById(R.id.contactEmpty)
        recentLabel = findViewById(R.id.recentLabel)

        findViewById<View>(R.id.back).setOnClickListener { finish() }

        wireInput(findViewById(R.id.keywordInput), findViewById(R.id.keywordAdd)) { word ->
            store.keywords = store.keywords + word.lowercase()
        }
        wireInput(findViewById(R.id.contactInput), findViewById(R.id.contactAdd)) { name ->
            store.contacts = store.contacts + name
        }

        // System contact picker: no READ_CONTACTS permission needed,
        // Android only lets us read the contact the user picked.
        findViewById<View>(R.id.contactPick).setOnClickListener {
            try {
                startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI), PICK_CONTACT)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.contacts_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Plain Activity without AndroidX: this is fine")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_CONTACT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (name.isNullOrBlank()) return
        store.contacts = store.contacts + name
        Toast.makeText(this, getString(R.string.contact_added, name), Toast.LENGTH_SHORT).show()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun wireInput(input: EditText, button: View, add: (String) -> Unit) {
        fun commit() {
            val value = input.text.toString().trim()
            if (value.isEmpty()) return
            add(value)
            input.text.clear()
            render()
        }
        button.setOnClickListener { commit() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { commit(); true } else false
        }
    }

    private fun render() {
        val keywords = store.keywords.sorted()
        keywordChips.removeAllViews()
        keywords.forEach { word ->
            keywordChips.addView(chip("$word  ✕", filled = true) {
                store.keywords = store.keywords - word
                render()
            })
        }
        keywordEmpty.visibility = if (keywords.isEmpty()) View.VISIBLE else View.GONE
        keywordChips.visibility = if (keywords.isEmpty()) View.GONE else View.VISIBLE

        val contacts = store.contacts.sortedBy { Rules.norm(it) }
        contactChips.removeAllViews()
        contacts.forEach { name ->
            contactChips.addView(chip("$name  ✕", filled = true) {
                store.contacts = store.contacts - name
                render()
            })
        }
        contactEmpty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
        contactChips.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE

        // Recently seen senders not already in the rules: one tap to add them.
        val already = contacts.map(Rules::norm).toSet()
        val recent = store.recentSenders().filter { Rules.norm(it) !in already }
        recentChips.removeAllViews()
        recent.forEach { name ->
            recentChips.addView(chip("+  $name", filled = false) {
                store.contacts = store.contacts + name
                render()
            })
        }
        val vis = if (recent.isEmpty()) View.GONE else View.VISIBLE
        recentLabel.visibility = vis
        recentChips.visibility = vis
    }

    private fun chip(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(getColor(R.color.text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val h = dp(14); val v = dp(8)
        setPadding(h, v, h, v)
        setBackgroundResource(if (filled) R.drawable.bg_chip else R.drawable.bg_chip_outline)
        maxLines = 1
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
