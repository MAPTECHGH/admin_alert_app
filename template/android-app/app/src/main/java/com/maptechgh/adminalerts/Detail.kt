package com.maptechgh.adminalerts

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Shared building blocks for the order / AFA detail screens. */
object Detail {

    /** "Label   value" row; long-press copies when [copy] is true. */
    fun kv(a: Activity, box: LinearLayout, label: String, value: String?, copy: Boolean = false, color: Int = C.TEXT) {
        if (value.isNullOrEmpty() || value == "null") return
        val r = hbox(a).apply { setPadding(0, a.dp(4), 0, a.dp(4)) }
        r.addView(tv(a, label, 13f, C.MUTED), lp(a.dp(122), WRAP))
        val v = tv(a, value, 14f, color)
        r.addView(v, lp(0, WRAP, 1f))
        if (copy) {
            r.addView(tv(a, "⧉", 16f, C.GOLD).apply { setPadding(a.dp(8), 0, a.dp(4), 0) })
            r.setOnClickListener { copyText(a, label, value) }
        }
        box.addView(r)
    }

    /** Monospace JSON block that starts collapsed; tap to expand, long-press to copy. */
    fun jsonBlock(a: Activity, json: String, collapsedLines: Int = 10): View {
        val wrap = vbox(a).apply {
            background = rounded(C.BG, a.dp(10).toFloat(), C.STROKE, a.dp(1))
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
        }
        val code = TextView(a).apply {
            text = json
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(C.TEXT)
            setTextIsSelectable(true)
        }
        val lines = json.count { it == '\n' } + 1
        val toggle = tv(a, "", 12.5f, C.GOLD, true).apply { setPadding(0, a.dp(8), 0, 0) }
        var open = lines <= collapsedLines
        fun apply() {
            code.maxLines = if (open) Int.MAX_VALUE else collapsedLines
            toggle.text = if (lines <= collapsedLines) "Long-press to copy" else if (open) "Show less  •  long-press to copy" else "Show all $lines lines  •  long-press to copy"
        }
        apply()
        val click = View.OnClickListener { if (lines > collapsedLines) { open = !open; apply() } }
        toggle.setOnClickListener(click)
        wrap.setOnClickListener(click)
        wrap.setOnLongClickListener { copyText(a, "API response", json); true }
        code.setOnLongClickListener { copyText(a, "API response", json); true }
        wrap.addView(code)
        wrap.addView(toggle)
        return wrap
    }

