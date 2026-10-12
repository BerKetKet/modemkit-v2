package com.minebutt.modemkit

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// True while a location scan runs. It lives as long as the app process.
internal object ScanState {
    @Volatile var running = false
}

internal val scanUi = Handler(Looper.getMainLooper())
internal const val SCAN_RESTORE = "scan_restore"

// A cell is "earfcn/pci". The modem reports neighbours by these two only, with no tower ID.
internal fun MainActivity.cellKeys(): Set<String> =
    mon.modem?.cells?.filter { it.pci >= 0 }?.map { "${it.earfcn}/${it.pci}" }?.toSet() ?: emptySet()

// Cells this phone has measured. Kept across restarts, newest last, capped at 2000.
// Reads the older "towers" key once, so earlier data is not lost.
internal fun MainActivity.cellDb(): LinkedHashSet<String> {
    val raw = prefs.getString("cells", null) ?: prefs.getString("towers", "") ?: ""
    return LinkedHashSet(raw.split(",").filter { it.isNotBlank() })
}

// Serving towers (eNodeB IDs) this phone has camped on. Only the cell you camp on has a known tower ID.
internal fun MainActivity.servedDb(): LinkedHashSet<String> =
    LinkedHashSet((prefs.getString("served_towers", "") ?: "").split(",").filter { it.isNotBlank() })

// Called on every modem poll: saves the cells measured now, and the tower of the cell you camp on.
internal fun MainActivity.recordCells() {
    val now = cellKeys()
    val db = cellDb()
    if (now.isNotEmpty() && db.addAll(now)) {
        prefs.edit().putString("cells", db.toList().takeLast(2000).joinToString(",")).apply()
    }
    val eci = mon.modem?.ci ?: 0L
    if (eci > 0) {
        val served = servedDb()
        if (served.add((eci shr 8).toString())) {
            prefs.edit().putString("served_towers", served.toList().takeLast(500).joinToString(",")).apply()
        }
    }
}

// How many cells measured now were not seen by the last scan. 0 if no scan has been done.
internal fun MainActivity.newCellsSinceScan(): Int {
    val scanned = prefs.getString("scan_cells", null) ?: prefs.getString("scan_towers", null) ?: return 0
    val before = scanned.split(",").filter { it.isNotBlank() }.toSet()
    return cellKeys().count { it !in before }
}

// Why a scan cannot start right now, or null if it can. Shared by the button and the CLI.
internal fun MainActivity.scanBlocker(): String? {
    if (ScanState.running) return "A scan is already running."
    if (mon.root != true || mon.tools != true) return "Root and the helper tools are needed first. Tap Set up tools on Home."
    if (busy) return "Wait until the current band command has finished."
    if (!prefs.getBoolean("scan", true)) return "The radio scan is switched off. Tick “Live monitor” on Home, then try again."
    if (prefs.getBoolean("perm_radio", false)) return "Turn off “Keep across reboots” on the Bands page first. A scan would undo it."
    val prev = enabledBands()
    if (prev == null || prev.isEmpty()) return "The modem's bands have not been read yet. Wait for the Bands page to load."
    return null
}

private fun MainActivity.restoreCmd(): String =
    "$qband set ${(enabledBands() ?: emptyList()).joinToString(",")}"

// The Scan button: asks first. Nothing changes until the person presses Scan in the dialog.
internal fun MainActivity.startLocationScan() {
    scanBlocker()?.let { toast(it); return }
    val prev = enabledBands() ?: emptyList()
    val restore = restoreCmd()
    val lock = Radio.bandList(prev.filter { !Radio.isInternal(it) }).ifEmpty { "as it is now" }
    confirm("Scan this location?",
        "For about 45 seconds every band the modem supports is switched on, so it can measure what is around you.\n\n" +
        "Your connection may drop and come back during that time. Afterwards your current lock ($lock) is put back.\n\n" +
        "Do not start a scan during a call or a download.",
        "Scan") { beginScan(restore) }
}

// The CLI: "--yes" on the command line is the confirmation, since no dialog can be shown.
internal fun MainActivity.startScanFromCli(): String {
    scanBlocker()?.let { return "Refused: $it" }
    val restore = restoreCmd()
    runOnUiThread { beginScan(restore) }
    return "Scan started. It takes about 60 seconds. When it is done, run './modemkit-cli local'."
}

private fun MainActivity.beginScan(restore: String) {
    ScanState.running = true
    prefs.edit().putString(SCAN_RESTORE, restore).apply()
    addLog("action", "Location scan started", "Every band is switched on for about 45 seconds. The lock is put back afterwards.")
    val seen = HashSet<Int>()
    val cells = HashSet<String>()
    val started = System.currentTimeMillis()
    runBands("Scan: switch all bands on", "$qband set all", false, 0, repair = true)
    fun sample() {
        seen.addAll(seenBands().map { it.band })
        cells.addAll(cellKeys())
        if (System.currentTimeMillis() - started < 45_000) scanUi.postDelayed({ sample() }, 5_000)
        else finishScan(restore, seen, cells)
    }
    scanUi.postDelayed({ sample() }, 8_000)
}

private fun MainActivity.finishScan(restore: String, seen: Set<Int>, cells: Set<String>) {
    val found = seen.filter { !Radio.isInternal(it) }.sorted()
    if (found.isEmpty()) {
        addLog("info", "Location scan found nothing", "No bands were measured, so the earlier result is kept.")
    } else {
        prefs.edit()
            .putString("local_bands", found.joinToString(","))
            .putLong("local_scan_at", System.currentTimeMillis())
            .putString("scan_cells", cells.joinToString(","))
            .apply()
        addLog("action", "Location scan finished", "Bands found here: " + Radio.bandList(found) +
            " (" + cells.size + " cells). Putting the lock back.")
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

private fun MainActivity.onOff(key: String): String = if (prefs.getBoolean(key, false)) "on" else "off"

// Text for "modemkit-cli local".
internal fun MainActivity.scanSummary(): String {
    val at = prefs.getLong("local_scan_at", 0L)
    val found = (prefs.getString("local_bands", "") ?: "").split(",").filter { it.isNotBlank() }
    val when_ = if (at == 0L) "none yet" else SimpleDateFormat("d MMM yyyy HH:mm", Locale.US).format(Date(at))
    return "Last scan: $when_\n" +
        "Bands found here: " + (if (found.isEmpty()) "none" else found.joinToString(", ") { "B$it" }) + "\n" +
        "Show only bands found here: " + onOff("show_local") + "\n" +
        "Show theoretical bands: " + onOff("show_theory") + "\n" +
        "Scan running: " + (if (ScanState.running) "yes" else "no")
}

// Text for "modemkit-cli seen".
internal fun MainActivity.seenReport(): String {
    val cells = cellDb().toList()
    val recent = cells.takeLast(30).reversed().joinToString("\n") {
        "  EARFCN ${it.substringBefore("/")}, PCI ${it.substringAfter("/")}"
    }
    return "Cells measured on this phone: ${cells.size}\n" +
        "Not seen at the last scan: ${newCellsSinceScan()}\n" +
        "Serving towers you have camped on: ${servedDb().size}\n" +
        "Neighbour cells are identified by EARFCN and PCI only, because the modem does not report their tower IDs.\n" +
        (if (cells.isEmpty()) "No cells recorded yet." else "Most recent first (up to 30):\n" + recent)
}
