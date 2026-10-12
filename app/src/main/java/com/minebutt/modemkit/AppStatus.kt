package com.minebutt.modemkit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

internal enum class AppStatus { GREEN, AMBER, RED, UNKNOWN }

// state: 1 on, 0 off, 2 caution, -1 problem
internal class StatusLine(val state: Int, val text: String)

private fun MainActivity.homeWarnings(): List<Warn> =
    conflicts().filter { Scope.HOME in it.scopes || Scope.CELLS in it.scopes }

internal fun MainActivity.currentAppStatus(): AppStatus {
    if (mon.root == false) return AppStatus.RED
    if (mon.root == null || mon.updated == 0L) return AppStatus.UNKNOWN
    if (System.currentTimeMillis() - mon.updated > 30000) return AppStatus.UNKNOWN
    if (mon.tools == false || mon.modemOk == false) return AppStatus.RED
    if (mon.tools == true && mon.modemOk == null) return AppStatus.UNKNOWN
    val w = homeWarnings()
    if (w.any { it.level >= 2 }) return AppStatus.RED
    if (w.any { it.level == 1 }) return AppStatus.AMBER
    return AppStatus.GREEN
}

internal fun MainActivity.statusTitle(): String = when (currentAppStatus()) {
    AppStatus.GREEN -> "ALL GOOD"
    AppStatus.AMBER -> "CHECK THIS"
    AppStatus.RED -> "ACTION NEEDED"
    AppStatus.UNKNOWN -> "CHECKING…"
}

internal fun MainActivity.statusDetail(): String {
    val st = currentAppStatus()
    if (st == AppStatus.GREEN) return "Everything this app set is still in place. Nothing changed behind its back."
    if (st == AppStatus.UNKNOWN) return "Waiting for root access and the first modem scan."
    val w = homeWarnings().filter { it.level >= 1 }.sortedByDescending { it.level }
    if (w.isEmpty()) return "Something needs attention. See the warnings below."
    return w.first().text + if (w.size > 1) "\n\n+ ${w.size - 1} more below." else ""
}

internal fun MainActivity.statusLines(): List<StatusLine> {
    val out = ArrayList<StatusLine>()
    val root = mon.root
    out.add(StatusLine(if (root == true) 1 else if (root == false) -1 else 0,
        "Root access: " + (if (root == true) "yes" else if (root == false) "NO (allow the app in Magisk)" else "checking")))

    val modemGood = mon.tools == true && mon.modemOk == true
    out.add(StatusLine(if (modemGood) 1 else if (mon.root == true && (mon.tools == false || mon.modemOk == false)) -1 else 0,
        "Helper tools and modem: " + (if (modemGood) "working" else if (mon.tools == false) "tools missing" else if (mon.modemOk == false) "modem not answering" else "checking")))

    val en = enabledBands()
    if (en == null) {
        out.add(StatusLine(0, "Band lock: unknown yet"))
    } else {
        val locked = knownBands().any { it !in en }
        val shown = en.filter { !Radio.isInternal(it) }
        val failed = mon.lockFailed != null
        out.add(StatusLine(if (failed) -1 else if (locked) 1 else 0,
            if (locked) "Band lock: ON, only " + (if (shown.isEmpty()) "no normal band" else Radio.bandList(shown))
            else "Band lock: off (all bands allowed)"))
    }

    val m = mon.mode
    out.add(StatusLine(if (m != null && m != 0x3f && m != 0x3c && m != 0x1c) 1 else 0,
        "Network mode: " + (m?.let { Radio.modeName(it) } ?: "unknown yet")))

    val want = prefs.getString("exp_cell", null)
    if (want == null) {
        out.add(StatusLine(0, "Cell lock: not requested by this app"))
    } else if (want != active.name) {
        out.add(StatusLine(2, "Cell lock: requested for “$want” (a different profile is selected now)"))
    } else {
        val v = lockVerdict()
        out.add(StatusLine(if (v == "OK") 1 else 2,
            if (v == "OK") "Cell lock: HOLDING on ${CellScan.bandLabel(active.earfcn)} ${active.earfcn} / PCI ${active.pci}"
            else "Cell lock: requested for “$want” but NOT holding ($v)"))
    }

    val target = pf("ip")
    val main = mainAddr()
    out.add(StatusLine(
        if (target.isEmpty()) 0 else if (main?.second == target || mon.aliasOn) 1 else 2,
        "Target IP: " + when {
            target.isEmpty() -> "none set"
            main?.second == target -> "active ($target)"
            mon.aliasOn -> "active as an alias ($target)"
            else -> "$target NOT active (now ${main?.second ?: "no address"})"
        }))

    out.add(StatusLine(if (mon.hookOn) 1 else 0,
        "IP hook: " + if (mon.hookOn) "ON, reason ${Reasons.label(mon.hookReason)}" else "off"))

    out.add(StatusLine(if (prefs.getBoolean("guard", true)) 1 else 0,
        "Auto-repair of band lock: " + if (prefs.getBoolean("guard", true)) "on" else "off"))
    out.add(StatusLine(0, "Last scan: " + if (mon.updated == 0L) "never" else hm(mon.updated)))
    return out
}

// One button that does the most useful repair for the first problem found, or null.
internal fun MainActivity.fixAction(): Pair<String, () -> Unit>? {
    if (mon.root != true) return null
    if (mon.tools == false || mon.modemOk == false)
        return "Fix: set up tools again" to { runRoot("Set up tools", "echo setup done", setup = true) }
    val cmd = prefs.getString("exp_cmd", null)
    if (mon.drift.containsKey("bands") && cmd != null)
        return "Fix: put my band lock back" to { runBands("Re-apply band lock", cmd, false, 0, repair = true) }
    val failed = mon.lockFailed
    if (failed != null && cmd != null)
        return "Retry the last band lock" to { runBands("Retry band lock", cmd, false, 0, repair = true) }
    return null
}

