package com.minebutt.modemkit

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import java.util.Locale

// ---------------- text helpers shared by Home, Bands and Cells ----------------

// Text for qband output: did the command fail?
internal fun bandBad(out: String): Boolean {
    if (out.startsWith("error") || out.contains("dlopen") || out.contains("missing symbol")) return true
    if (out.contains("qmi_client_init_instance failed")) return true
    if (out.contains("no LTE band TLV") || out.contains("not applied within")) return true
    if (out.contains("no mode candidate") || out.contains("bad mode") || out.contains("mode not confirmed")) return true
    if (out.contains("bad band list") || out.contains("no saved original mask")) return true
    val r = Regex("set result=(-?\\d+)").findAll(out).lastOrNull()
    if (r != null && r.groupValues[1] != "0") return true
    return false
}

internal fun MainActivity.signalText(): String {
    val s = mon.snap
    val sv = s?.serving
    var sig: CellScan.Cell? = null
    if (s != null && sv != null) {
        sig = s.cells.firstOrNull { it.earfcn == sv.earfcn && it.pci == sv.pci } ?: s.cells.firstOrNull { it.registered }
    }
    val ms = mon.modem?.serving()
    val rsrp: Double? = sig?.rsrp?.toDouble() ?: ms?.rsrp
    val rsrq: Double? = sig?.rsrq?.toDouble() ?: ms?.rsrq
    if (rsrp == null && rsrq == null) return "n/a"
    val a = if (rsrp != null) String.format(Locale.US, "%.1f", rsrp) else "?"
    val b = if (rsrq != null) String.format(Locale.US, "%.1f", rsrq) else "?"
    return "RSRP $a dBm  RSRQ $b dB  ${Radio.quality(rsrp)}"
}

internal fun MainActivity.bandsBrief(): String {
    val en = enabledBands()
    if (en == null) return if (mon.modemOk == false) "modem did not answer" else "reading…"
    val known = knownBands()
    val locked = known.any { it !in en }
    if (!locked) return "not locked (${en.size} bands on)"
    val shown = en.filter { !Radio.isInternal(it) }
    return "locked to " + (if (shown.isEmpty()) "no normal band" else Radio.bandList(shown))
}

internal fun MainActivity.radioBrief(): String {
    if (mon.root != true) return "Waiting for root access…"
    if (mon.snap == null && mon.modem == null)
        return if (prefs.getBoolean("scan", true)) "Scanning…" else "The radio scan is switched off (tick it under Live monitor)."
    val o = observed()
    val e = o.earfcn
    val p = o.pci
    val sb = StringBuilder()
    if (e == null || p == null) {
        sb.append("Cell       none (no service?)\n")
    } else {
        val b = CellScan.bandNumber(e)
        sb.append("Cell       ${if (b != null) Radio.label(b) else "B?"}  ($e / PCI $p)\n")
        val ci = mon.modem?.ci?.takeIf { it > 0 } ?: mon.snap?.serving?.ci
        if (ci != null) sb.append("Tower      ${ci shr 8} / cell ${ci and 0xFFL}   TAC ${o.tac ?: "?"}\n")
        else sb.append("TAC        ${o.tac ?: "?"}\n")
    }
    sb.append("Signal     ${signalText()}\n")
    sb.append("Bands      ${bandsBrief()}\n")
    sb.append("Mode       ${mon.mode?.let { Radio.modeName(it) } ?: "?"}\n")
    sb.append("Lock check ${lockVerdict()}")
    return sb.toString()
}

