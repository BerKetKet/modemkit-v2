package com.minebutt.modemkit

import android.os.Handler
import android.os.Looper

// True while a location scan runs. It lives as long as the app process.
internal object ScanState {
    @Volatile var running = false
}

internal val scanUi = Handler(Looper.getMainLooper())
internal const val SCAN_RESTORE = "scan_restore"

// A tower is "earfcn/pci" for each measured cell the modem reports.
internal fun MainActivity.towerKeys(): Set<String> =
    mon.modem?.cells?.filter { it.pci >= 0 }?.map { "${it.earfcn}/${it.pci}" }?.toSet() ?: emptySet()

// Every tower this phone has measured. Kept across restarts, newest last, capped at 2000.
internal fun MainActivity.towerDb(): LinkedHashSet<String> =
    LinkedHashSet((prefs.getString("towers", "") ?: "").split(",").filter { it.isNotBlank() })

// Called on every modem poll: adds whatever is measured now to the database.
internal fun MainActivity.recordTowers() {
    val now = towerKeys()
    if (now.isEmpty()) return
    val db = towerDb()
    if (db.addAll(now)) {
        prefs.edit().putString("towers", db.toList().takeLast(2000).joinToString(",")).apply()
    }
}

// How many towers measured now were not seen by the last scan. 0 if no scan has been done.
internal fun MainActivity.newTowersSinceScan(): Int {
    val scanned = prefs.getString("scan_towers", null) ?: return 0
    val before = scanned.split(",").filter { it.isNotBlank() }.toSet()
    return towerKeys().count { it !in before }
}

// Step 1: ask. Nothing changes until the person presses Scan in the dialog.
internal fun MainActivity.startLocationScan() {
    if (ScanState.running) { toast("A scan is already running"); return }
    if (mon.root != true || mon.tools != true) {
        toast("Root and the helper tools are needed first. Tap Set up tools on Home."); return
    }
    if (busy) { toast("Wait until the current band command has finished"); return }
    if (!prefs.getBoolean("scan", true)) {
        toast("The radio scan is switched off. Tick “Live monitor” on Home, then try again."); return
    }
    if (prefs.getBoolean("perm_radio", false)) {
        toast("Turn off “Keep across reboots” on the Bands page first. A scan would undo it."); return
    }
    val prev = enabledBands()
    if (prev == null || prev.isEmpty()) {
        toast("The modem's bands have not been read yet. Wait for the Bands page to load."); return
    }
    val restore = "$qband set ${prev.joinToString(",")}"
    val lock = Radio.bandList(prev.filter { !Radio.isInternal(it) }).ifEmpty { "as it is now" }
    confirm("Scan this location?",
        "For about 45 seconds every band the modem supports is switched on, so it can measure what is around you.\n\n" +
        "Your connection may drop and come back during that time. Afterwards your current lock ($lock) is put back.\n\n" +
        "Do not start a scan during a call or a download.",
        "Scan") { beginScan(restore) }
}

private fun MainActivity.beginScan(restore: String) {
    ScanState.running = true
    prefs.edit().putString(SCAN_RESTORE, restore).apply()
    addLog("action", "Location scan started", "Every band is switched on for about 45 seconds. The lock is put back afterwards.")
    val seen = HashSet<Int>()
    val towers = HashSet<String>()
    val started = System.currentTimeMillis()
    runBands("Scan: switch all bands on", "$qband set all", false, 0, repair = true)
    fun sample() {
        seen.addAll(seenBands().map { it.band })
        towers.addAll(towerKeys())
        if (System.currentTimeMillis() - started < 45_000) scanUi.postDelayed({ sample() }, 5_000)
        else finishScan(restore, seen, towers)
    }
    scanUi.postDelayed({ sample() }, 8_000)
}

private fun MainActivity.finishScan(restore: String, seen: Set<Int>, towers: Set<String>) {
    val found = seen.filter { !Radio.isInternal(it) }.sorted()
    if (found.isEmpty()) {
        addLog("info", "Location scan found nothing", "No bands were measured, so the earlier result is kept.")
    } else {
        prefs.edit()
            .putString("local_bands", found.joinToString(","))
            .putLong("local_scan_at", System.currentTimeMillis())
            .putString("scan_towers", towers.joinToString(","))
            .apply()
        addLog("action", "Location scan finished", "Bands found here: " + Radio.bandList(found) +
            " (" + towers.size + " towers). Putting the lock back.")
    }
    runBands("Scan: put the lock back", restore, false, 0, repair = true)
    scanUi.postDelayed({
        ScanState.running = false
        prefs.edit().remove(SCAN_RESTORE).apply()
        refreshLive()
    }, 6_000)
}

// If the app was closed during a scan, put the lock back the next time it starts.
internal fun MainActivity.recoverScan() {
    if (ScanState.running) return
    val cmd = prefs.getString(SCAN_RESTORE, null) ?: return
    addLog("action", "Putting the band lock back", "The app closed during a scan. Restoring the lock it had before.")
    runBands("Scan recovery: put the lock back", cmd, false, 0, repair = true)
    prefs.edit().remove(SCAN_RESTORE).apply()
}
