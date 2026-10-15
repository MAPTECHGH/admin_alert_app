package com.maptechgh.adminalerts

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Home screen. Its structure follows the selected theme's layout
 * (classic / banking banner / priority queue / ops console / editorial),
 * with a quick theme switcher (up to 5 favourites) near the top.
 */
class MainActivity : Activity() {

    private class Tile(val key: String, val label: String, val value: Int, val color: Int, val icon: Int, val open: () -> Unit)

    private lateinit var s: Screen
    private lateinit var t: AppTheme
    private lateinit var content: LinearLayout
    private lateinit var liveBox: LinearLayout
    private lateinit var warnBox: LinearLayout
    private lateinit var tilesBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private var headerTitle: TextView? = null
    private var headerSub: TextView? = null
    private var appliedTheme = 0
    private var receiverOn = false
    private val drawer by lazy { Drawer(this) }

    private val updates = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!Prefs.loggedIn(this@MainActivity)) { Api.sessionEnded(this@MainActivity, "Your session ended."); return }
            refreshLocal()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        t = Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        if (!Prefs.loggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        appliedTheme = t.id
        s = Screen(this, "", back = false)
        s.root.removeViewAt(0)                 // own header instead of the standard bar
        s.body.setPadding(0, 0, 0, dp(24))

        if (t.layout == HomeLayout.BANNER) {
            val banner = vbox(this).apply {
                background = Themes.heroDrawable(0f).apply {
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat())
                }
                setPadding(0, 0, 0, dp(34))
            }
            banner.addView(header(true), lp())
            banner.addView(themeSwitcher(true), lp())
            liveBox = vbox(this)
            banner.addView(liveBox, lp())
            s.body.addView(banner, lp())
        } else {
            s.body.addView(header(false), lp())
            s.body.addView(themeSwitcher(false), lp())
        }

        content = vbox(this).apply { setPadding(dp(16), dp(4), dp(16), 0) }
        s.body.addView(content, lp())
        if (t.layout != HomeLayout.BANNER) { liveBox = vbox(this); content.addView(liveBox, lp()) }
        if (t.layout == HomeLayout.BANNER) content.addView(quickActions(), lp().margins(t = -dp(22)))
        warnBox = vbox(this); content.addView(warnBox, lp())
        content.addView(sectionTitle(this, when (t.layout) {
            HomeLayout.BANNER -> "Overview"; HomeLayout.COMMAND -> "Counters"; HomeLayout.EDITORIAL -> "Today at a glance"
            HomeLayout.QUEUE -> "Counters"; else -> "Right now"
        }))
        tilesBox = vbox(this); content.addView(tilesBox, lp())
        val hHead = hbox(this)
        hHead.addView(sectionTitle(this, when (t.layout) {
            HomeLayout.QUEUE -> "Priority queue"; HomeLayout.COMMAND -> "Event log"; HomeLayout.EDITORIAL -> "Latest"; else -> "Recent alerts"
        }), lp(0, WRAP, 1f))
        hHead.addView(hbox(this).apply {
            background = ripple(rounded(alpha(C.GOLD, 28), dp(20).toFloat(), alpha(C.GOLD, 90), dp(1)))
            setPadding(dp(12), dp(6), dp(10), dp(6))
            addView(tv(this@MainActivity, "View all", 12.5f, C.GOLD, true))
            addView(icon(this@MainActivity, R.drawable.ic_chevron, 14, C.GOLD))
            setOnClickListener { startActivity(Intent(this@MainActivity, PriorityActivity::class.java)) }
        })
        content.addView(hHead, lp())
        historyBox = vbox(this); content.addView(historyBox, lp())
        content.addView(creditsCard(this))

        if (t.layout == HomeLayout.BANNER) s.root.addView(bottomNav(), lp())

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        AlertService.start(this)
        refreshLocal()
        refreshMe()
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.loggedIn(this)) return
        if (Prefs.themeId(this) != appliedTheme) { recreate(); return }
        AlertPlayer.stop(this)
        if (!receiverOn) {
            val f = IntentFilter(AlertService.ACTION_UPDATED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(updates, f)
            receiverOn = true
        }
        if (AlertService.shouldRun(this) && !AlertService.running) AlertService.start(this)
        refreshLocal()
        refreshSummary()
    }

    override fun onPause() {
        super.onPause()
        if (receiverOn) { try { unregisterReceiver(updates) } catch (e: Exception) { }; receiverOn = false }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshLocal()
    }

    // ── Header & theme switcher ─────────────────────────────
    private fun header(onHero: Boolean): LinearLayout {
        val fg = if (onHero) t.heroText else C.TEXT
        val admin = Prefs.admin(this)
        val row = hbox(this).apply { setPadding(dp(12), dp(14), dp(12), dp(8)) }
        row.addView(roundIcon(R.drawable.ic_hamburger, fg, onHero) { openDrawer() })
        row.addView(logoView(this, 44).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10) })
        val tt = vbox(this).apply { setPadding(dp(12), 0, dp(6), 0) }
        headerTitle = tv(this, admin.optString("site_name", "MAPTECH GLOBAL").uppercase(Locale.US), 17f, fg, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; letterSpacing = 0.02f
        }
        headerSub = tv(this, subtitle(admin), 12.5f, alpha(fg, 190)).apply { maxLines = 1 }
        tt.addView(headerTitle); tt.addView(headerSub)
        row.addView(tt, lp(0, WRAP, 1f))
        row.addView(roundIcon(R.drawable.ic_gear, fg, onHero) { startActivity(Intent(this, AlertSettingsActivity::class.java)) })
        return row
    }

    private fun subtitle(admin: JSONObject): String {
        val name = admin.optString("name")
        return if (t.layout == HomeLayout.BANNER) "${greeting()}, $name" else "$name • ${admin.optString("role_name", "Admin")}"
    }

    private fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (h < 12) "Good morning" else if (h < 17) "Good afternoon" else "Good evening"
    }

    private fun roundIcon(res: Int, color: Int, onHero: Boolean, onClick: (View) -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = ripple(rounded(alpha(color, if (onHero) 40 else 22), dp(13).toFloat()))
            addView(icon(this@MainActivity, res, 19, color), FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener(onClick)
        }

    /** Up to 5 favourite themes as round swatches; tap = switch instantly, "+" = all themes. */
    private fun themeSwitcher(onHero: Boolean): View {
        val fg = if (onHero) t.heroText else C.MUTED
        val row = hbox(this).apply { setPadding(dp(16), dp(2), dp(16), dp(10)) }
        val label = vbox(this)
        label.addView(tv(this, "THEME", 10f, alpha(fg, 200), true).apply { letterSpacing = 0.12f })
        label.addView(tv(this, t.name, 12.5f, if (onHero) t.heroText else C.TEXT, true).apply { maxLines = 1 })
        row.addView(label, lp(0, WRAP, 1f))
        for (q in Themes.quick(this)) {
            val sel = q.id == t.id
            val sw = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (q.bgGradColors != null) { colors = q.bgGradColors; orientation = GradientDrawable.Orientation.TL_BR } else setColor(q.bg)
                    setStroke(dp(if (sel) 3 else 1), if (sel) (if (onHero) t.heroText else C.GOLD) else alpha(fg, 120))
                }
                val inner = View(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        colors = if (q.hero.size > 1) intArrayOf(q.accent, q.hero.last()) else intArrayOf(q.accent, q.accent)
                    }
                }
                addView(inner, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
                setOnClickListener {
                    if (!sel) { Prefs.setThemeId(this@MainActivity, q.id); recreate() }
                    else toast(this@MainActivity, "${q.name} is on")
                }
                setOnLongClickListener { toast(this@MainActivity, q.name); true }
            }
            row.addView(sw, lp(dp(34), dp(34)).margins(l = dp(8)))
        }
        val more = FrameLayout(this).apply {
            background = ripple(rounded(alpha(fg, 30), dp(17).toFloat(), alpha(fg, 110), dp(1)))
            addView(icon(this@MainActivity, R.drawable.ic_palette, 17, if (onHero) t.heroText else C.TEXT), FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
            setOnClickListener { startActivity(Intent(this@MainActivity, ThemeActivity::class.java)) }
        }
        row.addView(more, lp(dp(34), dp(34)).margins(l = dp(8)))
        return row
    }

    // ── Data ────────────────────────────────────────────────
    private fun tiles(sum: JSONObject): List<Tile> {
        val base = Prefs.serverUrl(this).trimEnd('/')
        val all = listOf(
            Tile("complaints_open", "Open complaints", 0, C.RED, R.drawable.ic_alert) { openList(ListActivity.MODE_COMPLAINTS) },
            Tile("orders_needs_review", "Needs review", 0, C.RED, R.drawable.ic_shield) { openList(ListActivity.MODE_ORDERS, "needs_review") },
            Tile("orders_processing", "Processing", 0, C.ORANGE, R.drawable.ic_clock) { openList(ListActivity.MODE_ORDERS, "processing") },
            Tile("orders_failed_today", "Failed today", 0, C.RED, R.drawable.ic_x) { openListDated(ListActivity.MODE_ORDERS, "failed", "today") },
            Tile("afa_processing", "AFA processing", 0, C.ORANGE, R.drawable.ic_id) { openList(ListActivity.MODE_AFA, "processing") },
            Tile("afa_failed_today", "AFA failed today", 0, C.RED, R.drawable.ic_id) { openListDated(ListActivity.MODE_AFA, "failed", "today") },
            Tile("unclaimed_payments", "Unclaimed MoMo", 0, C.YELLOW, R.drawable.ic_wallet) { openUrl(this, "$base/admin/send-claim") },
            Tile("support_unread", "Unread chats", 0, C.BLUE, R.drawable.ic_chat) { openList(ListActivity.MODE_SUPPORT) },
            Tile("shops_pending", "Shops waiting", 0, C.BLUE, R.drawable.ic_store) { openList(ListActivity.MODE_SHOPS, "pending") },
            Tile("withdrawals_pending", "Withdrawals", 0, C.YELLOW, R.drawable.ic_wallet) { openList(ListActivity.MODE_WITHDRAWALS, "pending") },
            Tile("contact_new", "Contact messages", 0, C.BLUE, R.drawable.ic_chat) { openList(ListActivity.MODE_CONTACT) },
            Tile("orders_today", "Orders today", 0, C.GREEN, R.drawable.ic_box) { openListDated(ListActivity.MODE_ORDERS, "", "today") },
        )
        return all.filter { sum.has(it.key) }.map { Tile(it.key, it.label, sum.optInt(it.key), it.color, it.icon, it.open) }
    }

    private fun attentionCount(sum: JSONObject): Int = listOf(
        "complaints_open", "orders_needs_review", "orders_failed_today", "support_unread", "unclaimed_payments",
        "shops_pending", "withdrawals_pending", "afa_failed_today", "contact_new"
    ).sumOf { sum.optInt(it, 0) }

    private data class Live(val color: Int, val title: String, val sub: String, val on: Boolean, val word: String)

    private fun live(): Live {
        val on = Prefs.liveEnabled(this)
        val err = Prefs.lastError(this)
        val last = Prefs.lastCheck(this)
        return when {
            !on -> Live(C.MUTED, "Live alerts are OFF", "You won't get rings until you turn this on", false, "PAUSED")
            err.isNotEmpty() -> Live(C.ORANGE, "Live alerts — reconnecting", err, true, "RECONNECTING")
            last > 0 -> Live(C.GREEN, "Live alerts are ON",
                "Checks every ${Prefs.pollSeconds(this)}s • last ${ago(last)}" + if (Prefs.inQuietHours(this)) " • quiet hours" else "", true, "MONITORING")
            else -> Live(C.BLUE, "Live alerts — starting", "Connecting to ${Prefs.serverUrl(this)}", true, "STARTING")
        }
    }

    private fun refreshLocal() {
        val sum = Prefs.summary(this)
        renderLive(sum)
        buildWarnings()
        renderTiles(tiles(sum))
        renderHistory()
    }

    // ── Live status per layout ──────────────────────────────
    private fun liveSwitch(tint: Int? = null): android.widget.Switch = styledSwitch(this, Prefs.liveEnabled(this)).apply {
        setOnCheckedChangeListener { _, on ->
            Prefs.setLiveEnabled(this@MainActivity, on)
            if (on) AlertService.start(this@MainActivity) else { AlertService.stop(this@MainActivity); AlertPlayer.stop(this@MainActivity) }
            post { refreshLocal() }
        }
        if (tint != null) {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(tint, alpha(tint, 200)))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(alpha(tint, 120), alpha(tint, 60)))
        }
    }

    private fun dot(color: Int, size: Int = 10): View = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(3), alpha(color, 70)) }
        layoutParams = lp(dp(size + 4), dp(size + 4))
    }

    private fun checkNow() { AlertService.pollNow(this); refreshSummary(); toast(this, "Checking…") }

    private fun renderLive(sum: JSONObject) {
        liveBox.removeAllViews()
        val l = live()
        val mono = Typeface.MONOSPACE
        when (t.layout) {
            HomeLayout.CLASSIC -> {
                val fg = t.heroText
                val c = vbox(this).apply {
                    background = Themes.heroDrawable(dp(C.R).toFloat()).apply { setStroke(dp(1), alpha(Color.WHITE, if (C.LIGHT) 0 else 18)) }
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                }
                val row = hbox(this)
                row.addView(dot(if (l.on && l.color == C.GREEN) Color.parseColor("#22C55E") else l.color))
                val tx = vbox(this).apply { setPadding(dp(12), 0, dp(8), 0) }
                tx.addView(tv(this, l.title, 16.5f, fg, true))
                tx.addView(tv(this, l.sub, 12.5f, alpha(fg, 200)).apply { maxLines = 2 })
                row.addView(tx, lp(0, WRAP, 1f))
                row.addView(liveSwitch(if (t.id == 10) Color.parseColor("#0B1630") else null))
                c.addView(row, lp())
                val btns = hbox(this).apply { setPadding(0, dp(14), 0, 0) }
                btns.addView(heroBtn("Check now", R.drawable.ic_refresh, fg) { checkNow() }, lp(0, WRAP, 1f).margins(r = dp(8)))
                btns.addView(heroBtn("Alert sounds", R.drawable.ic_bell, fg) { startActivity(Intent(this, AlertSettingsActivity::class.java)) }, lp(0, WRAP, 1f))
                c.addView(btns, lp())
                liveBox.addView(c, lp().margins(b = dp(4)))
            }
            HomeLayout.BANNER -> {
                val fg = t.heroText
                val b = vbox(this).apply { setPadding(dp(20), dp(6), dp(20), 0) }
                b.addView(tv(this, "Needs attention now", 13f, alpha(fg, 210)))
                val n = attentionCount(sum)
                val big = hbox(this).apply { gravity = Gravity.BOTTOM }
                big.addView(tv(this, n.toString(), 46f, fg, true).apply { letterSpacing = -0.03f; includeFontPadding = false })
                big.addView(tv(this, if (n == 1) "  item" else "  items", 18f, alpha(fg, 210), true).apply { setPadding(0, 0, 0, dp(6)) })
                b.addView(big, lp())
                val row = hbox(this).apply { setPadding(0, dp(10), 0, 0) }
                row.addView(dot(if (l.color == C.GREEN) Color.parseColor("#4ADE80") else l.color, 8))
                row.addView(tv(this, "  " + (if (l.on) "Live • " else "") + l.sub, 12.5f, alpha(fg, 225)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
                row.addView(liveSwitch(fg), lp(WRAP, WRAP).margins(r = dp(6)))
                row.addView(tv(this, "Check now", 12.5f, fg, true).apply {
                    background = ripple(rounded(alpha(fg, 45), dp(16).toFloat())); setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { checkNow() }
                })
                b.addView(row, lp())
                liveBox.addView(b, lp())
            }
            HomeLayout.QUEUE -> {
                val c = hbox(this).apply {
                    background = rounded(C.SURFACE, dp(14).toFloat(), C.STROKE, dp(1)); setPadding(dp(14), dp(10), dp(10), dp(10))
                }
                c.addView(dot(l.color, 8))
                c.addView(tv(this, if (l.on) "  LIVE" else "  OFF", 13f, l.color, true).apply { letterSpacing = 0.12f })
                c.addView(tv(this, "  " + l.sub, 12f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
                c.addView(FrameLayout(this).apply {
                    background = ripple(rounded(C.SURFACE2, dp(10).toFloat()))
                    addView(icon(this@MainActivity, R.drawable.ic_refresh, 16, C.TEXT), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                    setOnClickListener { checkNow() }
                }, lp(dp(34), dp(34)).margins(r = dp(6)))
                c.addView(liveSwitch())
                liveBox.addView(c, lp().margins(b = dp(2)))
            }
            HomeLayout.COMMAND -> {
                val c = hbox(this).apply {
                    background = rounded(C.SURFACE, dp(C.R).toFloat(), C.STROKE, dp(1)); setPadding(dp(14), dp(12), dp(12), dp(12))
                }
                val left = vbox(this)
                left.addView(tv(this, "SYSTEM", 10f, C.MUTED).apply { typeface = mono; letterSpacing = 0.2f })
                val st = hbox(this)
                st.addView(dot(l.color, 8))
                st.addView(tv(this, " " + l.word, 15f, l.color, true).apply { typeface = Typeface.create(mono, Typeface.BOLD) })
                left.addView(st)
                c.addView(left, lp(0, WRAP, 1f))
                val last = Prefs.lastCheck(this)
                c.addView(tv(this, "POLL ${Prefs.pollSeconds(this)}s\nLAST ${if (last > 0) ((System.currentTimeMillis() - last) / 1000).toString() + "s" else "--"}", 10.5f, C.MUTED).apply {
                    typeface = mono; gravity = Gravity.END; setPadding(0, 0, dp(10), 0)
                    setOnClickListener { checkNow() }
                })
                c.addView(liveSwitch())
                liveBox.addView(c, lp())
            }
            HomeLayout.EDITORIAL -> {
                val b = vbox(this).apply { setPadding(dp(4), dp(6), dp(4), dp(4)) }
                b.addView(tv(this, SimpleDateFormat("EEEE, d MMMM", Locale.US).format(System.currentTimeMillis()), 13f, C.MUTED, true))
                val n = attentionCount(sum)
                b.addView(tv(this, if (n == 0) "All clear." else if (n == 1) "1 thing needs you." else "$n things need you.", 34f, C.TEXT, true).apply {
                    letterSpacing = -0.03f; setLineSpacing(0f, 1.0f); setPadding(0, dp(4), 0, 0)
                })
                val row = hbox(this).apply { setPadding(0, dp(10), 0, 0) }
                row.addView(dot(l.color, 8))
                row.addView(tv(this, "  " + l.title.replace("Live alerts are ", "Live alerts ").lowercase(Locale.US).replaceFirstChar { it.uppercase() } + " — " + l.sub, 12.5f, C.MUTED).apply { maxLines = 2 }, lp(0, WRAP, 1f))
                row.addView(liveSwitch())
                b.addView(row, lp())
                b.addView(tv(this, "Check now  ›", 13f, C.GOLD, true).apply { setPadding(0, dp(8), 0, dp(4)); setOnClickListener { checkNow() } })
                liveBox.addView(b, lp())
            }
        }
    }

    private fun heroBtn(label: String, res: Int, fg: Int, onClick: () -> Unit): LinearLayout = hbox(this).apply {
        gravity = Gravity.CENTER
        background = ripple(rounded(if (t.id == 10) alpha(Color.WHITE, 225) else alpha(fg, 30), dp(12).toFloat()))
        setPadding(dp(8), dp(11), dp(8), dp(11))
        val c = if (t.id == 10) Color.parseColor("#0B1630") else fg
        addView(icon(this@MainActivity, res, 15, c))
        addView(tv(this@MainActivity, "  $label", 13f, c, true))
        setOnClickListener { onClick() }
    }

    private fun quickActions(): View {
        val c = hbox(this).apply {
            background = rounded(C.SURFACE, dp(C.R).toFloat(), C.STROKE, dp(1))
            elevation = dp(6).toFloat()
            setPadding(dp(6), dp(14), dp(6), dp(12))
        }
        val items = ArrayList<Triple<String, Int, () -> Unit>>()
        if (Prefs.hasAccess(this, "orders")) items.add(Triple("Orders", R.drawable.ic_box) { openList(ListActivity.MODE_ORDERS, "") })
        if (Prefs.hasAccess(this, "afa")) items.add(Triple("AFA", R.drawable.ic_id) { openList(ListActivity.MODE_AFA, "") })
        if (Prefs.hasAccess(this, "complaints")) items.add(Triple("Complaints", R.drawable.ic_alert) { openList(ListActivity.MODE_COMPLAINTS) })
        if (Prefs.hasAccess(this, "support")) items.add(Triple("Support", R.drawable.ic_chat) { openList(ListActivity.MODE_SUPPORT) })
        if (Prefs.hasAccess(this, "shops")) items.add(Triple("Shops", R.drawable.ic_store) { openList(ListActivity.MODE_SHOPS, "pending") })
        for ((label, res, go) in items.take(5)) {
            val v = vbox(this).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(iconChip(this@MainActivity, res, C.GOLD, 46, 21))
                addView(tv(this@MainActivity, label, 11.5f, C.MUTED, true).apply { setPadding(0, dp(6), 0, 0); maxLines = 1 })
                setOnClickListener { go() }
            }
            c.addView(v, lp(0, WRAP, 1f))
        }
        return c
    }

    private fun bottomNav(): View {
        val nav = hbox(this).apply {
            setBackgroundColor(C.SURFACE)
            setPadding(0, dp(8), 0, dp(10))
        }
        val items = listOf(
            Triple("Home", R.drawable.ic_home) { s.scrollView?.smoothScrollTo(0, 0) },
            Triple("Orders", R.drawable.ic_box) { openList(ListActivity.MODE_ORDERS, "") },
            Triple("Alerts", R.drawable.ic_bell) { startActivity(Intent(this, AlertSettingsActivity::class.java)) },
            Triple("Themes", R.drawable.ic_palette) { startActivity(Intent(this, ThemeActivity::class.java)) },
            Triple("Menu", R.drawable.ic_hamburger) { openDrawer() },
        )
        items.forEachIndexed { i, (label, res, go) ->
            val col = if (i == 0) C.GOLD else C.MUTED
            val v = vbox(this).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(icon(this@MainActivity, res, 21, col))
                addView(tv(this@MainActivity, label, 10.5f, col, i == 0).apply { setPadding(0, dp(3), 0, 0) })
                background = ripple(rounded(Color.TRANSPARENT, dp(12).toFloat()))
                setOnClickListener { go() }
            }
            nav.addView(v, lp(0, WRAP, 1f))
        }
        return nav
    }

    // ── Warnings ────────────────────────────────────────────
    private fun buildWarnings() {
        warnBox.removeAllViews()
        fun warn(text: String, action: String, onClick: () -> Unit) {
            val c = card(this, C.ORANGE)
            c.addView(tv(this, "⚠  $text", 13.5f, C.TEXT))
            c.addView(btn(this, action, BTN_SECONDARY) { onClick() }, lp().margins(t = dp(10)))
            c.layoutParams = lp().margins(t = dp(10))
            warnBox.addView(c)
        }
        if (!Notifier.notificationsAllowed(this)) warn("Notifications are blocked, so alerts can't show.", "Allow notifications") { openAppNotificationSettings() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) warn("Battery saver may pause live alerts when the screen is off.", "Allow background running") { askBatteryExemption(this) }
        if (!Notifier.canFullScreen(this) && AlertType.values().any { Prefs.typeFullScreen(this, it) && Prefs.typeOn(this, it) })
            warn("Full-screen alarms (over the lock screen) need permission.", "Allow full-screen alarms") { askFullScreen(this) }
    }

    // ── Tiles per layout ────────────────────────────────────
    private fun numberFace(): Typeface = if (t.mono) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.create("sans-serif", Typeface.BOLD)

    private fun renderTiles(list: List<Tile>) {
        tilesBox.removeAllViews()
        if (list.isEmpty()) { tilesBox.addView(tv(this, "Waiting for the first check…", 13f, C.MUTED)); return }
        val screenW = resources.displayMetrics.widthPixels - dp(32)
        when (t.layout) {
            HomeLayout.EDITORIAL -> {
                val c = vbox(this).apply { background = rounded(C.SURFACE, dp(C.R).toFloat(), C.STROKE, dp(1)) }
                list.forEachIndexed { i, tile ->
                    val r = hbox(this).apply {
                        setPadding(dp(16), dp(14), dp(14), dp(14))
                        background = ripple(rounded(Color.TRANSPARENT, dp(C.R).toFloat()))
                        setOnClickListener { tile.open() }
                    }
                    r.addView(tv(this, tile.label, 15f, C.TEXT, true), lp(0, WRAP, 1f))
                    r.addView(tv(this, tile.value.toString(), 16f, if (tile.value > 0) tile.color else C.MUTED, true).apply {
                        gravity = Gravity.CENTER; minWidth = dp(44)
                        background = rounded(alpha(if (tile.value > 0) tile.color else C.MUTED, 30), dp(10).toFloat())
                        setPadding(dp(10), dp(3), dp(10), dp(3))
                    })
                    c.addView(r, lp())
                    if (i < list.size - 1) c.addView(View(this).apply { setBackgroundColor(C.STROKE) }, lp(MATCH, dp(1)))
                }
                tilesBox.addView(c, lp())
            }
            HomeLayout.QUEUE -> {
                val g = GridLayout(this).apply { columnCount = 4 }
                val w = (screenW - dp(8) * 3) / 4
                list.forEachIndexed { i, tile ->
                    val v = vbox(this).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        background = ripple(rounded(C.SURFACE, dp(12).toFloat(), if (tile.value > 0) alpha(tile.color, 120) else C.STROKE, dp(1)))
                        setPadding(dp(4), dp(10), dp(4), dp(10))
                        setOnClickListener { tile.open() }
                    }
                    v.addView(tv(this, tile.value.toString(), 20f, if (tile.value > 0) tile.color else C.TEXT, true).apply { typeface = numberFace() })
                    v.addView(tv(this, tile.label, 10f, C.MUTED).apply { gravity = Gravity.CENTER; maxLines = 2 })
                    g.addView(v, GridLayout.LayoutParams().apply { width = w; height = dp(66); setMargins(0, 0, if (i % 4 == 3) 0 else dp(8), dp(8)) })
                }
                tilesBox.addView(g, lp())
            }
            else -> {
                val g = GridLayout(this).apply { columnCount = 2 }
                val w = (screenW - dp(10)) / 2
                val maxV = (list.maxOfOrNull { it.value } ?: 1).coerceAtLeast(1)
                list.forEachIndexed { i, tile ->
                    val v: View = when (t.layout) {
                        HomeLayout.BANNER -> hbox(this).apply {
                            background = ripple(rounded(C.SURFACE, dp(16).toFloat(), C.STROKE, dp(1)))
                            setPadding(dp(12), dp(12), dp(10), dp(12))
                            addView(iconChip(this@MainActivity, tile.icon, tile.color, 38, 18))
                            val tx = vbox(this@MainActivity).apply { setPadding(dp(10), 0, 0, 0) }
                            tx.addView(tv(this@MainActivity, tile.value.toString(), 19f, C.TEXT, true))
                            tx.addView(tv(this@MainActivity, tile.label, 11.5f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                            addView(tx, lp(0, WRAP, 1f))
                            setOnClickListener { tile.open() }
                        }
                        HomeLayout.COMMAND -> vbox(this).apply {
                            background = ripple(rounded(C.SURFACE, dp(12).toFloat(), C.STROKE, dp(1)))
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            val h = hbox(this@MainActivity)
                            h.addView(tv(this@MainActivity, tile.label.uppercase(Locale.US), 9.5f, C.MUTED).apply {
                                typeface = Typeface.MONOSPACE; letterSpacing = 0.06f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                            }, lp(0, WRAP, 1f))
                            h.addView(icon(this@MainActivity, tile.icon, 14, tile.color))
                            addView(h, lp())
                            addView(tv(this@MainActivity, String.format(Locale.US, "%02d", tile.value), 28f, if (tile.value > 0) tile.color else C.TEXT, true).apply { typeface = numberFace() })
                            // share-of-total bar (real proportion, not decoration)
                            val track = FrameLayout(this@MainActivity).apply { background = rounded(alpha(tile.color, 35), dp(3).toFloat()) }
                            val fill = View(this@MainActivity).apply { background = rounded(tile.color, dp(3).toFloat()) }
                            val fw = ((w - dp(24)) * tile.value / maxV).coerceAtLeast(if (tile.value > 0) dp(6) else 0)
                            track.addView(fill, FrameLayout.LayoutParams(fw, dp(5)))
                            addView(track, lp(MATCH, dp(5)).margins(t = dp(8)))
                            setOnClickListener { tile.open() }
                        }
                        else -> FrameLayout(this).apply {   // CLASSIC
                            background = ripple(rounded(C.SURFACE, dp(C.R).toFloat(), C.STROKE, dp(1)))
                            clipToOutline = true
                            addView(View(this@MainActivity).apply { setBackgroundColor(tile.color) }, FrameLayout.LayoutParams(dp(3), FrameLayout.LayoutParams.MATCH_PARENT))
                            val inner = vbox(this@MainActivity).apply { setPadding(dp(14), dp(14), dp(12), dp(14)) }
                            inner.addView(iconChip(this@MainActivity, tile.icon, tile.color, 34, 18))
                            inner.addView(tv(this@MainActivity, tile.value.toString(), 28f, C.TEXT, true).apply { typeface = numberFace(); setPadding(0, dp(8), 0, 0); letterSpacing = -0.02f })
                            inner.addView(tv(this@MainActivity, tile.label, 12.5f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                            addView(inner)
                            setOnClickListener { tile.open() }
                        }
                    }
                    if (t.layout != HomeLayout.CLASSIC) v.elevation = 0f
                    g.addView(v, GridLayout.LayoutParams().apply { width = w; setMargins(0, 0, if (i % 2 == 0) dp(10) else 0, dp(10)) })
                }
                tilesBox.addView(g, lp())
            }
        }
    }

    // ── History per layout ──────────────────────────────────
    private fun renderHistory() {
        historyBox.removeAllViews()
        val items = AlertCards.visible(this)
        if (items.isEmpty()) {
            historyBox.addView(emptyView(this, "No alerts yet. New complaints, chats and order problems will appear here and ring your phone. Only alert types switched on in Alert settings show here."))
            return
        }
        val n = if (t.layout == HomeLayout.COMMAND) 10 else 5
        AlertCards.render(this, historyBox, items.take(n), t.layout)
        if (items.size > n) {
            historyBox.addView(btn(this, "View all ${items.size} priority alerts", BTN_SECONDARY) {
                startActivity(Intent(this, PriorityActivity::class.java))
            }.apply { layoutParams = lp().margins(t = dp(4)) })
        }
    }

    // ── Server refresh ──────────────────────────────────────
    private fun refreshSummary() {
        Api.run(this, { Api.call(this, "GET", "/api/admin-app/summary") }) { res, _ ->
            res?.optJSONObject("summary")?.let { Prefs.saveSummary(this, it); refreshLocal() }
        }
    }

    private fun refreshMe() {
        Api.run(this, { Api.call(this, "GET", "/api/admin-app/me") }) { res, _ ->
            res?.optJSONObject("admin")?.let {
                Prefs.saveAdmin(this, it)
                headerTitle?.text = it.optString("site_name", "MAPTECH GLOBAL").uppercase(Locale.US)
                headerSub?.text = subtitle(it)
            }
        }
    }

    private fun openListDated(mode: String, filter: String, date: String) {
        startActivity(Intent(this, ListActivity::class.java).putExtra("mode", mode).putExtra("filter", filter).putExtra("date", date))
    }

    private fun openList(mode: String, filter: String? = null) {
        val i = Intent(this, ListActivity::class.java).putExtra("mode", mode)
        if (filter != null) i.putExtra("filter", filter)
        startActivity(i)
    }

    private fun openDrawer() {
        val sum = Prefs.summary(this)
        fun n(k: String) = sum.optInt(k, 0)
        val tiles = ArrayList<Drawer.Item>()
        if (Prefs.hasAccess(this, "orders")) {
            tiles.add(Drawer.Item("Orders", R.drawable.ic_box, C.GOLD, n("orders_processing")) { openList(ListActivity.MODE_ORDERS, "") })
            tiles.add(Drawer.Item("Needs review", R.drawable.ic_shield, C.RED, n("orders_needs_review")) { openList(ListActivity.MODE_ORDERS, "needs_review") })
        }
        if (Prefs.hasAccess(this, "afa")) tiles.add(Drawer.Item("AFA orders", R.drawable.ic_id, C.ORANGE, n("afa_processing")) { openList(ListActivity.MODE_AFA, "") })
        if (Prefs.hasAccess(this, "complaints")) tiles.add(Drawer.Item("Complaints", R.drawable.ic_alert, C.RED, n("complaints_open")) { openList(ListActivity.MODE_COMPLAINTS) })
        if (Prefs.hasAccess(this, "support")) tiles.add(Drawer.Item("Live Support", R.drawable.ic_chat, C.BLUE, n("support_unread")) { openList(ListActivity.MODE_SUPPORT) })
        if (Prefs.hasAccess(this, "contact")) tiles.add(Drawer.Item("Contact messages", R.drawable.ic_mail, C.PURPLE, n("contact_new")) { openList(ListActivity.MODE_CONTACT) })
        if (Prefs.hasAccess(this, "shops")) {
            tiles.add(Drawer.Item("Shops", R.drawable.ic_store, C.BLUE, n("shops_pending")) { openList(ListActivity.MODE_SHOPS, "pending") })
            tiles.add(Drawer.Item("Shop withdrawals", R.drawable.ic_wallet, C.YELLOW, n("withdrawals_pending")) { openList(ListActivity.MODE_WITHDRAWALS, "pending") })
        }
        tiles.add(Drawer.Item("Priority alerts", R.drawable.ic_bell, C.ORANGE, AlertCards.visible(this).size) { startActivity(Intent(this, PriorityActivity::class.java)) })
        val links = listOf(
            Drawer.Item("Alert sounds & toggles", R.drawable.ic_bell, C.GOLD) { startActivity(Intent(this, AlertSettingsActivity::class.java)) },
            Drawer.Item("Themes", R.drawable.ic_palette, C.PURPLE) { startActivity(Intent(this, ThemeActivity::class.java)) },
            Drawer.Item("Signed-in phones", R.drawable.ic_phone_dev, C.GREEN) { openList(ListActivity.MODE_DEVICES) },
            Drawer.Item("Open admin website", R.drawable.ic_external, C.BLUE) { openUrl(this, Prefs.serverUrl(this).trimEnd('/') + "/admin/dashboard") }
        )
        val footer = listOf(
            Drawer.Item("About", R.drawable.ic_info, C.BLUE) { startActivity(Intent(this, AboutActivity::class.java)) },
            Drawer.Item("Sign out", R.drawable.ic_logout, C.RED, danger = true) { confirmSignOut() }
        )
        drawer.open(Prefs.admin(this), Prefs.liveEnabled(this), tiles, links, footer)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.isOpen) drawer.close() else super.onBackPressed()
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out?")
            .setMessage("This phone will stop receiving alerts until you sign in again.")
            .setPositiveButton("Sign out") { _, _ ->
                val ctx = applicationContext
                Api.run(null, { try { Api.call(ctx, "POST", "/api/admin-app/logout") } catch (e: Exception) { null } }) { _, _ -> }
                AlertService.stop(this)
                AlertPlayer.stop(this)
                window.decorView.postDelayed({ Api.sessionEnded(this, "Signed out.") }, 600)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    companion object {
        fun askBatteryExemption(a: Activity) {
            try {
                @Suppress("BatteryLife")
                a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
            } catch (e: Exception) {
                try { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
            }
        }

        fun askFullScreen(a: Activity) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    a.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${a.packageName}")))
                }
            } catch (e: Exception) {
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
            }
        }
    }
}