internal fun MainActivity.bandStatusText(): String {
    if (mon.root != true) return "Waiting for root access…"
    if (mon.tools != true) return "The helper tools are not installed yet."
    val o = observed()
    val e = o.earfcn
    val serving = if (e == null) "none" else {
        val b = CellScan.bandNumber(e)
        (if (b != null) Radio.label(b) else "B?") + "  ($e / PCI ${o.pci ?: "?"})"
    }
    val perm = if (prefs.getBoolean("perm_radio", false)) "yes (restore with “Keep across reboots” ticked to undo)" else "no"
    return "Bands      ${bandsBrief()}\n" +
        "Mode       ${mon.mode?.let { Radio.modeName(it) } ?: "?"}\n" +
        "Serving    $serving\n" +
        "Permanent lock saved by this app: $perm"
}

internal fun MainActivity.servingText(): String {
    val s = mon.snap
    val m = mon.modem
    if (s == null && m == null) return if (mon.root != true) "Waiting for root access…" else "Scanning…"
    val sv = s?.serving
    val lines = ArrayList<String>()
    val carrier = s?.carrier
    lines.add("Service : ${s?.dataState ?: "?"}" + (if (carrier.isNullOrBlank()) "" else "  ($carrier)"))
    if (sv == null) {
        lines.add("Cell    : none reported by Android")
        if (m != null) lines.add("Modem   : ${CellScan.bandLabel(m.earfcn)} ${m.earfcn} PCI ${m.servingPci}")
    } else {
        val tacText = (sv.tac?.toString() ?: "unknown") + (if (m != null && m.tac != sv.tac) " (modem ${m.tac})" else "")
        lines.add("Cell    : ${CellScan.bandLabel(sv.earfcn)} ${sv.earfcn} (${CellScan.freqLabel(sv.earfcn)})")
        lines.add("PCI     : ${sv.pci}   TAC: $tacText")
        lines.add("ECI     : ${sv.ci ?: "unknown"}")
        sv.ci?.let { lines.add("Tower   : ${it shr 8}  cell ${it and 0xFFL}") }
        val sig = s?.cells?.firstOrNull { it.earfcn == sv.earfcn && it.pci == sv.pci } ?: s?.cells?.firstOrNull { it.registered }
        lines.add("Width   : ${CellScan.bwLabel(sv.bwKhz ?: sig?.bwKhz)}   CA: ${s?.ca?.let { if (it) "yes" else "no" } ?: "?"}")
    }
    lines.add("Signal  : ${signalText()}")
    m?.serving()?.let { lines.add("RSSI    : ${String.format(Locale.US, "%.1f", it.rssi)} dBm") }
    return lines.joinToString("\n")
}

internal fun MainActivity.nearbyText(): String {
    val s = mon.snap
    val m = mon.modem
    val sv = s?.serving
    if (m != null && m.cells.isNotEmpty()) {
        val rows = m.cells.sortedByDescending { it.rsrp }.map { c ->
            val mark = if (c.earfcn == m.earfcn && c.pci == m.servingPci) "*" else " "
            String.format(Locale.US, "%s %-3s %5d PCI %-3d %6.1f dBm %5.1f dB",
                mark, CellScan.bandLabel(c.earfcn), c.earfcn, c.pci, c.rsrp, c.rsrq)
        }
        val extra = if (m.unmeasured.isEmpty()) "" else
            "\nListed, not measured: " + m.unmeasured.joinToString(", ") { "${CellScan.bandLabel(it)} $it" }
        return rows.joinToString("\n") + extra
    }
    val sorted = (s?.cells ?: emptyList()).sortedByDescending { it.rsrp ?: -999 }
    if (sorted.isNotEmpty()) {
        return sorted.joinToString("\n") { c ->
            val mark = if (sv != null && c.earfcn == sv.earfcn && c.pci == sv.pci) "*" else " "
            val tower = c.ci?.let { "  tower ${it shr 8}/${it and 0xFFL}" } ?: ""
            "$mark ${CellScan.bandLabel(c.earfcn)}  ${c.earfcn}  PCI ${c.pci}  RSRP ${c.rsrp ?: "?"} dBm  RSRQ ${c.rsrq ?: "?"} dB$tower"
        }
    }
    return "No cells listed by the modem or Android right now."
}