// ---- what the app expects, and what changed behind its back ----
internal fun MainActivity.expBands(): List<Int>? =
    prefs.getString("exp_bands", null)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }

internal fun MainActivity.recordExpectation(cmd: String, out: String) {
    val e = prefs.edit()
    val set = "$qband set "
    val mode = "$qband mode "
    if (cmd.contains(set + "all")) {
        e.remove("exp_bands").remove("exp_cmd")
    } else if (cmd.contains(set)) {
        Radio.parseMasks(out)?.let { e.putString("exp_bands", Radio.bandsIn(it).joinToString(",")) }
        e.putString("exp_cmd", cmd)
    }
    if (cmd.contains(mode + "auto")) {
        e.remove("exp_mode")
    } else if (cmd.contains(mode)) {
        Radio.parseMode(out)?.let { e.putInt("exp_mode", it) }
    }
    e.apply()
}

internal fun MainActivity.noteExternal(text: String) {
    mon.external.add("${hm(System.currentTimeMillis())}  $text")
    while (mon.external.size > 20) mon.external.removeAt(0)
    addLog("cell", "Changed outside the app", text)
}

internal fun MainActivity.detectDrift() {
    mon.drift.clear()
    if (ScanState.running) return
    if (mon.root != true) return
    val now = System.currentTimeMillis()
    val grace = busy || now - mon.lastOwnWrite < 20000
    if (grace) return
    val en = enabledBands()
    val eb = expBands()
    if (en != null && eb != null && en.toSet() != eb.toSet()) {
        val tries = prefs.getInt("repairs", 0)
        mon.drift["bands"] = "Your band lock was changed behind the app's back. Expected " +
            Radio.bandList(eb.filter { !Radio.isInternal(it) }) + ", the modem now has " +
            Radio.bandList(en.filter { !Radio.isInternal(it) }) + "." +
            (if (tries >= 3) " Auto-repair tried 3 times and gave up: something keeps undoing it." else "")
        val cmd = prefs.getString("exp_cmd", null)
        if (cmd != null && prefs.getBoolean("guard", true) && mon.tools == true && tries < 3 && now - mon.repairAt > 45000) {
            mon.repairAt = now
            prefs.edit().putInt("repairs", tries + 1).apply()
            addLog("action", "Auto-repair band lock (try ${tries + 1} of 3)", "The modem no longer matched the lock this app applied, so it is being applied again.")
            runBands("Auto-repair band lock", cmd, false, 0, repair = true)
        }
    }
    val em = prefs.getInt("exp_mode", -1)
    val m = mon.mode
    if (m != null && em != -1 && m != em)
        mon.drift["mode"] = "Network mode was changed behind the app's back: expected ${Radio.modeName(em)}, now ${Radio.modeName(m)}."
}

internal fun MainActivity.driftWarnings(): List<Warn> {
    val w = ArrayList<Warn>()
    val s = setOf(Scope.HOME, Scope.BANDS)
    for ((_, text) in mon.drift) w.add(Warn(2, text, s))
    val newTowers = newTowersSinceScan()
    if (newTowers > 0 && !ScanState.running) {
        w.add(Warn(0, "The phone now sees $newTowers tower${if (newTowers == 1) "" else "s"} the last scan did not see. " +
            "Scan this location again to refresh the band list.", setOf(Scope.BANDS)))
    }
    mon.lockFailed?.let { w.add(Warn(2, "The last band lock attempt failed even after a retry ($it). Look at the Logs tab; a carrier setting or another app may be blocking it.", s)) }
    val want = prefs.getString("exp_cell", null)
    if (want != null && want == active.name) {
        val v = lockVerdict()
        if (v != "OK" && !v.startsWith("waiting"))
            w.add(Warn(1, "A cell lock was requested for “$want” but the phone is not on it ($v). Either the PC command has not finished and the phone has not rebooted yet, or it failed.", setOf(Scope.HOME, Scope.CELLS)))
    }
    return w
}

// Everything a tester needs to paste into a bug report.
internal fun MainActivity.diagReport(): String {
    val sb = StringBuilder()
    sb.append("Modem Kit report ${hm(System.currentTimeMillis())}\n")
    sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE}, build ${Build.DISPLAY}\n\n")
    sb.append("STATUS: ${statusTitle()}\n${statusDetail()}\n\n")
    for (l in statusLines()) sb.append("- ${l.text}\n")
    sb.append("\nRoot=${mon.root} tools=${mon.tools} modemOk=${mon.modemOk} ${mon.modemMsg}\n")
    sb.append("Bands now: ${enabledBands()?.let { Radio.bandList(it) }}\nExpected: ${expBands()?.let { Radio.bandList(it) }}\n")
    sb.append("Mode now: ${mon.mode?.let { Radio.modeName(it) }} expected ${prefs.getInt("exp_mode", -1)}\n")
    sb.append("Last lock command: ${prefs.getString("exp_cmd", "-")}\n")
    sb.append("Operator ${mon.oper} / ${mon.net} / APN ${mon.apn} / addrs ${mon.addrs}\n")
    sb.append("Hook on=${mon.hookOn} reason=${mon.hookReason} addr=${mon.hookAddr}\n\nWARNINGS\n")
    for (w in conflicts()) sb.append("[${w.level}] ${w.text}\n")
    sb.append("\nLAST LOG ENTRIES\n")
    for (e in logs.takeLast(30)) sb.append("${hm(e.time)} ${e.kind} ${e.title}: ${e.text.take(300)}\n")
    return sb.toString()
}

internal fun MainActivity.copyReport() {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("modemkit report", diagReport()))
    toast("Report copied")
}
