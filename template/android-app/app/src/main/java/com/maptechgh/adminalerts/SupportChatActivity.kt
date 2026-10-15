package com.maptechgh.adminalerts

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject

/** Live Support conversation with reply box; refreshes itself every 8 seconds while open. */
class SupportChatActivity : Activity() {

    private lateinit var s: Screen
    private lateinit var scroll: ScrollView
    private lateinit var msgs: LinearLayout
    private lateinit var inputBar: LinearLayout
    private var id = 0
    private var lastCount = -1
    private var canManage = false
    private var status = "open"
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { load(false); h.postDelayed(this, 8000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        AlertPlayer.stop(this)
        id = intent.getIntExtra("id", 0)
        s = Screen(this, "Live Support", "Chat #$id", scroll = false)
        s.body.setPadding(0, 0, 0, 0)
        s.action("Close chat") { confirmClose() }

        msgs = vbox(this).apply { setPadding(dp(14), dp(6), dp(14), dp(12)) }
        scroll = ScrollView(this).apply { addView(msgs, lp()) }
        s.body.addView(scroll, lp(MATCH, 0, 1f))

        inputBar = hbox(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(C.SURFACE)
        }
        val text = input(this, "Reply to client…", multiline = true).apply { minLines = 1; maxLines = 5 }
        inputBar.addView(text, lp(0, WRAP, 1f).margins(r = dp(8)))
        lateinit var send: android.widget.TextView
        send = btn(this, "Send") {
            val t = text.text.toString().trim()
            if (t.isEmpty()) return@btn
            send.isEnabled = false
            Api.run(this, { Api.call(this, "POST", "/api/admin-app/support/$id/reply", body = JSONObject().put("message", t)) }) { res, err ->
                send.isEnabled = true
                if (res == null) { toast(this, Api.friendly(err)); return@run }
                text.setText("")
                load(true)
            }
        }
        inputBar.addView(send, lp(WRAP, WRAP))
        s.body.addView(inputBar, lp())
        msgs.addView(loadingView(this))
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun load(forceScroll: Boolean) {
        Api.run(this, { Api.call(this, "GET", "/api/admin-app/support/$id") }) { res, err ->
            if (res == null) {
                if (lastCount < 0) { msgs.removeAllViews(); msgs.addView(emptyView(this, Api.friendly(err))) }
                return@run
            }
            val sess = res.optJSONObject("session") ?: JSONObject()
            canManage = res.optBoolean("can_manage")
            status = sess.str("status")
            s.titleView.text = sess.str("client_name").ifEmpty { "Live Support" }
            s.setSubtitle(listOf(if (status == "closed") "Closed" else "Open", sess.str("client_phone"), sess.str("client_email"))
                .filter { it.isNotEmpty() }.joinToString(" • "))
            inputBar.visibility = if (canManage) View.VISIBLE else View.GONE
            s.actions.visibility = if (canManage && status == "open") View.VISIBLE else View.GONE
            val arr = res.optJSONArray("messages") ?: JSONArray()
            if (arr.length() != lastCount || forceScroll) {
                lastCount = arr.length()
                render(arr, sess)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun render(arr: JSONArray, sess: JSONObject) {
        msgs.removeAllViews()
        if (sess.str("client_phone").isNotEmpty() && !sess.str("client_phone").contains("*")) {
            val acts = hbox(this).apply { setPadding(0, dp(4), 0, dp(10)) }
            acts.addView(btn(this, "Call", BTN_GHOST) { callPhone(this, sess.str("client_phone")) }, lp(0, WRAP, 1f).margins(r = dp(8)))
            acts.addView(btn(this, "WhatsApp", BTN_GHOST) { whatsApp(this, sess.str("client_phone")) }, lp(0, WRAP, 1f))
            msgs.addView(acts, lp())
        }
        if (arr.length() == 0) msgs.addView(emptyView(this, "No messages yet."))
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val mine = m.str("sender_type") == "admin"
            val wrap = LinearLayout(this).apply { gravity = if (mine) Gravity.END else Gravity.START }
            val bubble = vbox(this).apply {
                background = rounded(if (mine) C.GOLD else C.SURFACE2, dp(16).toFloat(), if (mine) C.GOLD else C.STROKE, dp(1))
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            val fg = if (mine) C.ON_GOLD else C.TEXT
            if (mine && m.str("sender_name").isNotEmpty()) bubble.addView(tv(this, m.str("sender_name"), 11f, fg, true))
            val maxW = (resources.displayMetrics.widthPixels * 0.78).toInt()
            if (m.str("message").isNotEmpty()) bubble.addView(tv(this, m.str("message"), 14.5f, fg).apply { maxWidth = maxW })
            val img = m.str("attachment_url")
            if (img.isNotEmpty()) bubble.addView(tv(this, "📷 View image", 14f, if (mine) C.ON_GOLD else C.BLUE, true).apply {
                setPadding(0, dp(4), 0, 0); setOnClickListener { openUrl(this@SupportChatActivity, img) }
            })
            bubble.addView(tv(this, agoServer(m.str("created_at")), 10.5f, if (mine) C.ON_GOLD else C.MUTED).apply { alpha = 0.8f })
            bubble.setOnLongClickListener { copyText(this, "Message", m.str("message")); true }
            wrap.addView(bubble, LinearLayout.LayoutParams(WRAP, WRAP))
            msgs.addView(wrap, lp().margins(b = dp(8)))
        }
    }

    private fun confirmClose() {
        AlertDialog.Builder(this)
            .setTitle("Close this chat?")
            .setMessage("It will be marked as attended by you. The client can still write again.")
            .setPositiveButton("Close chat") { _, _ ->
                Api.run(this, { Api.call(this, "POST", "/api/admin-app/support/$id/close") }) { res, err ->
                    if (res == null) toast(this, Api.friendly(err)) else { toast(this, "Chat closed."); finish() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
