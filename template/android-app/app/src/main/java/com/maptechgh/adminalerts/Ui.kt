package com.maptechgh.adminalerts

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Live palette — loaded from the selected theme (see Themes.kt). */
object C {
    var THEME: AppTheme = Themes.byId(Themes.DEFAULT_ID)
    var BG = 0; var SURFACE = 0; var SURFACE2 = 0; var STROKE = 0; var TEXT = 0; var MUTED = 0
    var GOLD = 0          // the theme's primary accent (buttons, chips, switches)
    var ON_GOLD = 0       // text on the accent
    var BLUE = 0; var RED = 0; var GREEN = 0; var ORANGE = 0; var PURPLE = 0; var YELLOW = 0
    var R = 16            // card corner radius (dp)
    var LIGHT = false

    fun load(t: AppTheme) {
        THEME = t
        BG = t.bg; SURFACE = t.surface; SURFACE2 = t.surface2; STROKE = t.stroke; TEXT = t.text; MUTED = t.muted
        GOLD = t.accent; ON_GOLD = t.onAccent; BLUE = t.blue; RED = t.red; GREEN = t.green; ORANGE = t.orange
        PURPLE = t.purple; YELLOW = t.gold; R = t.radius; LIGHT = t.light
    }

    init { load(THEME) }
}

fun alpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

fun ripple(bg: Drawable, color: Int = Color.parseColor("#33FFFFFF")): Drawable =
    RippleDrawable(ColorStateList.valueOf(color), bg, null)

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
    setMargins(l, t, r, b); return this
}

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

fun tv(c: Context, text: CharSequence, size: Float = 14f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(c).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

fun vbox(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
fun hbox(c: Context): LinearLayout = LinearLayout(c).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun card(c: Context, accent: Int? = null): LinearLayout = vbox(c).apply {
    background = rounded(C.SURFACE, c.dp(C.R).toFloat(), accent ?: C.STROKE, c.dp(1))
    setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
    layoutParams = lp().margins(b = c.dp(12))
}

const val BTN_PRIMARY = 0
const val BTN_SECONDARY = 1
const val BTN_DANGER = 2
const val BTN_GHOST = 3

fun btn(c: Context, text: String, kind: Int = BTN_PRIMARY, onClick: (View) -> Unit): TextView =
    TextView(c).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val r = c.dp(12).toFloat()
        val (bg, fg) = when (kind) {
            BTN_PRIMARY -> rounded(C.GOLD, r) to C.ON_GOLD
            BTN_DANGER -> rounded(C.RED, r) to Color.WHITE
            BTN_GHOST -> rounded(Color.TRANSPARENT, r, C.STROKE, c.dp(1)) to C.TEXT
            else -> rounded(C.SURFACE2, r, C.STROKE, c.dp(1)) to C.TEXT
        }
        background = ripple(bg, if (C.LIGHT) Color.parseColor("#22000000") else Color.parseColor("#33FFFFFF"))
        setTextColor(fg)
        setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
        isClickable = true
        isFocusable = true
        setOnClickListener(onClick)
    }

fun chip(c: Context, text: String, selected: Boolean, onClick: (View) -> Unit): TextView =
    TextView(c).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val r = c.dp(20).toFloat()
        background = ripple(if (selected) rounded(C.GOLD, r) else rounded(C.SURFACE2, r, C.STROKE, c.dp(1)))
        setTextColor(if (selected) C.ON_GOLD else C.TEXT)
        if (selected) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(c.dp(14), c.dp(7), c.dp(14), c.dp(7))
        layoutParams = lp(WRAP, WRAP).margins(r = c.dp(8))
        setOnClickListener(onClick)
    }

fun badge(c: Context, text: String, color: Int): TextView = TextView(c).apply {
    this.text = text.uppercase(Locale.US)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
    letterSpacing = 0.06f
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    setTextColor(color)
    background = rounded(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), c.dp(8).toFloat())
    setPadding(c.dp(8), c.dp(3), c.dp(8), c.dp(3))
}

