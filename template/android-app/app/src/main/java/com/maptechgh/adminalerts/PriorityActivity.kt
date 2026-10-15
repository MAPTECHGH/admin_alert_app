package com.maptechgh.adminalerts

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.HorizontalScrollView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Every priority alert on this phone (only switched-on types), grouped by day with type filters. */
class PriorityActivity : Activity() {

    private lateinit var s: Screen
    private var filter = ""
    private var receiverOn = false
    private val updates = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { build() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        s = Screen(this, "Priority alerts")
        s.action("Clear") { confirmClear() }
        build()
    }

    override fun onResume() {
        super.onResume()
        AlertPlayer.stop(this)
        if (!receiverOn) {
            val f = IntentFilter(AlertService.ACTION_UPDATED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(updates, f)
            receiverOn = true
        }
        build()
    }

    override fun onPause() {
        super.onPause()
        if (receiverOn) { try { unregisterReceiver(updates) } catch (e: Exception) { }; receiverOn = false }
    }

    private fun build() {
        val b = s.body
        b.removeAllViews()
        val all = AlertCards.visible(this)
        val hidden = Prefs.history(this).length() - all.size
        s.setSubtitle(if (all.isEmpty()) "Nothing waiting" else "${all.size} alert${if (all.size == 1) "" else "s"} on this phone")

        // type filter chips
        val counts = LinkedHashMap<String, Int>()
        for (e in all) { val t = e.optString("type"); counts[t] = (counts[t] ?: 0) + 1 }
        if (filter.isNotEmpty() && !counts.containsKey(filter)) filter = ""
        if (counts.size > 1) {
            val row = hbox(this)
            row.addView(chip(this, "All  ${all.size}", filter.isEmpty()) { filter = ""; build() })
            for ((t, n) in counts) {
                row.addView(chip(this, "${AlertCards.label(t)}  $n", filter == t) { filter = t; build() }.apply {
                    layoutParams = lp(WRAP, WRAP).margins(l = dp(8))
                })
            }
            b.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, lp().margins(b = dp(12)))
        }

        if (hidden > 0) {
            val n = card(this)
            n.addView(tv(this, "$hidden older alert${if (hidden == 1) " is" else "s are"} hidden because that alert type is switched off.", 12.5f, C.MUTED))
            n.addView(tv(this, "Alert settings →", 13f, C.GOLD, true).apply {
                setPadding(0, dp(6), 0, 0)
                setOnClickListener { startActivity(Intent(this@PriorityActivity, AlertSettingsActivity::class.java)) }
            })
            b.addView(n)
        }

        val items = if (filter.isEmpty()) all else all.filter { it.optString("type") == filter }
        if (items.isEmpty()) {
            b.addView(emptyView(this, "No priority alerts. Complaints, failed or stuck orders and other switched-on alerts will appear here."))
            return
        }
        var lastDay = ""
        val group = ArrayList<JSONObject>()
        fun flush() {
            if (group.isEmpty()) return
            b.addView(sectionTitle(this, "$lastDay  •  ${group.size}"))
            for (e in group) b.addView(AlertCards.queueCard(this, e), lp().margins(b = dp(10)))
            group.clear()
        }
        for (e in items) {
            val d = dayLabel(e.optLong("received_at"))
            if (d != lastDay) { flush(); lastDay = d }
            group.add(e)
        }
        flush()
    }

    private fun dayLabel(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val now = Calendar.getInstance()
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (same(c, now)) return "Today"
        now.add(Calendar.DAY_OF_YEAR, -1)
        if (same(c, now)) return "Yesterday"
        return SimpleDateFormat("EEE d MMM", Locale.US).format(c.time)
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear all alerts?")
            .setMessage("This only clears the list on this phone. Nothing changes on the website.")
            .setPositiveButton("Clear") { _, _ -> Prefs.clearHistory(this); build() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