private fun seenLabel(x: MainActivity.Seen): String {
    val head = Radio.label(x.band) + (if (x.serving) "   (you are here)" else "")
    val tail = if (x.best != null)
        String.format(Locale.US, "best %.1f dBm · %d cell%s", x.best, x.count, if (x.count == 1) "" else "s")
    else "listed by the network, not measured"
    return "$head\n$tail"
}

// Runs a qband command that changes bands or mode, then reads the answer back into the page.
// permEffect: +1 a permanent lock was written, -1 a permanent restore was written, 0 leave the flag alone.
// A set command that never printed "set result=" did not run at all (wrong argv, wrapper problem), which must not count as success.
private fun setNeverRan(cmd: String, out: String) = cmd.contains(" set ") && !out.contains("set result=")

internal fun MainActivity.runBands(title: String, cmd: String, perm: Boolean, permEffect: Int, repair: Boolean = false, tries: Int = 1) {
    runRoot(title, cmd, check = { bandBad(it) || setNeverRan(cmd, it) }) done@{ out ->
        mon.lastOwnWrite = System.currentTimeMillis()
        val bad = bandBad(out) || setNeverRan(cmd, out)
        if (bad && tries > 0) {
            addLog("info", "Retrying: $title", "The first try failed. Trying once more in 3 seconds.")
            resultBox?.text = "✖ $title failed. Retrying once in 3 seconds…"
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runBands(title, cmd, perm, permEffect, repair, tries - 1) }, 3000)
            return@done
        }
        Radio.parseMasks(out)?.let { m ->
            mon.masks = m
            selBands.clear(); selBands.addAll(Radio.bandsIn(m)); selInit = true
        }
        Radio.parseMode(out)?.let { mon.mode = it }
        if (!bad) {
            recordExpectation(cmd, out)
            if (!repair) prefs.edit().putInt("repairs", 0).apply()
            mon.lockFailed = null
            if (perm && permEffect != 0) prefs.edit().putBoolean("perm_radio", permEffect > 0).apply()
            resultBox?.let { it.text = it.text.toString() + "\n\n✔ Checked: the modem now reports ${bandsBrief()}, mode ${mon.mode?.let { m -> Radio.modeName(m) } ?: "?"}." }
        } else {
            mon.lockFailed = title
            resultBox?.let { it.text = it.text.toString() + "\n\n✖ NOT APPLIED, even after a retry. Look at the Logs tab. A carrier setting or another app may be blocking it." }
        }
        syncSel?.invoke()
        refreshLive()
    }
}