    /** Collapsible item: header line + (hidden) JSON. */
    fun responseItem(a: Activity, header: String, sub: String?, json: String): View {
        val box = vbox(a).apply {
            background = ripple(rounded(C.SURFACE2, a.dp(12).toFloat(), C.STROKE, a.dp(1)))
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            layoutParams = lp().margins(b = a.dp(8))
        }
        val head = hbox(a)
        head.addView(tv(a, header, 13.5f, C.TEXT, true), lp(0, WRAP, 1f))
        val arrow = tv(a, "▾", 16f, C.MUTED)
        head.addView(arrow)
        box.addView(head)
        if (!sub.isNullOrEmpty()) box.addView(tv(a, sub, 12.5f, C.ORANGE).apply { maxLines = 3 })
        val body = jsonBlock(a, json, 40).apply { visibility = View.GONE }
        box.addView(body, lp().margins(t = a.dp(8)))
        box.setOnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            arrow.text = if (show) "▴" else "▾"
        }
        return box
    }

    /** Vertical timeline: coloured dot, title, time, optional detail. */
    fun timeline(a: Activity, items: List<Triple<String, String, String?>>, colorOf: (String) -> Int): View {
        val box = vbox(a)
        items.forEachIndexed { i, (title, at, detail) ->
            val row = hbox(a).apply { gravity = android.view.Gravity.TOP }
            val rail = vbox(a).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
            rail.addView(View(a).apply { background = rounded(colorOf(title), a.dp(6).toFloat()) }, lp(a.dp(12), a.dp(12)).margins(t = a.dp(4)))
            if (i < items.size - 1) rail.addView(View(a).apply { setBackgroundColor(C.STROKE) }, lp(a.dp(2), a.dp(34)))
            row.addView(rail, lp(a.dp(20), WRAP).margins(r = a.dp(10)))
            val t = vbox(a)
            t.addView(tv(a, title, 14f, C.TEXT, true))
            t.addView(tv(a, at + "  •  " + agoServer(at), 12f, C.MUTED))
            if (!detail.isNullOrEmpty()) t.addView(tv(a, detail, 12.5f, C.MUTED).apply { maxLines = 4 })
            row.addView(t, lp(0, WRAP, 1f).margins(b = a.dp(10)))
            box.addView(row)
        }
        return box
    }

    fun timelineColor(title: String): Int {
        val t = title.lowercase()
        return when {
            "deliver" in t -> C.GREEN
            "fail" in t || "review" in t -> C.RED
            "refund" in t || "cancel" in t -> C.PURPLE
            "re-sent" in t || "resent" in t || "provider" in t -> C.BLUE
            "response" in t -> C.MUTED
            else -> C.ORANGE
        }
    }

    /**
     * Status picker used for single and bulk changes. For data orders, choosing
     * "accepted" (requeue) can also switch provider and pick same/new send key.
     */
    fun statusDialog(
        a: Activity,
        title: String,
        statuses: List<String>,
        current: String?,
        providers: JSONArray?,
        allowRequeueOptions: Boolean,
        onPick: (status: String, providerId: String, keyMode: String) -> Unit,
    ) {
        val box = vbox(a).apply { setPadding(a.dp(22), a.dp(8), a.dp(22), 0) }
        box.addView(tv(a, "New status", 12.5f, C.MUTED))
        val labels = statuses.map { if (it == "accepted") "accepted (requeue / send again)" else it }
        val sp = Spinner(a)
        sp.adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = statuses.indexOf(current)
        if (idx >= 0) sp.setSelection(idx)
        box.addView(sp, lp().margins(b = a.dp(8)))

        val requeue = vbox(a)
        var provSpinner: Spinner? = null
        val provIds = ArrayList<String>()
        if (allowRequeueOptions && providers != null && providers.length() > 0) {
            requeue.addView(tv(a, "Send with provider", 12.5f, C.MUTED))
            val names = ArrayList<String>()
            names.add("Keep current provider"); provIds.add("")
            for (i in 0 until providers.length()) {
                val p = providers.optJSONObject(i) ?: continue
                names.add(p.optString("name")); provIds.add(p.optInt("id").toString())
            }
            provSpinner = Spinner(a).apply { adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, names) }
            requeue.addView(provSpinner, lp().margins(b = a.dp(8)))
        }
        val keyGroup = RadioGroup(a)
        if (allowRequeueOptions) {
            requeue.addView(tv(a, "Send key", 12.5f, C.MUTED))
            val newKey = RadioButton(a).apply { id = View.generateViewId(); text = "New key (normal)"; setTextColor(C.TEXT); isChecked = true }
            val sameKey = RadioButton(a).apply { id = View.generateViewId(); text = "Same key as last time (avoid double send)"; setTextColor(C.TEXT); tag = "same" }
            keyGroup.addView(newKey); keyGroup.addView(sameKey)
            requeue.addView(keyGroup)
        }
        requeue.visibility = View.GONE
        box.addView(requeue)
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                requeue.visibility = if (allowRequeueOptions && statuses[pos] == "accepted") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        AlertDialog.Builder(a)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                val st = statuses[sp.selectedItemPosition]
                val pid = provSpinner?.let { provIds.getOrNull(it.selectedItemPosition) } ?: ""
                val checked = keyGroup.findViewById<RadioButton>(keyGroup.checkedRadioButtonId)
                val keyMode = if (checked?.tag == "same") "same" else "new"
                if (st == "refunded" || st == "cancelled") {
                    AlertDialog.Builder(a)
                        .setTitle("Mark as $st?")
                        .setMessage(if (st == "refunded") "The customer will be refunded (wallet or payment gateway). This can't be undone from the app."
                                    else "The order will be cancelled.")
                        .setPositiveButton("Yes, $st") { _, _ -> onPick(st, pid, keyMode) }
                        .setNegativeButton("Back", null)
                        .show()
                } else onPick(st, pid, keyMode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun ids(set: Collection<Int>): JSONArray = JSONArray().also { arr -> set.forEach { arr.put(it) } }

    fun statusBody(status: String, providerId: String, keyMode: String): JSONObject =
        JSONObject().put("status", status).put("provider_id", providerId).put("key_mode", keyMode)
}
