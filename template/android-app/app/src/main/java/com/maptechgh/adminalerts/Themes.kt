package com.maptechgh.adminalerts

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View

/** Home-screen structure used by a theme. */
enum class HomeLayout(val label: String) {
    CLASSIC("Hero card + tiles"),
    BANNER("Banking banner + tab bar"),
    QUEUE("Alerts-first priority queue"),
    COMMAND("Ops console"),
    EDITORIAL("Editorial headline"),
}

class AppTheme(
    val id: Int,
    val name: String,
    val desc: String,
    val layout: HomeLayout,
    val light: Boolean,
    bg: String, val bgGrad: List<String>?,
    surface: String, surface2: String, stroke: String,
    text: String, muted: String,
    accent: String, onAccent: String,
    red: String, orange: String, gold: String, blue: String, green: String, purple: String,
    hero: List<String>, heroText: String,
    val radius: Int,
    val mono: Boolean = false,
) {
    val bg = col(bg)
    val bgGradColors = bgGrad?.map { col(it) }?.toIntArray()
    val surface = col(surface); val surface2 = col(surface2); val stroke = col(stroke)
    val text = col(text); val muted = col(muted)
    val accent = col(accent); val onAccent = col(onAccent)
    val red = col(red); val orange = col(orange); val gold = col(gold); val blue = col(blue); val green = col(green); val purple = col(purple)
    val hero = hero.map { col(it) }.toIntArray()
    val heroText = col(heroText)

    companion object {
        /** "#RRGGBB" or "#AARRGGBB" */
        fun col(s: String) = Color.parseColor(s)
    }
}

