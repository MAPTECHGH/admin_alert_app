package com.maptechgh.adminalerts

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * One list screen for: complaints, support chats, data orders, AFA orders,
 * contact messages, shops, a shop's orders, shop withdrawals and signed-in phones.
 * Orders & AFA: date filter (all time / today / yesterday / last 7 days / custom),
 * status filter with counts, search, long-press to multi-select + bulk status.
 */
class ListActivity : Activity() {

    companion object {
        const val MODE_COMPLAINTS = "complaints"
        const val MODE_SUPPORT = "support"
        const val MODE_ORDERS = "orders"
        const val MODE_AFA = "afa"
        const val MODE_CONTACT = "contact"
        const val MODE_DEVICES = "devices"
        const val MODE_SHOPS = "shops"
        const val MODE_SHOP_ORDERS = "shop_orders"
        const val MODE_WITHDRAWALS = "withdrawals"
    }

    private lateinit var s: Screen
    private lateinit var mode: String
    private lateinit var chips: LinearLayout
    private lateinit var dateChips: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var actionBar: LinearLayout
    private lateinit var actionText: TextView
    private var search: EditText? = null
    private var filter = ""
    private var dateFilter = "all"
    private var dateFrom = ""
    private var dateTo = ""
    private var page = 1
    private var totalPages = 1
    private var loading = false
    private var counts: JSONObject = JSONObject()
    private var canManage = false
    private var statuses = JSONArray()
    private var providers = JSONArray()
    private var shopId = 0
    private val selected = LinkedHashSet<Int>()
    private val rowViews = HashMap<Int, LinearLayout>()

    private val hasDates get() = mode == MODE_ORDERS || mode == MODE_AFA || mode == MODE_SHOP_ORDERS
    private val selectable get() = (mode == MODE_ORDERS || mode == MODE_AFA) && canManage