// ---------------- Bands ----------------
internal fun MainActivity.buildBands(): View {
    val s = page()
    val act = this
    s.addM(note("Choose which LTE bands and which network type the modem may use. Only bands this modem has reported are listed."), 0)
    s.addM(warnBox(Scope.BANDS), 8)

    val st = cardView()
    st.addView(label("Right now", 16f, true))
    val stText = mono("", 12.5f)
    st.addM(stText, 6)
    live { stText.text = bandStatusText() }
    st.addM(btn("Refresh now") { pollNow() }, 8)
    s.addM(st, 10)

    // tick boxes live here so presets, sets and "seen" buttons can change them
    val boxes = HashMap<Int, CheckBox>()
    fun syncBoxes() {
        for ((n, b) in boxes) {
            val want = n in selBands
            if (b.isChecked != want) b.isChecked = want
        }
    }
    syncSel = { syncBoxes() }
    fun mkBox(n: Int): CheckBox = CheckBox(act).apply {
        text = Radio.label(n)
        isChecked = n in act.selBands
        setOnCheckedChangeListener { _, on ->
            if (on) act.selBands.add(n) else act.selBands.remove(n)
            act.refreshLive()
        }
    }
    fun setSel(list: Collection<Int>) {
        val k = knownBands()
        selBands.clear()
        for (b in list) if (b in k) selBands.add(b)
        syncBoxes()
        refreshLive()
    }

    // bands seen around you
    val seenCard = cardView()
    seenCard.addView(label("Bands seen here", 16f, true))
    seenCard.addM(note("Bands the modem or Android measured around you, or that the network lists. Tap one to tick only that band, then press Apply."), 0)
    val seenBox = vbox()
    seenCard.addM(seenBox, 6)
    var seenKey = "-"
    live {
        val known = knownBands()
        val list = seenBands().filter { it.band in known }
        val key = list.joinToString("|") { "${it.band}:${it.best}:${it.count}:${it.serving}" }
        if (key != seenKey) {
            seenKey = key
            seenBox.removeAllViews()
            if (list.isEmpty()) seenBox.addM(note("Nothing measured yet. Stay on this page for a few seconds."), 0)
            for (x in list) seenBox.addM(btn(seenLabel(x)) {
                selBands.clear(); selBands.add(x.band); syncBoxes(); refreshLive()
            }, 4)
        }
    }
    s.addM(seenCard, 10)

    // choose bands
    val c = cardView()
    c.addView(label("Choose bands", 16f, true))
    c.addM(note("Tick the bands to allow. Presets and saved sets fill the boxes for you; nothing is sent until you press Apply."), 0)
    val presetNames = Radio.presets.keys.toList()
    for (chunk in presetNames.chunked(2)) {
        val bs = chunk.map { name -> btn(name) { setSel(Radio.presets.getValue(name).toList()) } }
        c.addM(row(*bs.toTypedArray()), 4)
    }
    c.addM(row(
        btn("All modem bands") { setSel(knownBands()) },
        btn("Untick all") { setSel(emptyList()) }
    ), 4)

    val known = knownBands()
    val normal = known.filter { !Radio.isInternal(it) }
    val extra = known.filter { Radio.isInternal(it) }
    c.addM(label("Bands the modem reported", 14f, true, Pal.primary), 12)
    val grid = GridLayout(this).apply { columnCount = 2 }
    for (n in normal) {
        val b = mkBox(n)
        boxes[n] = b
        grid.addView(b, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
    }
    c.addView(grid)
    if (extra.isNotEmpty()) {
        c.addM(label("Extra ids the modem lists", 14f, true, Pal.muted), 10)
        c.addM(note("These are not normal bands you choose between. Leave them as they are; Restore puts them back."), 0)
        val g2 = GridLayout(this).apply { columnCount = 2 }
        for (n in extra) {
            val b = mkBox(n)
            boxes[n] = b
            g2.addView(b, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
        }
        c.addView(g2)
    }

    // saved sets
    c.addM(label("Saved sets", 14f, true, Pal.primary), 12)
    val setsBox = vbox()
    c.addM(setsBox, 4)
    fun fillSets() {
        setsBox.removeAllViews()
        val list = BandSets.load(act)
        if (list.isEmpty()) setsBox.addM(note("No saved sets yet. Tick some bands, then tap “Save ticked bands as a set”."), 0)
        for (bs in list) {
            val b = btn("${bs.name}   (${Radio.bandList(bs.bands)})") { setSel(bs.bands) }
            b.setOnLongClickListener {
                confirm("Delete “${bs.name}”?", "This removes the saved set. Your ticked bands do not change.", "Delete") {
                    BandSets.save(act, BandSets.load(act).filter { it.name != bs.name })
                    fillSets()
                }
                true
            }
            setsBox.addM(b, 4)
        }
        if (list.isNotEmpty()) setsBox.addM(note("Long-press a set to delete it."), 4)
    }
    fillSets()
    c.addM(btn("Save ticked bands as a set") {
        if (selBands.isEmpty()) toast("Tick at least one band first") else {
            val inp = EditText(act).apply {
                setSingleLine()
                setText(selBands.filter { !Radio.isInternal(it) }.joinToString("+") { "B$it" }.take(24))
            }
            AlertDialog.Builder(act).setTitle("Save band set as").setView(inp)
                .setPositiveButton("Save") { _, _ ->
                    val name = BandSets.cleanName(inp.text.toString())
                    if (name.isNotEmpty()) {
                        val l = BandSets.load(act).filter { it.name != name }.toMutableList()
                        l.add(BandSets.BandSet(name, selBands.toList()))
                        BandSets.save(act, l)
                        fillSets()
                    }
                }.setNegativeButton("Cancel", null).show()
        }
    }, 6)

    // persistence + actions
    c.addM(permCheck(), 12)
    c.addM(btn("Apply band lock", 1) {
        val sel = selBands.toList()
        if (sel.isEmpty()) toast("Tick at least one band first") else {
            val perm = prefs.getBoolean("perm", false)
            val names = Radio.bandList(sel.filter { !Radio.isInternal(it) }).ifEmpty { "the extra ids only" }
            confirm("Lock to $names?",
                (if (perm) "This is PERMANENT: it stays after a reboot until you restore with “Keep across reboots” ticked.\n\n"
                else "This lasts until the next reboot. Nothing is saved in the modem.\n\n") +
                    "If your carrier does not offer these bands where you are, you will lose service. “Restore original bands” undoes it.", "Apply") {
                runBands("Band lock $names", "$qband set ${sel.joinToString(",")}${if (perm) " perm" else ""}", perm, 1)
            }
        }
    }, 12)
    c.addM(btn("Restore original bands") {
        val perm = prefs.getBoolean("perm", false)
        confirm("Restore original bands?",
            "Sends the bands the modem had before this app first changed them." +
                (if (perm) "\n\n“Keep across reboots” is ticked, so this also overwrites a permanent lock." else ""), "Restore") {
            runBands("Restore original bands", "$qband set all${if (perm) " perm" else ""}", perm, -1)
        }
    }, 6)
    c.addM(btn("Read the modem's raw answer") {
        runRoot("Read bands (raw)", "$qband get", check = { bandBad(it) })
    }, 6)
    s.addM(c, 10)

    // network mode
    val mc = cardView()
    mc.addView(label("Network mode", 16f, true))
    mc.addM(note("Which generations the modem may use. Follows “Keep across reboots” above."), 0)
    fun modeRun(arg: String, name: String) {
        val perm = prefs.getBoolean("perm", false)
        runBands("Network mode: $name", "$qband mode $arg${if (perm) " perm" else ""}", perm, if (arg == "auto") 0 else 1)
    }
    mc.addM(row(
        btn("Auto") { modeRun("auto", "Auto") },
        btn("LTE only") { modeRun("lte", "LTE only") },
        btn("3G / 2G") {
            confirm("Switch LTE off?", "3G / 2G drops LTE, so data will be slow and a tether through this phone will stall until you switch back to Auto.", "Switch") {
                modeRun("3g2g", "3G / 2G")
            }
        }
    ), 8)
    s.addM(mc, 10)

    // panic button
    val rc = cardView()
    rc.addView(label("Something went wrong?", 16f, true))
    rc.addM(note("Sends the original bands and Auto network mode, permanently. Use it if a lock left you without service. A reboot also clears locks that were not permanent."), 0)
    rc.addM(btn("Reset radio to defaults", 2) {
        confirm("Reset the radio?", "All original bands and Auto network mode are written permanently.", "Reset") {
            runBands("Reset radio to defaults", "$qband set all perm; $qband mode auto perm", true, -1)
        }
    }, 8)
    s.addM(rc, 10)

    resultCard(s)
    return s
}

// ---------------- Cells ----------------
private fun MainActivity.efsCommand(p: Profiles.Profile): String {
    val d = "/nv/item_files/modem/lte/rrc/csp"
    val bytes = Profiles.lockBytes(p).split(" ").joinToString("") { "\\x$it" }
    return """echo start
printf '$bytes' > /tmp/pci_lock_app.bin
adb shell su -c "pkill adb_bridge"
(cd /tmp && printf 'rm $d/pci_lock\nmkdir $d\nput pci_lock_app.bin $d/pci_lock\ncat $d/pci_lock\n' | qcsuper --adb --efs-shell)
adb shell su -c "pkill adb_bridge"
sleep 330
adb shell date
adb shell su -c "logcat -v time -d | grep -E 'rmt_storage.*modem_fs[12].*Bytes written' | tail -4"
"""
}

// Undo for the cell lock: removes the pci_lock item (the same rm the lock command starts with). Not run on a phone yet.
private fun MainActivity.efsRemoveCommand(): String {
    val d = "/nv/item_files/modem/lte/rrc/csp"
    return """echo start
adb shell su -c "pkill adb_bridge"
(cd /tmp && printf 'rm $d/pci_lock\n' | qcsuper --adb --efs-shell)
adb shell su -c "pkill adb_bridge"
sleep 330
adb shell date
adb shell su -c "logcat -v time -d | grep -E 'rmt_storage.*modem_fs[12].*Bytes written' | tail -4"
"""
}

internal fun MainActivity.buildCells(): View {
    val s = page()
    val act = this
    s.addM(note("The cell your phone is on, its neighbours, and a check that you are on the cell you want. Nothing here changes the modem unless you press a button in Profiles."), 0)
    s.addM(warnBox(Scope.CELLS), 8)

    val sc = cardView()
    sc.addView(label("Serving cell", 16f, true))
    val lockT = label("", 14f, true)
    sc.addM(lockT, 6)
    val sT = mono("", 12f)
    sc.addM(sT, 6)
    live {
        sT.text = servingText()
        val v = lockVerdict()
        lockT.text = "Lock check: " + (if (v == "OK") "OK  ${CellScan.bandLabel(active.earfcn)} ${active.earfcn} / PCI ${active.pci} / TAC ${active.tac}" else v)
        lockT.setTextColor(if (v == "OK") Pal.ok else if (v.startsWith("waiting")) Pal.muted else Pal.warn)
    }
    s.addM(sc, 10)

    val nc = cardView()
    nc.addView(label("Cells nearby (* = serving)", 16f, true))
    nc.addM(note("Band, channel, sector (PCI), power and quality. Updates every 5 seconds."), 0)
    val nT = mono("", 11f)
    nc.addM(nT, 6)
    live { nT.text = nearbyText() }
    s.addM(nc, 10)

    val pc = cardView()
    pc.addView(label("Profiles (cells you want to stay on)", 16f, true))
    pc.addM(note("Pick the cell this phone should hold. The lock check above follows the active profile."), 0)
    val pStatus = mono("", 12f)
    pc.addM(pStatus, 6)
    val pBox = vbox()
    pc.addM(pBox, 6)
    fun fillProfiles() {
        pStatus.text = "Active profile: ${active.name}\n" +
            "${CellScan.bandLabel(active.earfcn)}  EARFCN ${active.earfcn}  PCI ${active.pci}  TAC ${active.tac}\n" +
            "pci_lock bytes: ${Profiles.lockBytes(active)}"
        pBox.removeAllViews()
        for (p in profiles) {
            val mark = if (p.name == active.name) "> " else "   "
            pBox.addM(btn(mark + "${p.name}  ${CellScan.bandLabel(p.earfcn)} ${p.earfcn}/${p.pci}") {
                selectProfile(p); fillProfiles()
            }, 2)
        }
    }
    fillProfiles()

    val lteBox = CheckBox(this).apply {
        text = "Also set LTE only (no 3G / 2G fallback)"
        isChecked = act.prefs.getBoolean("lte_only", true)
        setOnCheckedChangeListener { _, on -> act.prefs.edit().putBoolean("lte_only", on).apply() }
    }
    pc.addM(lteBox, 10)
    pc.addM(permCheck(), 4)
    pc.addM(btn("Apply band lock for this profile", 1) {
        val band = CellScan.bandNumber(active.earfcn)
        if (band == null) {
            toast("Unknown band for EARFCN ${active.earfcn}. Edit the profile or lock bands by hand on the Bands page.")
        } else {
            val perm = prefs.getBoolean("perm", false)
            val p = if (perm) " perm" else ""
            val cmd = "$qband set $band$p" + (if (lteBox.isChecked) "; $qband mode lte$p" else "")
            confirm("Lock to B$band?",
                (if (perm) "PERMANENT: stays after a reboot until you restore.\n\n" else "Lasts until the next reboot.\n\n") +
                    "This keeps the modem on B$band" + (if (lteBox.isChecked) " and LTE only" else "") +
                    ". It does not choose the tower; for that use the cell lock below.", "Apply") {
                runBands("Band lock for ${active.name}", cmd, perm, 1)
            }
        }
    }, 8)
    pc.addM(btn("Save current cell as a profile") {
        val o = observed()
        val e = o.earfcn
        val p = o.pci
        if (e == null || p == null) toast("No serving cell to save yet") else {
            val tac = o.tac ?: active.tac
            val inp = EditText(act).apply { setSingleLine(); setText("${CellScan.bandLabel(e)} $e/$p") }
            AlertDialog.Builder(act).setTitle("Save current cell as").setView(inp)
                .setPositiveButton("Save") { _, _ ->
                    val name = Profiles.cleanName(inp.text.toString())
                    if (name.isNotEmpty()) {
                        val np = Profiles.Profile(name, e, p, tac)
                        profiles.removeAll { it.name == name }
                        profiles.add(np)
                        Profiles.save(act, profiles)
                        selectProfile(np)
                        fillProfiles()
                    }
                }.setNegativeButton("Cancel", null).show()
        }
    }, 6)
    pc.addM(btn("Delete active profile") {
        if (profiles.size <= 1) toast("Keep at least one profile") else {
            confirm("Delete “${active.name}”?", "The profile is removed. Your modem is not changed.", "Delete") {
                profiles.removeAll { it.name == active.name }
                Profiles.save(act, profiles)
                selectProfile(profiles.first())
                fillProfiles()
            }
        }
    }, 6)
    s.addM(pc, 10)

    val lc = cardView()
    lc.addView(label("Cell lock (exact tower)", 16f, true))
    lc.addM(note("Holds the modem on one cell by writing its pci_lock file. The app cannot do this on the phone; it prepares a command for a computer (qcsuper over adb). After it finishes, check the last line shows a write time after the put, then reboot."), 0)
    lc.addM(btn("Cell lock: copy the PC command", 1) {
        val text = efsCommand(active)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("cell lock command", text))
        prefs.edit().putString("exp_cell", active.name).apply()
        addLog("action", "Cell lock command for ${active.name}", text)
        resultBox?.text = "Cell lock for ${active.name} copied to the clipboard.\nRun it on the PC, then tap Reboot now.\n\n$text"
        toast("Command copied")
    }, 8)
    lc.addM(btn("Remove cell lock: copy the PC command") {
        val text = efsRemoveCommand()
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("remove cell lock command", text))
        prefs.edit().remove("exp_cell").apply()
        addLog("action", "Remove cell lock command", text)
        resultBox?.text = "Command to remove the cell lock copied to the clipboard (untested: it only removes the pci_lock file).\nRun it on the PC, then reboot.\n\n$text"
        toast("Command copied")
    }, 6)
    lc.addM(btn("Reboot now (applies the cell lock)", 2) {
        confirm("Reboot now?", "The cell lock is read by the modem at boot. Reboot only after the write was flushed.", "Reboot") {
            Thread { su("reboot") }.start()
        }
    }, 6)
    s.addM(lc, 10)

    resultCard(s)
    return s
}