object Themes {
    val all = listOf(
        AppTheme(1, "Midnight Gold", "Deep navy, gold accents, softly lit cards with coloured edges.", HomeLayout.CLASSIC, false,
            "#0A0F1C", null, "#121A2C", "#1A2440", "#24314D", "#ECF0F7", "#8D99B0", "#F5B942", "#1A1405",
            "#F05252", "#F59E0B", "#F5B942", "#5B93FF", "#22C55E", "#A78BFA", listOf("#24305A", "#121A2C"), "#ECF0F7", 18),
        AppTheme(2, "Royal Banking", "Light bank-app style: blue banner with the attention count, quick actions, bottom tabs.", HomeLayout.BANNER, true,
            "#F3F6FC", null, "#FFFFFF", "#EEF2FB", "#E3E9F5", "#0F1B33", "#6A7894", "#1E5EFF", "#FFFFFF",
            "#E5484D", "#E58A00", "#C99A1E", "#1E5EFF", "#1E9E57", "#7C5CFA", listOf("#0B2E8A", "#1E5EFF", "#3C8BFF"), "#FFFFFF", 18),
        AppTheme(3, "Carbon Alert", "Pure black, red & amber. Alerts first with Handle-now buttons.", HomeLayout.QUEUE, false,
            "#050505", null, "#111111", "#1C1C1C", "#232323", "#F5F5F5", "#8A8A8A", "#FF3B30", "#FFFFFF",
            "#FF453A", "#FF9F0A", "#FFD60A", "#64D2FF", "#32D74B", "#BF5AF2", listOf("#1C1C1C", "#111111"), "#F5F5F5", 16),
        AppTheme(4, "Emerald Ops Console", "Green-black command centre: monospace numbers, activity bars, event log.", HomeLayout.COMMAND, false,
            "#06110D", null, "#0C1A15", "#13261F", "#1B3329", "#DDF5EA", "#6F9585", "#2EE59D", "#04110B",
            "#FF5C6C", "#FFB547", "#E8D06A", "#4FD1FF", "#2EE59D", "#B794F6", listOf("#0F2A20", "#0C1A15"), "#DDF5EA", 14, mono = true),
        AppTheme(5, "Pearl Executive", "Light, calm and premium with a big editorial headline.", HomeLayout.EDITORIAL, true,
            "#FAFAF7", null, "#FFFFFF", "#F1F0EA", "#E9E7DF", "#151515", "#77756C", "#B8862B", "#FFFFFF",
            "#C9372C", "#C27803", "#B8862B", "#2F5BD3", "#1F9D55", "#7A4FD0", listOf("#FFFFFF", "#F6F4EC"), "#151515", 20),
        AppTheme(6, "Aurora Glass", "Violet-to-teal aurora with frosted see-through cards.", HomeLayout.CLASSIC, false,
            "#0B0820", listOf("#3A1C8C", "#0B0820", "#073B42"), "#17FFFFFF", "#1FFFFFFF", "#24FFFFFF", "#F3F0FF", "#B3AAD6", "#8B5CF6", "#FFFFFF",
            "#FF6B8B", "#FFC266", "#FFD27A", "#6EE7F9", "#4ADE80", "#C4B5FD", listOf("#6D4AE0", "#1A8F9E"), "#FFFFFF", 20),
        AppTheme(7, "Graphite Pro", "Dark editorial: graphite tones with one electric-blue accent.", HomeLayout.EDITORIAL, false,
            "#131416", null, "#1B1C1F", "#24262A", "#2A2C31", "#F2F3F5", "#8E939C", "#3D8BFF", "#FFFFFF",
            "#FF5A5F", "#FFB020", "#E2C044", "#3D8BFF", "#30D158", "#A78BFA", listOf("#1B1C1F", "#131416"), "#F2F3F5", 16),
        AppTheme(8, "Sunset Pulse", "Warm dark theme: magenta-to-orange banner, bold rounded cards, tab bar.", HomeLayout.BANNER, false,
            "#120C14", null, "#1C1420", "#271C2D", "#33253A", "#FFF4F0", "#B49BA8", "#FF7A45", "#1A0A05",
            "#FF4D6D", "#FFA94D", "#FFC857", "#7AA2FF", "#43D17A", "#C084FC", listOf("#C2185B", "#FF5E3A", "#FF9A3C"), "#FFFFFF", 20),
        AppTheme(9, "Ocean Queue", "Deep ocean blue with cyan, alerts-first priority queue.", HomeLayout.QUEUE, false,
            "#06142A", null, "#0C1F3D", "#12294F", "#173363", "#EAF4FF", "#86A3C9", "#22D3EE", "#06142A",
            "#FF6B6B", "#FFB84D", "#FFD166", "#22D3EE", "#34D399", "#A78BFA", listOf("#12294F", "#0C1F3D"), "#EAF4FF", 18),
        AppTheme(10, "Maptech Brand", "Your logo’s gold and globe-blue on deep blue-black, matching the website.", HomeLayout.CLASSIC, false,
            "#07101F", null, "#0E1B33", "#152647", "#1E3463", "#EEF4FF", "#8AA0C6", "#F2B630", "#111111",
            "#FF5D5D", "#F59E0B", "#F2B630", "#2F8BFF", "#2ED47A", "#A78BFA", listOf("#F2B630", "#E8A21C", "#1F6FE5"), "#0B1630", 18),
    )

    const val DEFAULT_ID = 10
    const val MAX_QUICK = 5

    fun byId(id: Int) = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
    fun current(c: Context) = byId(Prefs.themeId(c))

    /** Call at the very start of every Activity.onCreate (before super.onCreate). */
    fun applyTo(a: Activity): AppTheme {
        val t = current(a)
        C.load(t)
        a.setTheme(if (t.light) R.style.AppThemeLight else R.style.AppTheme)
        a.window.setBackgroundDrawable(ColorDrawable(t.bg))
        a.window.statusBarColor = if (t.bgGradColors != null) t.bgGradColors[0] else t.bg
        a.window.navigationBarColor = t.bg
        var flags = a.window.decorView.systemUiVisibility
        flags = if (t.light) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else flags and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        a.window.decorView.systemUiVisibility = flags
        return t
    }

    /** Screen background (solid or aurora gradient). */
    fun background(): Drawable = C.THEME.bgGradColors?.let {
        GradientDrawable(GradientDrawable.Orientation.TL_BR, it)
    } ?: ColorDrawable(C.BG)

    fun heroDrawable(radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, if (C.THEME.hero.size > 1) C.THEME.hero else intArrayOf(C.THEME.hero[0], C.THEME.hero[0])).apply {
            cornerRadius = radius
        }

    fun quick(c: Context): List<AppTheme> = Prefs.quickThemes(c).mapNotNull { id -> all.firstOrNull { it.id == id } }
}
