package com.maptechgh.adminalerts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.Spinner
import org.json.JSONObject

class ComplaintActivity : Activity() {

    private lateinit var s: Screen
    private var id = 0

    private val statuses = arrayOf("reviewing", "resolved", "rejected", "open")
    private val statusLabels = arrayOf("Reviewing", "Resolved", "Rejected", "Open")
    private val quickReplies = listOf(
        "We're checking this order now and will update you shortly.",
        "The data has been resent to the number. Please check and confirm.",
        "Your order has been refunded to your wallet.",
        "Our records show this bundle was delivered successfully. Kindly check your data balance with *124#.",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        AlertPlayer.stop(this)
        id = intent.getIntExtra("id", 0)
        s = Screen(this, "Complaint #$id")
        s.action("⟳") { load() }
        load()
    }

    private fun load() {
        s.body.removeAllViews()
        s.body.addView(loadingView(this))
        Api.run(this, { Api.call(this, "GET", "/api/admin-app/complaints/$id") }) { res, err ->
            s.body.removeAllViews()
            val c = res?.optJSONObject("complaint")
            if (c == null) {
                s.body.addView(emptyView(this, Api.friendly(err)))
                s.body.addView(btn(this, "Try again", BTN_SECONDARY) { load() })
                return@run
            }
            render(c)
        }
    }

    private fun render(c: JSONObject) {
        val b = s.body
        val st = c.str("status")
        s.setSubtitle("${st.replaceFirstChar { it.uppercase() }} • ${agoServer(c.str("created_at"))}")

        // Customer
        val who = card(this, statusColor(st))
        val head = hbox(this)
        head.addView(badge(this, st, statusColor(st)))
        if (c.str("source") == "shop") head.addView(badge(this, "shop order", C.BLUE).apply { layoutParams = lp(WRAP, WRAP).margins(l = dp(6)) })
        who.addView(head, lp())
        who.addView(tv(this, c.str("customer_name").ifEmpty { "Customer" }, 18f, C.TEXT, true), lp().margins(t = dp(10)))
        if (c.str("user_email").isNotEmpty()) who.addView(tv(this, c.str("user_email"), 13f, C.MUTED))
        val phone = c.str("user_phone")
        if (phone.isNotEmpty()) {
            who.addView(tv(this, phone, 13f, C.MUTED))
            val acts = hbox(this).apply { setPadding(0, dp(10), 0, 0) }
            acts.addView(btn(this, "Call", BTN_SECONDARY) { callPhone(this, phone) }, lp(0, WRAP, 1f).margins(r = dp(8)))
            acts.addView(btn(this, "WhatsApp", BTN_SECONDARY) {
                whatsApp(this, phone, "Hello ${c.str("customer_name")}, about your complaint on order ${c.str("order_reference")}: ")
            }, lp(0, WRAP, 1f))
            who.addView(acts, lp())
        }
        b.addView(who)

        // Complaint text
        val msg = card(this)
        msg.addView(sectionTitle(this, "Complaint"))
        msg.addView(tv(this, c.str("message"), 15f, C.TEXT))
        if (c.str("admin_reply").isNotEmpty()) {
            msg.addView(divider(this))
            msg.addView(tv(this, "Last reply • ${agoServer(c.str("replied_at"))}", 12f, C.MUTED))
            msg.addView(tv(this, c.str("admin_reply"), 14f, C.GOLD))
        }
        b.addView(msg)

        // Order
        val ord = card(this)
        ord.addView(sectionTitle(this, "Order"))
        fun line(k: String, v: String) {
            if (v.isEmpty()) return
            val r = hbox(this).apply { setPadding(0, dp(3), 0, dp(3)) }
            r.addView(tv(this, k, 13f, C.MUTED), lp(dp(110), WRAP))
            r.addView(tv(this, v, 14f, C.TEXT), lp(0, WRAP, 1f))
            ord.addView(r)
        }
        line("Reference", c.str("order_reference"))
        line("Bundle", listOf(c.str("network"), c.str("bundle")).filter { it.isNotEmpty() }.joinToString(" "))
        line("Recipient", c.str("recipient"))
        if (!c.isNull("price")) line("Price", money(c.optDouble("price")))
        line("Order status", c.str("order_status"))
        line("Ordered", agoServer(c.str("order_created_at")))
        line("Provider says", c.str("provider_message"))
        val oActs = hbox(this).apply { setPadding(0, dp(10), 0, 0) }
        oActs.addView(btn(this, "Open order", BTN_SECONDARY) {
            startActivity(Intent(this, OrderActivity::class.java).putExtra("id", c.optInt("order_id")))
        }, lp(0, WRAP, 1f).margins(r = dp(8)))
        oActs.addView(btn(this, "Copy ref", BTN_SECONDARY) { copyText(this, "Reference", c.str("order_reference")) }, lp(0, WRAP, 1f))
        ord.addView(oActs, lp())
        b.addView(ord)

        // Reply
        val rep = card(this, C.GOLD)
        rep.addView(sectionTitle(this, "Reply to customer"))
        val replyBox = input(this, "Type your reply…", multiline = true)
        val qr = hbox(this)
        for (q in quickReplies) qr.addView(chip(this, q.take(28) + "…", false) { replyBox.setText(q); replyBox.setSelection(q.length) })
        rep.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(qr) }, lp().margins(b = dp(10)))
        rep.addView(replyBox)
        rep.addView(tv(this, "Set status to", 12.5f, C.MUTED))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, statusLabels)
        val cur = statuses.indexOf(st)
        spinner.setSelection(if (st == "open" || cur < 0) 0 else cur)
        rep.addView(spinner, lp().margins(b = dp(6)))
        val email = CheckBox(this).apply { text = "Also send by email"; setTextColor(C.TEXT); buttonTintList = android.content.res.ColorStateList.valueOf(C.GOLD) }
        val sms = CheckBox(this).apply { text = "Also send by SMS"; setTextColor(C.TEXT); buttonTintList = android.content.res.ColorStateList.valueOf(C.GOLD) }
        rep.addView(email); rep.addView(sms)
        val err = tv(this, "", 13f, C.RED).apply { visibility = View.GONE }
        rep.addView(err)
        lateinit var send: android.widget.TextView
        send = btn(this, "Send reply") {
            val t = replyBox.text.toString().trim()
            if (t.isEmpty()) { err.text = "Write a reply first."; err.visibility = View.VISIBLE; return@btn }
            err.visibility = View.GONE
            send.isEnabled = false; send.text = "Sending…"
            val body = JSONObject().put("reply", t).put("status", statuses[spinner.selectedItemPosition])
                .put("send_email", email.isChecked).put("send_sms", sms.isChecked)
            Api.run(this, { Api.call(this, "POST", "/api/admin-app/complaints/$id/reply", body = body) }) { res, e ->
                send.isEnabled = true; send.text = "Send reply"
                if (res == null) { err.text = Api.friendly(e); err.visibility = View.VISIBLE; return@run }
                val extra = ArrayList<String>()
                if (email.isChecked) extra.add(if (res.optBoolean("email_sent")) "email sent" else "email not sent")
                if (sms.isChecked) extra.add(if (res.optBoolean("sms_sent")) "SMS sent" else "SMS not sent")
                toast(this, "Reply saved" + if (extra.isEmpty()) "." else " (" + extra.joinToString(", ") + ").")
                load()
            }
        }
        rep.addView(send, lp().margins(t = dp(8)))
        b.addView(rep)
    }
}
