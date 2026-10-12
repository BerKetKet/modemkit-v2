package com.minebutt.modemkit

import android.widget.LinearLayout
import android.widget.Switch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The Settings page. Opened from the gear button in the title bar.
internal fun MainActivity.buildSettings(): LinearLayout {
    val s = vbox()

    val bands = cardView()
    bands.addView(label("Bands", 16f, true))
    bands.addM(note("Theoretical bands are bands the modem supports but nothing around you has shown. " +
        "Off, the Bands page lists only bands seen around you, plus any that are enabled while a lock is on. " +
        "On, it lists every band the modem supports."), 4)
    bands.addM(Switch(this).apply {
        text = "Show theoretical bands"
        textSize = 14f
        isChecked = prefs.getBoolean("show_theory", false)
        setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("show_theory", on).apply() }
    }, 10)
    s.addM(bands, 10)

    val place = cardView()
    place.addView(label("This location", 16f, true))
    place.addM(note("A scan switches every band on for about 45 seconds, so the modem can see what is really around you, " +
        "then puts your current lock back. It asks first each time."), 4)
    val lastText = note("")
    place.addM(lastText, 8)
    val towerText = note("")
    place.addM(towerText, 6)
    val scanBtn = btn("Scan this location", 1) { startLocationScan() }
    place.addM(scanBtn, 8)
    place.addM(Switch(this).apply {
        text = "Show only bands found here"
        textSize = 14f
        isChecked = prefs.getBoolean("show_local", false)
        setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("show_local", on).apply() }
    }, 10)
    live {
        val running = ScanState.running
        scanBtn.isEnabled = !running
        scanBtn.text = if (running) "Scanning, please wait…" else "Scan this location"
        val at = prefs.getLong("local_scan_at", 0L)
        val found = (prefs.getString("local_bands", "") ?: "").split(",").filter { it.isNotBlank() }
        lastText.text = when {
            running -> "A scan is running now."
            at == 0L -> "No scan on this phone yet."
            else -> "Last scan " + SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(at)) + ": " +
                (if (found.isEmpty()) "no bands found." else found.joinToString(", ") { "B$it" })
        }
        val fresh = newTowersSinceScan()
        towerText.text = "Towers this phone has measured: ${towerDb().size}." +
            (if (at != 0L && fresh > 0) " $fresh of them were not there at the last scan." else "")
    }
    s.addM(place, 10)

    return s
}