fun input(c: Context, hint: String, multiline: Boolean = false, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
    EditText(c).apply {
        this.hint = hint
        setHintTextColor(C.MUTED)
        setTextColor(C.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = rounded(C.SURFACE2, c.dp(12).toFloat(), C.STROKE, c.dp(1))
        setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
        inputType = if (multiline) type or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else type
        if (multiline) { minLines = 3; gravity = Gravity.TOP or Gravity.START }
        layoutParams = lp().margins(b = c.dp(10))
    }

fun styledSwitch(c: Context, checked: Boolean): Switch = Switch(c).apply {
    isChecked = checked
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = ColorStateList(states, intArrayOf(C.GOLD, if (C.LIGHT) Color.WHITE else Color.parseColor("#C9D1DE")))
    trackTintList = ColorStateList(states, intArrayOf(alpha(C.GOLD, 140), alpha(C.MUTED, 110)))
}

/** Title + optional subtitle on the left, a switch on the right. */
fun switchRow(c: Context, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
    val row = hbox(c).apply { setPadding(0, c.dp(8), 0, c.dp(8)) }
    val texts = vbox(c)
    texts.addView(tv(c, title, 15f, C.TEXT, true))
    if (!sub.isNullOrEmpty()) texts.addView(tv(c, sub, 12.5f, C.MUTED))
    row.addView(texts, lp(0, WRAP, 1f))
    val sw = styledSwitch(c, checked)
    sw.setOnCheckedChangeListener { _, v -> onChange(v) }
    row.addView(sw, lp(WRAP, WRAP))
    row.setOnClickListener { sw.toggle() }
    return row
}

fun divider(c: Context): View = View(c).apply {
    setBackgroundColor(C.STROKE)
    layoutParams = lp(MATCH, c.dp(1)).margins(t = c.dp(8), b = c.dp(8))
}

fun sectionTitle(c: Context, text: String): TextView = tv(c, text.uppercase(Locale.US), 12f, C.MUTED, true).apply {
    letterSpacing = 0.1f
    layoutParams = lp().margins(t = c.dp(8), b = c.dp(8), l = c.dp(4))
}

/** Standard screen: top bar (back, title, actions) + scrolling body. */
class Screen(val act: Activity, title: String, subtitle: String? = null, back: Boolean = true, scroll: Boolean = true) {
    val root: LinearLayout = vbox(act).apply { background = Themes.background() }
    val titleView: TextView
    val subtitleView: TextView
    val actions: LinearLayout = hbox(act)
    val body: LinearLayout = vbox(act).apply { setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(24)) }
    val scrollView: ScrollView?

    init {
        val bar = hbox(act).apply { setPadding(act.dp(8), act.dp(10), act.dp(8), act.dp(10)) }
        if (back) {
            bar.addView(iconButton(act, "←") { act.onBackPressed() })
        } else {
            bar.addView(View(act), lp(act.dp(8), 1))
        }
        val tbox = vbox(act).apply { setPadding(act.dp(6), 0, 0, 0) }
        titleView = tv(act, title, 19f, C.TEXT, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        subtitleView = tv(act, subtitle ?: "", 12.5f, C.MUTED).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        tbox.addView(titleView); tbox.addView(subtitleView)
        bar.addView(tbox, lp(0, WRAP, 1f))
        bar.addView(actions)
        root.addView(bar, lp())
        if (scroll) {
            scrollView = ScrollView(act).apply { isFillViewport = true; addView(body, lp()) }
            root.addView(scrollView, lp(MATCH, 0, 1f))
        } else {
            scrollView = null
            root.addView(body, lp(MATCH, 0, 1f))
        }
        act.setContentView(root)
    }

    fun setSubtitle(s: String?) {
        subtitleView.text = s ?: ""
        subtitleView.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun action(label: String, onClick: (View) -> Unit): TextView {
        val b = iconButton(act, label, onClick)
        actions.addView(b)
        return b
    }
}

fun iconButton(c: Context, label: String, onClick: (View) -> Unit): TextView = TextView(c).apply {
    text = label
    setTextColor(C.TEXT)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length <= 2) 20f else 14f)
    gravity = Gravity.CENTER
    minWidth = c.dp(44)
    minHeight = c.dp(44)
    setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(6))
    background = ripple(rounded(Color.TRANSPARENT, c.dp(22).toFloat()))
    setOnClickListener(onClick)
}

fun loadingView(c: Context, msg: String = "Loading…"): TextView =
    tv(c, msg, 14f, C.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, c.dp(40), 0, c.dp(40)) }

fun emptyView(c: Context, msg: String): TextView =
    tv(c, msg, 14f, C.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, c.dp(40), 0, c.dp(40)) }

fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_LONG).show()

// ── Formatting helpers ──────────────────────────────────────
private val serverFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("Africa/Accra") }

fun parseServerTime(s: String?): Long? = try { if (s.isNullOrEmpty() || s == "null") null else serverFmt.parse(s)?.time } catch (e: Exception) { null }

fun ago(ms: Long?): String {
    if (ms == null) return ""
    val d = (System.currentTimeMillis() - ms) / 1000
    return when {
        d < 45 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86400 -> "${d / 3600} h ago"
        d < 7 * 86400 -> "${d / 86400} d ago"
        else -> SimpleDateFormat("d MMM yyyy", Locale.US).format(ms)
    }
}

fun agoServer(s: String?): String = ago(parseServerTime(s))

fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(ms)

fun money(v: Double): String = "GH₵" + String.format(Locale.US, "%,.2f", v)

fun org.json.JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

