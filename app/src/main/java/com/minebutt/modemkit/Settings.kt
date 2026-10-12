package com.minebutt.modemkit

import android.widget.LinearLayout
import android.widget.Switch

// The Settings page. Opened from the gear button in the title bar.
internal fun MainActivity.buildSettings(): LinearLayout {
    val s = vbox()

    val bands = cardView()
    bands.addView(label("Bands", 16f, true))
    bands.addM(note("Theoretical bands are bands the modem supports but nothing around you has shown. " +
        "Off, the Bands page lists only the bands you can see, plus any that are enabled or ticked. " +
        "On, it lists every band the modem supports."), 4)
    bands.addM(Switch(this).apply {
        text = "Show theoretical bands"
        textSize = 14f
        isChecked = prefs.getBoolean("show_theory", false)
        setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("show_theory", on).apply() }
    }, 10)
    s.addM(bands, 10)

    return s
}