    private val filters: List<Pair<String, String>> get() = when (mode) {
        MODE_COMPLAINTS -> listOf("open" to "Open", "reviewing" to "Reviewing", "resolved" to "Resolved", "rejected" to "Rejected", "" to "All")
        MODE_SUPPORT -> listOf("open" to "Open", "closed" to "Closed")
        // Same statuses, same order as Admin → Orders on the website
        MODE_ORDERS -> listOf("" to "All statuses", "accepted" to "Accepted", "placed" to "Placed", "processing" to "Processing",
            "delivered" to "Delivered", "failed" to "Failed", "refunded" to "Refunded", "cancelled" to "Cancelled",
            "needs_review" to "⚠ Needs Review")
        MODE_AFA -> listOf("" to "All statuses", "accepted" to "Accepted", "placed" to "Placed", "processing" to "Processing",
            "delivered" to "Delivered", "failed" to "Failed", "refunded" to "Refunded", "needs_review" to "⚠ Needs Review")
        MODE_CONTACT -> listOf("new" to "New", "read" to "Read", "replied" to "Replied", "" to "All")
        MODE_SHOPS -> listOf("pending" to "Pending", "active" to "Active", "suspended" to "Suspended", "rejected" to "Rejected", "all" to "All")
        MODE_SHOP_ORDERS -> listOf("all" to "All", "pending" to "Pending", "processing" to "Processing", "delivered" to "Delivered", "failed" to "Failed")
        MODE_WITHDRAWALS -> listOf("pending" to "Pending", "paid" to "Paid", "rejected" to "Rejected")
        else -> emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: MODE_COMPLAINTS
        shopId = intent.getIntExtra("shop_id", 0)
        val title = when (mode) {
            MODE_COMPLAINTS -> "Complaints"
            MODE_SUPPORT -> "Live Support"
            MODE_ORDERS -> "Orders"
            MODE_AFA -> "AFA orders"
            MODE_CONTACT -> "Contact messages"
            MODE_SHOPS -> "Shops"
            MODE_SHOP_ORDERS -> intent.getStringExtra("shop_name") ?: "Shop orders"
            MODE_WITHDRAWALS -> "Shop withdrawals"
            else -> "Signed-in phones"
        }
        s = Screen(this, title)
        s.action("⟳") { reload() }
        filter = intent.getStringExtra("filter") ?: filters.firstOrNull()?.first ?: ""
        dateFilter = intent.getStringExtra("date") ?: "all"

        if (mode in listOf(MODE_COMPLAINTS, MODE_ORDERS, MODE_SUPPORT, MODE_AFA, MODE_SHOPS)) {
            val q = input(this, when (mode) {
                MODE_ORDERS -> "Search reference, number, customer, provider ref…"
                MODE_AFA -> "Search name, reference, number…"
                MODE_SHOPS -> "Search shop or owner…"
                else -> "Search…"
            })
            q.imeOptions = EditorInfo.IME_ACTION_SEARCH
            q.isSingleLine = true
            q.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { reload(); true } else false }
            s.body.addView(q)
            search = q
        }
        dateChips = hbox(this)
        if (hasDates) s.body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(dateChips) }, lp().margins(b = dp(8)))
        chips = hbox(this)
        if (filters.isNotEmpty()) s.body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp().margins(b = dp(12)))
        list = vbox(this)
        s.body.addView(list, lp())

        // Bottom bar shown while selecting orders
        actionBar = hbox(this).apply {
            setBackgroundColor(C.SURFACE)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            visibility = View.GONE
        }
        actionText = tv(this, "", 14f, C.TEXT, true)
        actionBar.addView(actionText, lp(0, WRAP, 1f))
        actionBar.addView(btn(this, "All", BTN_GHOST) { selectAllVisible() }, lp(WRAP, WRAP).margins(r = dp(6)))
        actionBar.addView(btn(this, "✕", BTN_GHOST) { clearSelection() }, lp(WRAP, WRAP).margins(r = dp(6)))
        actionBar.addView(btn(this, "Set status") { bulkStatus() }, lp(WRAP, WRAP))
        s.root.addView(actionBar, lp())

        drawChips(); drawDateChips()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onBackPressed() {
        if (selected.isNotEmpty()) clearSelection() else super.onBackPressed()
    }

    // ── Filters ─────────────────────────────────────────────
    private fun drawChips() {
        chips.removeAllViews()
        for ((key, label) in filters) {
            val n = when {
                counts.length() == 0 -> -1
                key == "" || key == "all" -> counts.optInt("all", -1)
                else -> counts.optInt(key, 0)
            }
            val text = if (n >= 0 && hasCounts()) "$label  $n" else label
            chips.addView(chip(this, text, key == filter) { filter = key; drawChips(); reload() })
        }
    }

    private fun hasCounts() = mode in listOf(MODE_ORDERS, MODE_AFA, MODE_SHOPS, MODE_SHOP_ORDERS, MODE_WITHDRAWALS, MODE_COMPLAINTS)

    private fun drawDateChips() {
        if (!hasDates) return
        dateChips.removeAllViews()
        val opts = listOf("all" to "All time", "today" to "Today", "yesterday" to "Yesterday", "last7" to "Last 7 days")
        for ((k, label) in opts) dateChips.addView(chip(this, "📅 $label", dateFilter == k) { dateFilter = k; drawDateChips(); reload() })
        val customLabel = if (dateFilter == "custom") "📅 ${shortDate(dateFrom)} – ${shortDate(dateTo)}" else "📅 Custom…"
        dateChips.addView(chip(this, customLabel, dateFilter == "custom") { pickCustomRange() })
    }

    private fun shortDate(d: String) = if (d.length == 10) d.substring(8) + "/" + d.substring(5, 7) else "?"

    private fun pickCustomRange() {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            val from = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            DatePickerDialog(this, { _, y2, m2, d2 ->
                var to = String.format(Locale.US, "%04d-%02d-%02d", y2, m2 + 1, d2)
                var fr = from
                if (to < fr) { val t = to; to = fr; fr = t }
                dateFrom = fr; dateTo = to; dateFilter = "custom"
                drawDateChips(); reload()
            }, y, m, d).apply { setTitle("To date") }.show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply { setTitle("From date") }.show()
    }

    // ── Loading ─────────────────────────────────────────────
    private fun reload() {
        page = 1
        rowViews.clear()
        list.removeAllViews()
        list.addView(loadingView(this))
        load()
    }

    private fun load() {
        if (loading) return
        loading = true
        val q = HashMap<String, String>()
        q["page"] = page.toString()
        val term = search?.text?.toString()?.trim() ?: ""
        if (term.isNotEmpty()) q["q"] = term
        if (hasDates) {
            q["date_filter"] = dateFilter
            if (dateFilter == "custom") { q["date_from"] = dateFrom; q["date_to"] = dateTo }
        }
        val path = when (mode) {
            MODE_SUPPORT -> { q["tab"] = filter; "/api/admin-app/support" }
            MODE_DEVICES -> "/api/admin-app/devices"
            MODE_SHOPS -> { q["status"] = filter; "/api/admin-app/shops" }
            MODE_SHOP_ORDERS -> { q["filter"] = filter; "/api/admin-app/shops/$shopId/orders" }
            MODE_WITHDRAWALS -> { q["status"] = filter; "/api/admin-app/shop-withdrawals" }
            else -> { if (filter.isNotEmpty()) q["status"] = filter; "/api/admin-app/$mode" }
        }
        Api.run(this, { Api.call(this, "GET", path, q) }) { res, err ->
            loading = false
            if (page == 1) list.removeAllViews()
            removeLoadMore()
            if (res == null) {
                list.addView(emptyView(this, Api.friendly(err)))
                list.addView(btn(this, "Try again", BTN_SECONDARY) { reload() })
                return@run
            }
            totalPages = res.optJSONObject("pagination")?.optInt("total_pages", 1) ?: 1
            if (page == 1) {
                res.optJSONObject("counts")?.let { counts = it; drawChips() }
                canManage = res.optBoolean("can_manage", false)
                res.optJSONArray("statuses")?.let { statuses = it }
                res.optJSONArray("providers")?.let { if (it.length() > 0) providers = it }
                val total = res.optJSONObject("pagination")?.optInt("total", -1) ?: -1
                when (mode) {
                    MODE_COMPLAINTS -> counts.let { c -> s.setSubtitle("Open ${c.optInt("open")} • Reviewing ${c.optInt("reviewing")} • Resolved ${c.optInt("resolved")}") }
                    MODE_ORDERS, MODE_AFA -> s.setSubtitle(if (total >= 0) "$total found" + if (selectable) " • long-press to select" else "" else null)
                    MODE_SHOP_ORDERS -> res.optJSONObject("totals")?.let { t ->
                        s.setSubtitle("Revenue ${money(t.optDouble("revenue"))} • Commission ${money(t.optDouble("commission"))}")
                    }
                    MODE_SHOPS -> s.setSubtitle("${counts.optInt("withdrawals_pending")} withdrawal request(s) pending")
                }
            }
            val arr: JSONArray = when (mode) {
                MODE_COMPLAINTS -> res.optJSONArray("complaints")
                MODE_SUPPORT -> res.optJSONArray("sessions")
                MODE_ORDERS, MODE_AFA, MODE_SHOP_ORDERS -> res.optJSONArray("orders")
                MODE_CONTACT -> res.optJSONArray("messages")
                MODE_SHOPS -> res.optJSONArray("shops")
                MODE_WITHDRAWALS -> res.optJSONArray("withdrawals")
                else -> res.optJSONArray("devices")
            } ?: JSONArray()
            if (arr.length() == 0 && page == 1) {
                list.addView(emptyView(this, "Nothing here for this filter."))
                if (mode == MODE_SHOPS && filter == "pending") list.addView(btn(this, "Show withdrawals", BTN_GHOST) {
                    startActivity(Intent(this, ListActivity::class.java).putExtra("mode", MODE_WITHDRAWALS))
                })
                return@run
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.addView(when (mode) {
                    MODE_COMPLAINTS -> complaintRow(o)
                    MODE_SUPPORT -> supportRow(o)
                    MODE_ORDERS -> orderRow(o)
                    MODE_AFA -> afaRow(o)
                    MODE_CONTACT -> contactRow(o)
                    MODE_SHOPS -> shopRow(o)
                    MODE_SHOP_ORDERS -> shopOrderRow(o)
                    MODE_WITHDRAWALS -> withdrawalRow(o)
                    else -> deviceRow(o)
                })
            }
            if (page < totalPages) {
                list.addView(btn(this, "Load more (page ${page + 1} of $totalPages)", BTN_GHOST) { page++; load() }.apply { tag = "more" })
            }
        }
    }

    private fun removeLoadMore() {
        for (i in list.childCount - 1 downTo 0) if (list.getChildAt(i).tag == "more") list.removeViewAt(i)
    }

    // ── Selection (orders & AFA) ────────────────────────────
    private fun toggle(id: Int) {
        if (!selected.remove(id)) selected.add(id)
        paintRow(id)
        updateActionBar()
    }

    private fun paintRow(id: Int) {
        val v = rowViews[id] ?: return
        val sel = id in selected
        v.background = ripple(rounded(if (sel) C.SURFACE2 else C.SURFACE, dp(16).toFloat(), if (sel) C.GOLD else C.STROKE, dp(if (sel) 2 else 1)))
        v.findViewWithTag<TextView>("check")?.visibility = if (sel) View.VISIBLE else View.GONE
    }

    private fun updateActionBar() {
        actionBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        actionText.text = "${selected.size} selected"
    }

    private fun selectAllVisible() {
        selected.addAll(rowViews.keys)
        rowViews.keys.forEach { paintRow(it) }
        updateActionBar()
    }

    private fun clearSelection() {
        val ids = selected.toList()
        selected.clear()
        ids.forEach { paintRow(it) }
        updateActionBar()
    }

    private fun bulkStatus() {
        if (selected.isEmpty()) return
        val sts = ArrayList<String>()
        for (i in 0 until statuses.length()) sts.add(statuses.optString(i))
        val isOrders = mode == MODE_ORDERS
        Detail.statusDialog(this, "Set status for ${selected.size} ${if (isOrders) "order(s)" else "AFA order(s)"}", sts, null,
            if (isOrders) providers else null, isOrders) { st, pid, key ->
            val body = Detail.statusBody(st, pid, key).put("ids", Detail.ids(selected))
            val path = if (isOrders) "/api/admin-app/orders/bulk-status" else "/api/admin-app/afa/bulk-status"
            Api.run(this, { Api.call(this, "POST", path, body = body) }) { res, err ->
                toast(this, res?.optString("message") ?: Api.friendly(err))
                if (res != null) { selected.clear(); updateActionBar(); reload() }
            }
        }
    }

    // ── Rows ────────────────────────────────────────────────
    private fun rowCard(): LinearLayout = card(this).apply {
        background = ripple(rounded(C.SURFACE, dp(16).toFloat(), C.STROKE, dp(1)))
    }

    private fun topLine(left: View, right: String): LinearLayout {
        val h = hbox(this)
        h.addView(left)
        h.addView(View(this), lp(0, 1, 1f))
        h.addView(tv(this, right, 12f, C.MUTED))
        return h
    }

    private fun badges(vararg b: Pair<String, Int>?): LinearLayout {
        val h = hbox(this)
        b.filterNotNull().forEachIndexed { i, (t, c) ->
            h.addView(badge(this, t, c).apply { if (i > 0) layoutParams = lp(WRAP, WRAP).margins(l = dp(6)) })
        }
        return h
    }

    private fun selectableRow(id: Int, c: LinearLayout, open: () -> Unit) {
        rowViews[id] = c
        val check = tv(this, "✓ selected", 11.5f, C.GOLD, true).apply { tag = "check"; visibility = View.GONE }
        c.addView(check, 0)
        c.setOnClickListener { if (selected.isNotEmpty()) toggle(id) else open() }
        if (selectable) c.setOnLongClickListener { toggle(id); true }
        if (id in selected) paintRow(id)
    }

    private fun orderRow(o: JSONObject): View {
        val c = rowCard()
        val st = o.str("status")
        c.addView(topLine(badges(st to statusColor(st),
            if (o.optBoolean("needs_review")) "⚠ review" to C.RED else null,
            if (o.optBoolean("reprocessed")) "re-sent" to C.BLUE else null), agoServer(o.str("created_at"))), lp())
        c.addView(tv(this, "${o.str("network")} ${o.str("bundle")} → ${o.str("recipient")}", 15.5f, C.TEXT, true), lp().margins(t = dp(8)))
        c.addView(tv(this, "#${o.optInt("id")} • ${o.str("user_name").ifEmpty { "Guest" }} • ${money(o.optDouble("price", 0.0))} • ${o.str("source")}", 13f, C.MUTED))
        c.addView(tv(this, listOf(o.str("reference"), o.str("provider")).filter { it.isNotEmpty() }.joinToString(" • "), 12f, C.MUTED))
        if (o.str("provider_message").isNotEmpty()) c.addView(tv(this, o.str("provider_message"), 12.5f, C.ORANGE).apply { maxLines = 2 })
        selectableRow(o.optInt("id"), c) { startActivity(Intent(this, OrderActivity::class.java).putExtra("id", o.optInt("id"))) }
        return c
    }

    private fun afaRow(o: JSONObject): View {
        val c = rowCard()
        val st = o.str("status")
        c.addView(topLine(badges(st to statusColor(st), if (o.optBoolean("needs_review")) "⚠ review" to C.RED else null), agoServer(o.str("created_at"))), lp())
        c.addView(tv(this, o.str("full_name"), 15.5f, C.TEXT, true), lp().margins(t = dp(8)))
        c.addView(tv(this, "#${o.optInt("id")} • ${o.str("phone")} • ${o.str("ghana_card")}", 13f, C.MUTED))
        c.addView(tv(this, "${o.str("user_name").ifEmpty { "Guest" }} • ${money(o.optDouble("price", 0.0))} • ${o.str("source")} • ${o.str("reference")}", 12f, C.MUTED))
        if (o.str("provider_message").isNotEmpty() && st != "delivered") c.addView(tv(this, o.str("provider_message"), 12.5f, C.ORANGE).apply { maxLines = 2 })
        selectableRow(o.optInt("id"), c) { startActivity(Intent(this, AfaActivity::class.java).putExtra("id", o.optInt("id"))) }
        return c
    }

    private fun complaintRow(o: JSONObject): View {
        val c = rowCard()
        val st = o.str("status")
        c.addView(topLine(badges(st to statusColor(st), if (o.str("source") == "shop") "shop" to C.BLUE else null), agoServer(o.str("created_at"))), lp())
        c.addView(tv(this, "#${o.optInt("id")} • ${o.str("customer_name").ifEmpty { "Customer" }}", 15.5f, C.TEXT, true), lp().margins(t = dp(8)))
        c.addView(tv(this, listOf(o.str("network"), o.str("bundle"), o.str("recipient")).filter { it.isNotEmpty() }.joinToString(" • "), 13f, C.MUTED))
        c.addView(tv(this, o.str("message"), 13.5f, C.TEXT).apply { maxLines = 2 }, lp().margins(t = dp(6)))
        c.setOnClickListener { startActivity(Intent(this, ComplaintActivity::class.java).putExtra("id", o.optInt("id"))) }
        return c
    }

    private fun supportRow(o: JSONObject): View {
        val c = rowCard()
        val unread = o.optBoolean("unread_by_admin")
        val name = hbox(this)
        if (unread) name.addView(View(this).apply { background = rounded(C.GOLD, dp(5).toFloat()) }, lp(dp(10), dp(10)).margins(r = dp(8)))
        name.addView(tv(this, o.str("client_name"), 15.5f, C.TEXT, true))
        c.addView(topLine(name, agoServer(o.str("last_message_at"))), lp())
        val who = if (o.str("last_message_by") == "admin") "You: " else ""
        c.addView(tv(this, who + o.str("last_message").ifEmpty { "📷 Image" }, 13.5f, if (unread) C.TEXT else C.MUTED).apply { maxLines = 2 },
            lp().margins(t = dp(6)))
        if (o.str("attended_by_name").isNotEmpty()) c.addView(tv(this, "Closed by ${o.str("attended_by_name")}", 12f, C.MUTED))
        c.setOnClickListener { startActivity(Intent(this, SupportChatActivity::class.java).putExtra("id", o.optInt("id"))) }
        return c
    }

    private fun contactRow(o: JSONObject): View {
        val c = rowCard()
        val st = o.str("status")
        c.addView(topLine(badge(this, st, statusColor(st)), agoServer(o.str("created_at"))), lp())
        c.addView(tv(this, o.str("name"), 15.5f, C.TEXT, true), lp().margins(t = dp(8)))
        if (o.str("subject").isNotEmpty()) c.addView(tv(this, o.str("subject"), 13.5f, C.TEXT))
        c.addView(tv(this, o.str("message"), 13f, C.MUTED).apply { maxLines = 3 })
        c.setOnClickListener { contactDialog(o) }
        return c
    }

    private fun contactDialog(o: JSONObject) {
        val id = o.optInt("id")
        if (o.str("status") == "new") Api.run(this, { Api.call(this, "POST", "/api/admin-app/contact/$id/read") }) { _, _ -> }
        AlertDialog.Builder(this)
            .setTitle(o.str("subject").ifEmpty { "Message from ${o.str("name")}" })
            .setMessage("${o.str("name")} <${o.str("email")}>\n\n${o.str("message")}" +
                if (o.str("admin_reply").isNotEmpty()) "\n\n— Your reply —\n${o.str("admin_reply")}" else "")
            .setPositiveButton("Reply by email") { _, _ ->
                openUrl(this, "mailto:${o.str("email")}?subject=" + android.net.Uri.encode("Re: " + o.str("subject").ifEmpty { "Your message" }))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Shops ───────────────────────────────────────────────
    private fun shopRow(o: JSONObject): View {
        val c = rowCard()
        val st = o.str("status")
        val shopColor = when (st) { "active" -> C.GREEN; "pending" -> C.ORANGE; "suspended", "rejected" -> C.RED; else -> C.BLUE }
        c.addView(topLine(badge(this, st, shopColor), agoServer(o.str("applied_at").ifEmpty { o.str("created_at") })), lp())
        c.addView(tv(this, o.str("name"), 15.5f, C.TEXT, true), lp().margins(t = dp(8)))
        c.addView(tv(this, "${o.str("owner_name")} • ${o.str("owner_email")} • /shop/${o.str("slug")}", 13f, C.MUTED))
        c.addView(tv(this, "${o.optInt("order_count")} orders (${o.optInt("orders_today")} today) • balance ${money(o.optDouble("commission_balance"))} • earned ${money(o.optDouble("commission_earned"))}", 12.5f, C.MUTED))
        if (st == "rejected" && o.str("rejected_reason").isNotEmpty()) c.addView(tv(this, "Reason: " + o.str("rejected_reason"), 12.5f, C.ORANGE))
        c.setOnClickListener { shopMenu(o) }
        return c
    }

    private fun shopMenu(o: JSONObject) {
        val id = o.optInt("id")
        val st = o.str("status")
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts.add("View this shop's orders" to {
            startActivity(Intent(this, ListActivity::class.java).putExtra("mode", MODE_SHOP_ORDERS)
                .putExtra("shop_id", id).putExtra("shop_name", o.str("name")))
        })
        if (st == "pending" || st == "rejected") opts.add("✅ Approve shop" to { shopAction(id, "approve", null) })
        if (st == "pending") opts.add("❌ Reject shop…" to { askText("Reject ${o.str("name")}", "Reason (shown to the owner)") { r -> shopAction(id, "reject", r) } })
        if (st == "active") opts.add("⏸ Suspend shop" to { confirm("Suspend ${o.str("name")}?", "The storefront stops taking orders.") { shopAction(id, "suspend", null) } })
        if (st == "suspended") opts.add("▶ Reinstate shop" to { shopAction(id, "unsuspend", null) })
        opts.add("Open storefront" to { openUrl(this, Prefs.serverUrl(this).trimEnd('/') + "/shop/" + o.str("slug")) })
        opts.add("Manage on website" to { openUrl(this, Prefs.serverUrl(this).trimEnd('/') + "/admin/shops/$id/edit") })
        AlertDialog.Builder(this).setTitle(o.str("name"))
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
            .setNegativeButton("Close", null).show()
    }

    private fun shopAction(id: Int, action: String, reason: String?) {
        val body = JSONObject().put("reason", reason ?: "")
        Api.run(this, { Api.call(this, "POST", "/api/admin-app/shops/$id/$action", body = body) }) { res, err ->
            toast(this, res?.optString("message") ?: Api.friendly(err)); reload()
        }
    }

    private fun shopOrderRow(o: JSONObject): View {
        val c = rowCard()
        val st = o.str("status")
        val pay = o.str("payment_status")
        c.addView(topLine(badges(st to statusColor(st), "pay: $pay" to (if (pay == "paid") C.GREEN else if (pay == "failed") C.RED else C.ORANGE),
            o.str("type") to C.BLUE), agoServer(o.str("created_at"))), lp())
        val what = if (o.str("type") == "afa") "AFA registration" else "${o.str("network_name")} ${if (!o.isNull("capacity_gb")) o.optDouble("capacity_gb").toString().removeSuffix(".0") + "GB" else ""} → ${o.str("recipient")}"
        c.addView(tv(this, what, 15f, C.TEXT, true), lp().margins(t = dp(8)))
        c.addView(tv(this, "${o.str("customer_name")} • ${o.str("customer_phone")}", 13f, C.MUTED))
        c.addView(tv(this, "Paid ${money(o.optDouble("customer_price", 0.0))} • cost ${money(o.optDouble("owner_cost", 0.0))} • commission ${money(o.optDouble("commission", 0.0))}", 12.5f, C.MUTED))
        val oid = o.optInt("order_id", 0)
        val aid = o.optInt("afa_order_id", 0)
        c.setOnClickListener {
            when {
                oid > 0 -> startActivity(Intent(this, OrderActivity::class.java).putExtra("id", oid))
                aid > 0 -> startActivity(Intent(this, AfaActivity::class.java).putExtra("id", aid))
                else -> toast(this, "No linked order yet (payment ${o.str("payment_status")}).")
            }
        }
        return c
    }

    private fun withdrawalRow(o: JSONObject): View {
        val c = rowCard()
        val st = o.str("status")
        val col = when (st) { "paid" -> C.GREEN; "rejected" -> C.RED; else -> C.ORANGE }
        c.addView(topLine(badge(this, st, col), agoServer(o.str("requested_at"))), lp())
        c.addView(tv(this, "${money(o.optDouble("amount", 0.0))} — ${o.str("shop_name")}", 15.5f, C.TEXT, true), lp().margins(t = dp(8)))
        val dest = if (o.str("type") == "wallet_transfer") "To wallet" else "MoMo ${o.str("momo_network")} ${o.str("momo_number")} • ${o.str("momo_name")}"
        c.addView(tv(this, dest, 13f, C.MUTED))
        val net = if (!o.isNull("net_amount")) " • net ${money(o.optDouble("net_amount"))}" else ""
        c.addView(tv(this, "Owner ${o.str("owner_name")} • shop balance ${money(o.optDouble("commission_balance", 0.0))}$net", 12.5f, C.MUTED))
        if (o.str("payment_note").isNotEmpty()) c.addView(tv(this, "Note: " + o.str("payment_note"), 12.5f, C.GOLD))
        if (st == "pending") {
            val acts = hbox(this).apply { setPadding(0, dp(10), 0, 0) }
            val id = o.optInt("id")
            acts.addView(btn(this, "Mark paid", BTN_PRIMARY) {
                askText("Mark ${money(o.optDouble("amount", 0.0))} as paid", "Payment note (e.g. MoMo transaction ID)") { n -> withdrawalAction(id, "approve", n) }
            }, lp(0, WRAP, 1f).margins(r = dp(8)))
            acts.addView(btn(this, "Reject", BTN_DANGER) {
                askText("Reject withdrawal", "Reason (the amount goes back to the shop balance)") { n -> withdrawalAction(id, "reject", n) }
            }, lp(0, WRAP, 1f))
            c.addView(acts, lp())
            if (o.str("momo_number").isNotEmpty()) c.setOnClickListener { copyText(this, "MoMo number", o.str("momo_number")) }
        }
        return c
    }

    private fun withdrawalAction(id: Int, action: String, note: String) {
        Api.run(this, { Api.call(this, "POST", "/api/admin-app/shop-withdrawals/$id/$action", body = JSONObject().put("note", note)) }) { res, err ->
            toast(this, res?.optString("message") ?: Api.friendly(err)); reload()
        }
    }

    private fun deviceRow(o: JSONObject): View {
        val c = card(this)
        val me = o.optBoolean("is_this_device")
        c.addView(topLine(tv(this, o.str("device_name").ifEmpty { "Phone" } + if (me) "  (this phone)" else "", 15.5f, C.TEXT, true),
            agoServer(o.str("last_seen_at"))), lp())
        c.addView(tv(this, "${o.str("admin_name")} • app ${o.str("app_version").ifEmpty { "?" }} • IP ${o.str("last_ip")}", 13f, C.MUTED))
        c.addView(tv(this, "Signed in ${agoServer(o.str("created_at"))}", 12f, C.MUTED))
        if (!me) {
            c.addView(btn(this, "Sign this phone out", BTN_DANGER) {
                Api.run(this, { Api.call(this, "POST", "/api/admin-app/devices/${o.optInt("id")}/revoke") }) { res, err ->
                    if (res == null) toast(this, Api.friendly(err)) else { toast(this, "Signed out."); reload() }
                }
            }, lp().margins(t = dp(10)))
        }
        return c
    }

    // ── Small dialogs ───────────────────────────────────────
    private fun askText(title: String, hint: String, onOk: (String) -> Unit) {
        val e = input(this, hint)
        val box = vbox(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(e.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirm(title: String, msg: String, onOk: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> onOk() }
            .setNegativeButton("Cancel", null).show()
    }
}