fun statusColor(s: String): Int = when (s.lowercase(Locale.US)) {
    "open", "failed", "new", "rejected" -> C.RED
    "reviewing", "processing", "placed", "accepted", "pending" -> C.ORANGE
    "resolved", "delivered", "read", "replied", "closed" -> C.GREEN
    "refunded", "cancelled" -> C.PURPLE
    else -> C.BLUE
}

fun copyText(c: Context, label: String, text: String) {
    (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    toast(c, "$label copied")
}

/** 0241234567 → 233241234567 for WhatsApp links. */
fun ghIntl(phone: String): String {
    val d = phone.filter { it.isDigit() }
    return when {
        d.startsWith("233") -> d
        d.startsWith("0") && d.length == 10 -> "233" + d.substring(1)
        d.length == 9 -> "233$d"
        else -> d
    }
}

fun openUrl(c: Context, url: String) {
    try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: Exception) { toast(c, "No app found to open this link.") }
}

fun callPhone(c: Context, phone: String) = openUrl(c, "tel:$phone")
fun whatsApp(c: Context, phone: String, text: String = "") =
    openUrl(c, "https://wa.me/${ghIntl(phone)}" + if (text.isNotEmpty()) "?text=" + Uri.encode(text) else "")

// ── Icons, logo & credits ───────────────────────────────────
fun icon(c: Context, res: Int, sizeDp: Int = 20, color: Int = C.TEXT): android.widget.ImageView =
    android.widget.ImageView(c).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(c.dp(sizeDp), c.dp(sizeDp))
    }

/** Rounded square holding an icon, tinted with [color] on a soft background of the same colour. */
fun iconChip(c: Context, res: Int, color: Int, boxDp: Int = 34, iconDp: Int = 18): android.widget.FrameLayout =
    android.widget.FrameLayout(c).apply {
        background = rounded(alpha(color, if (C.LIGHT) 30 else 38), c.dp(10).toFloat())
        val i = icon(c, res, iconDp, color)
        addView(i, android.widget.FrameLayout.LayoutParams(c.dp(iconDp), c.dp(iconDp), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(c.dp(boxDp), c.dp(boxDp))
    }

fun logoView(c: Context, sizeDp: Int = 42): android.widget.ImageView = android.widget.ImageView(c).apply {
    setImageResource(R.drawable.logo_full)
    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    background = rounded(Color.WHITE, c.dp(12).toFloat())
    val p = c.dp(sizeDp / 9); setPadding(p, p, p, p)
    layoutParams = LinearLayout.LayoutParams(c.dp(sizeDp), c.dp(sizeDp))
}

/** "Developed by M-AVETECH IT SERVICES" card with website + WhatsApp buttons. */
fun creditsCard(c: Context): LinearLayout {
    val card = card(c)
    val top = hbox(c)
    top.addView(logoView(c, 40))
    val t = vbox(c).apply { setPadding(c.dp(12), 0, 0, 0) }
    t.addView(tv(c, "Developed by", 11.5f, C.MUTED))
    t.addView(tv(c, "M-AVETECH IT SERVICES", 14.5f, C.TEXT, true).apply { letterSpacing = 0.03f })
    top.addView(t, lp(0, WRAP, 1f))
    card.addView(top, lp())
    val row = hbox(c).apply { setPadding(0, c.dp(12), 0, 0) }
    val web = hbox(c).apply {
        gravity = Gravity.CENTER
        background = ripple(rounded(C.SURFACE2, c.dp(12).toFloat(), C.STROKE, c.dp(1)))
        setPadding(c.dp(8), c.dp(11), c.dp(8), c.dp(11))
        addView(icon(c, R.drawable.ic_globe, 16, C.TEXT))
        addView(tv(c, "  mavetechservices.com", 12.5f, C.TEXT, true).apply { maxLines = 1 })
        setOnClickListener { openUrl(c, DEV_WEBSITE) }
    }
    val wa = hbox(c).apply {
        gravity = Gravity.CENTER
        background = ripple(rounded(Color.parseColor("#1FAF5A"), c.dp(12).toFloat()))
        setPadding(c.dp(8), c.dp(11), c.dp(8), c.dp(11))
        addView(icon(c, R.drawable.ic_wa, 16, Color.WHITE))
        addView(tv(c, "  WhatsApp", 12.5f, Color.WHITE, true))
        setOnClickListener { openUrl(c, DEV_WHATSAPP) }
    }
    row.addView(web, lp(0, WRAP, 1.25f).margins(r = c.dp(8)))
    row.addView(wa, lp(0, WRAP, 1f))
    card.addView(row, lp())
    card.addView(tv(c, "Maptech Admin v$APP_VERSION  •  © 2026 M-AVETECH IT SERVICES", 11f, C.MUTED).apply {
        gravity = Gravity.CENTER; setPadding(0, c.dp(10), 0, 0)
    }, lp())
    card.layoutParams = lp().margins(t = c.dp(18), b = c.dp(8))
    return card
}
