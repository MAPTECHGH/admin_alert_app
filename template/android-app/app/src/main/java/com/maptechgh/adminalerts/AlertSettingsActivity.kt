package com.maptechgh.adminalerts

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/**
 * Admin-only alert controls: turn every alert type on/off, give each one
 * its own ringtone (built-in, any phone sound, or an audio file), choose
 * how long it rings, vibration, full-screen alarm, volume, ring-on-silent
 * and quiet hours. Each type has a Test button that behaves exactly like
 * a real alert.
 */
class AlertSettingsActivity : Activity() {

    private lateinit var s: Screen
    private val toneLabels = HashMap<AlertType, TextView>()

    companion object {
        private const val REQ_SYSTEM = 1000
        private const val REQ_FILE = 2000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.applyTo(this)
        super.onCreate(savedInstanceState)
        s = Screen(this, "Alerts & sounds", "Only on this phone")
        build()
    }

    private var builtTheme = 0

    override fun onResume() {
        super.onResume()
        if (builtTheme != 0 && builtTheme != Prefs.themeId(this)) { recreate(); return }
        builtTheme = Prefs.themeId(this)
    }

    override fun onPause() {
        super.onPause()
        AlertPlayer.stop(this)
    }

    private fun build() {
        val b = s.body
        b.removeAllViews()
        toneLabels.clear()

        // ── Appearance ───────────────────────────────────────
        b.addView(sectionTitle(this, "Appearance"))
        b.addView(btn(this, "🎨  Themes — now: ${C.THEME.name}", BTN_SECONDARY) {
            startActivity(android.content.Intent(this, ThemeActivity::class.java))
        }, lp().margins(b = dp(4)))

        // ── Master switches ──────────────────────────────────
        b.addView(sectionTitle(this, "Master switches"))
        val m = card(this)
        m.addView(switchRow(this, "Notifications", "Show alerts at all", Prefs.masterNotify(this)) {
            Prefs.setBool(this, "master_notify", it)
        })
        m.addView(divider(this))
        m.addView(switchRow(this, "Sounds & ringing", "Turn off to get silent alerts only", Prefs.masterSound(this)) {
            Prefs.setBool(this, "master_sound", it); if (!it) AlertPlayer.stop(this)
        })
        m.addView(divider(this))
        m.addView(switchRow(this, "Live alerts running", "Background checking for new events", Prefs.liveEnabled(this)) {
            Prefs.setLiveEnabled(this, it)
            if (it) AlertService.start(this) else AlertService.stop(this)
        })
        b.addView(m)

        // ── Sound behaviour ──────────────────────────────────
        b.addView(sectionTitle(this, "Sound"))
        val snd = card(this)
        val volLabel = tv(this, "Alert volume: ${Prefs.volume(this)}%", 15f, C.TEXT, true)
        snd.addView(volLabel)
        val seek = SeekBar(this).apply {
            max = 100
            progress = Prefs.volume(this@AlertSettingsActivity)
            progressTintList = android.content.res.ColorStateList.valueOf(C.GOLD)
            thumbTintList = android.content.res.ColorStateList.valueOf(C.GOLD)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    val vv = v.coerceAtLeast(5); volLabel.text = "Alert volume: $vv%"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    Prefs.setInt(this@AlertSettingsActivity, "volume", sb.progress.coerceAtLeast(5))
                    AlertPlayer.play(this@AlertSettingsActivity, AlertType.COMPLAINT, preview = true)
                }
            })
        }
        snd.addView(seek, lp().margins(t = dp(6), b = dp(6)))
        snd.addView(divider(this))
        snd.addView(switchRow(this, "Ring even on silent", "Uses the alarm volume so you never miss a complaint, even when the phone is on silent or vibrate",
            Prefs.ringOnSilent(this)) { Prefs.setBool(this, "ring_on_silent", it) })
        snd.addView(divider(this))
        snd.addView(switchRow(this, "Force maximum loudness", "Temporarily raises the alarm volume to max while an alert rings (needs 'Ring even on silent')",
            Prefs.forceLoud(this)) { Prefs.setBool(this, "force_loud", it) })
        b.addView(snd)

        // ── Per-alert-type settings ──────────────────────────
        b.addView(sectionTitle(this, "Alert types & ringtones"))
        for (t in AlertType.values()) {
            if (!Prefs.hasAccess(this, t.access)) continue
            b.addView(typeCard(t))
        }
        if (AlertType.values().none { Prefs.hasAccess(this, it.access) }) {
            b.addView(emptyView(this, "Your admin role has no alert permissions. Ask the Owner to update your role."))
        }

        // ── Checking speed ───────────────────────────────────
        b.addView(sectionTitle(this, "Checking"))
        val chk = card(this)
        chk.addView(tv(this, "Check for new alerts every", 14f, C.TEXT, true))
        chk.addView(spinner(POLL_CHOICES.map { "$it seconds" + if (it == 15) " (recommended)" else "" },
            POLL_CHOICES.indexOf(Prefs.pollSeconds(this)).coerceAtLeast(0)) {
            Prefs.setInt(this, "poll_sec", POLL_CHOICES[it]); AlertService.pollNow(this)
        })
        chk.addView(tv(this, "Treat an order as stuck after", 14f, C.TEXT, true), lp().margins(t = dp(10)))
        chk.addView(spinner(STUCK_CHOICES.map { "$it minutes pending" }, STUCK_CHOICES.indexOf(Prefs.stuckMinutes(this)).coerceAtLeast(0)) {
            Prefs.setInt(this, "stuck_min", STUCK_CHOICES[it])
        })
        chk.addView(divider(this))
        chk.addView(switchRow(this, "Keep phone awake for alerts", "Most reliable when the screen is off. Uses a little more battery.",
            Prefs.keepAwake(this)) {
            Prefs.setBool(this, "keep_awake", it)
            if (Prefs.liveEnabled(this)) { AlertService.stop(this); AlertService.start(this) }
        })
        b.addView(chk)

        // ── Quiet hours ──────────────────────────────────────
        b.addView(sectionTitle(this, "Quiet hours"))
        val q = card(this)
        q.addView(switchRow(this, "Quiet hours", "Alerts still appear, but without sound", Prefs.quietEnabled(this)) {
            Prefs.setBool(this, "quiet_on", it)
        })
        val times = hbox(this).apply { setPadding(0, dp(6), 0, dp(6)) }
        times.addView(timeButton("From", "quiet_start", Prefs.quietStart(this)), lp(0, WRAP, 1f).margins(r = dp(8)))
        times.addView(timeButton("Until", "quiet_end", Prefs.quietEnd(this)), lp(0, WRAP, 1f))
        q.addView(times, lp())
        q.addView(switchRow(this, "Complaints still ring", "Critical alerts break through quiet hours", Prefs.quietAllowCritical(this)) {
            Prefs.setBool(this, "quiet_critical", it)
        })
        b.addView(q)

        // ── Phone permissions ────────────────────────────────
        b.addView(sectionTitle(this, "Phone permissions"))
        val p = card(this)
        p.addView(tv(this, "For alerts to ring instantly with the screen off, allow these once:", 13.5f, C.MUTED), lp().margins(b = dp(10)))
        p.addView(btn(this, "Allow background running (battery)", BTN_SECONDARY) { MainActivity.askBatteryExemption(this) }, lp().margins(b = dp(8)))
        if (Build.VERSION.SDK_INT >= 34) {
            p.addView(btn(this, "Allow full-screen alarms", BTN_SECONDARY) { MainActivity.askFullScreen(this) }, lp().margins(b = dp(8)))
        }
        p.addView(btn(this, "Notification settings", BTN_SECONDARY) {
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        }, lp().margins(b = dp(8)))
        p.addView(tv(this, "Tecno, Infinix, itel, Samsung, Xiaomi & Oppo phones: also turn on \"Auto-start\" / \"Allow background activity\" for Maptech Admin in the phone's battery settings.",
            12.5f, C.MUTED))
        b.addView(p)

        b.addView(btn(this, "Reset all alert settings to default", BTN_GHOST) { confirmReset() }, lp().margins(t = dp(6)))
        b.addView(creditsCard(this))
    }

    private fun typeCard(t: AlertType): View {
        val c = card(this, if (t.critical) C.RED else null)
        val enabled = Prefs.typeOn(this, t)
        val details = vbox(this).apply { visibility = if (enabled) View.VISIBLE else View.GONE }

        c.addView(switchRow(this, t.label, t.description, enabled) {
            Prefs.setTypeOn(this, t, it)
            details.visibility = if (it) View.VISIBLE else View.GONE
        })

        // Ringtone row
        val toneRow = hbox(this).apply { setPadding(0, dp(8), 0, dp(4)) }
        val toneTexts = vbox(this)
        toneTexts.addView(tv(this, "Ringtone", 12.5f, C.MUTED))
        val toneName = tv(this, Prefs.typeToneName(this, t), 15f, C.GOLD, true)
        toneLabels[t] = toneName
        toneTexts.addView(toneName)
        toneRow.addView(toneTexts, lp(0, WRAP, 1f))
        toneRow.addView(btn(this, "▶", BTN_SECONDARY) { AlertPlayer.play(this, t, preview = true) }, lp(WRAP, WRAP).margins(r = dp(8)))
        toneRow.addView(btn(this, "Change", BTN_SECONDARY) { chooseTone(t) }, lp(WRAP, WRAP))
        details.addView(toneRow, lp())

        details.addView(tv(this, "How long it rings", 12.5f, C.MUTED), lp().margins(t = dp(6)))
        details.addView(spinner(RING_MODES.toList(), Prefs.typeRing(this, t)) { Prefs.setTypeRing(this, t, it) })

        details.addView(switchRow(this, "Vibrate", null, Prefs.typeVibrate(this, t)) { Prefs.setTypeVibrate(this, t, it) })
        details.addView(switchRow(this, "Full-screen alarm", "Wakes the screen and shows over the lock screen", Prefs.typeFullScreen(this, t)) {
            Prefs.setTypeFullScreen(this, t, it)
            if (it && !Notifier.canFullScreen(this)) MainActivity.askFullScreen(this)
        })
        details.addView(btn(this, "Send a test alert", BTN_GHOST) { testAlert(t) }, lp().margins(t = dp(6)))
        c.addView(details, lp())
        return c
    }

    private fun spinner(items: List<String>, selected: Int, onPick: (Int) -> Unit): Spinner {
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(selected.coerceIn(0, items.size - 1), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        sp.layoutParams = lp().margins(b = dp(4))
        return sp
    }

    private fun timeButton(label: String, key: String, minutes: Int): TextView {
        lateinit var b: TextView
        b = btn(this, "$label ${fmtTime(minutes)}", BTN_SECONDARY) {
            val cur = if (key == "quiet_start") Prefs.quietStart(this) else Prefs.quietEnd(this)
            TimePickerDialog(this, { _, h, mi ->
                Prefs.setInt(this, key, h * 60 + mi)
                b.text = "$label ${fmtTime(h * 60 + mi)}"
            }, cur / 60, cur % 60, true).show()
        }
        return b
    }

    private fun fmtTime(m: Int) = String.format(Locale.US, "%02d:%02d", m / 60, m % 60)

    private fun chooseTone(t: AlertType) {
        val keys = BUILTIN_TONES.keys.toList()
        val labels = keys.map { "🔔  " + BUILTIN_TONES[it] } + listOf("📱  Phone ringtones & notification sounds…", "🎵  Pick an audio file / song…", "🔕  Silent (vibrate only)")
        AlertDialog.Builder(this)
            .setTitle("Ringtone for ${t.label}")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < keys.size -> {
                        Prefs.setTypeTone(this, t, keys[which], BUILTIN_TONES[keys[which]] ?: "")
                        toneChanged(t, preview = true)
                    }
                    which == keys.size -> pickSystem(t)
                    which == keys.size + 1 -> pickFile(t)
                    else -> { Prefs.setTypeTone(this, t, "silent", "Silent"); toneChanged(t, preview = false) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickSystem(t: AlertType) {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Ringtone for ${t.label}")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        val cur = Prefs.typeTone(this, t)
        if (!cur.startsWith("builtin:") && cur != "silent" && cur != "default") {
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur))
        }
        try { startActivityForResult(i, REQ_SYSTEM + t.ordinal) } catch (e: Exception) { toast(this, "This phone has no ringtone picker.") }
    }

    private fun pickFile(t: AlertType) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { startActivityForResult(i, REQ_FILE + t.ordinal) } catch (e: Exception) { toast(this, "No file picker available.") }
    }

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode in REQ_SYSTEM until REQ_SYSTEM + 100) {
            val t = AlertType.values().getOrNull(requestCode - REQ_SYSTEM) ?: return
            @Suppress("DEPRECATION")
            val uri: Uri? = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri == null) return
            val name = try { RingtoneManager.getRingtone(this, uri)?.getTitle(this) } catch (e: Exception) { null } ?: "Phone sound"
            if (RingtoneManager.isDefault(uri)) Prefs.setTypeTone(this, t, "default", "Phone default sound")
            else Prefs.setTypeTone(this, t, uri.toString(), name)
            toneChanged(t, preview = true)
        } else if (requestCode in REQ_FILE until REQ_FILE + 100) {
            val t = AlertType.values().getOrNull(requestCode - REQ_FILE) ?: return
            val uri = data.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
            var name = "Audio file"
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) name = cur.getString(0) ?: name
                }
            } catch (e: Exception) { }
            Prefs.setTypeTone(this, t, uri.toString(), name)
            toneChanged(t, preview = true)
        }
    }

    private fun toneChanged(t: AlertType, preview: Boolean) {
        toneLabels[t]?.text = Prefs.typeToneName(this, t)
        if (preview) AlertPlayer.play(this, t, preview = true)
    }

    /** Behaves exactly like a real alert of this type (notification + ringtone + full screen). */
    private fun testAlert(t: AlertType) {
        val e = JSONObject()
            .put("key", "test:${t.key}:${System.currentTimeMillis()}")
            .put("type", t.key)
            .put("ref_id", 0)
            .put("title", "Test — ${t.label}")
            .put("body", "This is how a real alert will look and sound. Tap Silence to stop it.")
        val full = Prefs.typeFullScreen(this, t)
        Notifier.postAlert(this, e, t, full)
        if (Prefs.masterSound(this)) AlertPlayer.play(this, t)
        toast(this, if (full && Notifier.canFullScreen(this)) "Test sent. Lock your phone within a few seconds next time to see the full-screen alarm."
                    else "Test alert sent.")
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset alert settings?")
            .setMessage("All ringtones, durations and switches go back to the defaults.")
            .setPositiveButton("Reset") { _, _ ->
                for (t in AlertType.values()) {
                    Prefs.setTypeOn(this, t, t.defaultOn)
                    Prefs.setTypeTone(this, t, t.defaultTone, BUILTIN_TONES[t.defaultTone] ?: "")
                    Prefs.setTypeRing(this, t, t.defaultRing)
                    Prefs.setTypeVibrate(this, t, t.defaultVibrate)
                    Prefs.setTypeFullScreen(this, t, t.defaultFullScreen)
                }
                Prefs.setInt(this, "volume", 100)
                Prefs.setBool(this, "ring_on_silent", true)
                Prefs.setBool(this, "force_loud", false)
                Prefs.setBool(this, "master_notify", true)
                Prefs.setBool(this, "master_sound", true)
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
