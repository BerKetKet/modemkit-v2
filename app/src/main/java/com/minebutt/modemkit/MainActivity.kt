package com.minebutt.modemkit

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TreeSet

class Warn(val level: Int, val text: String, val scopes: Set<Int>)
class LogEntry(val time: Long, val kind: String, val title: String, val text: String)
class Obs(val earfcn: Int?, val pci: Int?, val tac: Int?)

// Which page a warning belongs to.
object Scope {
    const val HOME = 0
    const val BANDS = 1
    const val CELLS = 2
    const val KEEP = 3
    const val MODEM = 4
    const val HOOK = 5
}

class Mon {
    var root: Boolean? = null
    var tools: Boolean? = null
    var addrs: List<Pair<String, String>> = emptyList()
    var oper = ""; var net = ""; var data = -1; var apn = ""
    var hookOn = false; var hookReason = ""; var hookAddr = ""; var hookApn = ""
    var hookSeen = false; var aliasOn = false
    var lastMain = ""; var ipSince = System.currentTimeMillis(); var changes = 0; var updated = 0L

    // radio
    var snap: CellScan.Snapshot? = null      // what Android reports
    var modem: CellScan.ModemInfo? = null    // what the modem reports (qband cell)
    var masks: LongArray? = null            // bands enabled right now
    var mode: Int? = null                   // network mode now
    var modemOk: Boolean? = null            // did qband answer
    var modemMsg = ""
    var origMasks: LongArray? = null        // the saved baseline
    var radioUpdated = 0L

    // last values, to write a log line only when something changes
    var haveLast = false
    var lastCell: String? = null
    var lastTac: Int? = null
    var lastData: String? = null
    var lastCa: Boolean? = null
    var lastLock: String? = null
    var lastBands: String? = null
    var lastMode: Int? = null

    // changes made behind the app's back, and the app's own recent writes
    val external = ArrayList<String>()
    val drift = LinkedHashMap<String, String>()
    var lastOwnWrite = 0L
    var lockFailed: String? = null
    var repairAt = 0L
}

class MainActivity : Activity() {
    companion object {
        var instance: MainActivity? = null
    }

    internal val dir = "/data/local/tmp"
    internal val qwds = "timeout 60 $dir/qwds_run"
    internal val qband = "timeout 60 $dir/qband_run"
    private val qbandCell = "timeout 10 $dir/qband_run cell 2>&1"
    internal val prefs: SharedPreferences by lazy { getSharedPreferences("modemkit", Context.MODE_PRIVATE) }
    internal val mon = Mon()
    internal val logs = ArrayList<LogEntry>()
    internal var resultBox: TextView? = null
    internal var busy = false

    // Bands ticked on the Bands page. Lives here so it survives switching pages.
    internal val selBands = TreeSet<Int>()
    internal var selInit = false
    internal var ipSub = 0
    internal var syncSel: (() -> Unit)? = null   // set by the Bands page so a finished command can refresh its tick boxes

    // Cell profiles
    internal var profiles = mutableListOf<Profiles.Profile>()
    internal lateinit var active: Profiles.Profile

    private val handler = Handler(Looper.getMainLooper())
    private val updaters = ArrayList<() -> Unit>()
    private val rmnetRe = Regex("""(rmnet\S*)\s+inet (\d+\.\d+\.\d+\.\d+)""")
    private val titles = listOf("Home", "Bands", "Cells", "IP", "Logs")
    private val ipTitles = listOf("Keep my IP", "Ask the modem", "Android hook")
    private val tabLabels = listOf("🏠\nHome", "📶\nBands", "🗼\nCells", "🌐\nIP", "📋\nLogs")
    private val tabViews = ArrayList<TextView>()
    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private var cur = 0
    private var pollCount = 0
    private var polling = false
    private var autoSetupTried = false
    @Suppress("DEPRECATION")
    private val toolsVer: Int get() = try { packageManager.getPackageInfo(packageName, 0).versionCode } catch (e: Exception) { 0 }

    // ---- small helpers ----
    internal fun pf(k: String, def: String = ""): String = prefs.getString("f_$k", def) ?: def
    internal fun setPf(k: String, v: String) { prefs.edit().putString("f_$k", v).apply() }
    internal fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    internal fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    internal fun live(f: () -> Unit) { updaters.add(f); f() }
    internal fun refreshLive() { updaters.toList().forEach { it() } }

    internal fun confirm(title: String, msg: String, yes: String = "Continue", onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }.setNegativeButton("Cancel", null).show()
    }

    internal fun su(cmd: String, seconds: Long = 70): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val buf = StringBuilder()
        val t = Thread { try { buf.append(p.inputStream.bufferedReader().readText()) } catch (_: Exception) {} }
        t.start()
        t.join(seconds * 1000)
        if (t.isAlive) {
            p.destroyForcibly()
            "error: timed out after ${seconds}s\n" + synchronized(buf) { buf.toString() }
        } else {
            p.waitFor()
            buf.toString()
        }
    } catch (e: Exception) { "error: ${e.message}" }

    private val atSh = "#!/system/bin/sh\n: > $dir/at.out\ncat \"\$1\" > $dir/at.out 2>/dev/null &\np=\$!\n" +
        "printf '%s\\r' \"\$2\" > \"\$1\"\nsleep \${3:-2}\nkill \$p 2>/dev/null\ncat $dir/at.out\n"

    internal fun prepare(): String {
        val rep = StringBuilder()
        for (n in listOf("qwds", "qband", "qwds_run", "qband_run")) {
            try {
                val tmp = File(cacheDir, n)
                assets.open(n).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                rep.append("$n: found inside the app (${tmp.length()} bytes)\n")
                rep.append(su("cp ${tmp.absolutePath} $dir/$n 2>&1; chmod 755 $dir/$n 2>&1; ls -l $dir/$n 2>&1").trim()).append("\n")
            } catch (e: Exception) {
                rep.append("$n: NOT inside the app (${e.message}). Run build_native.sh, then rebuild the app.\n")
            }
        }
        try {
            val at = File(cacheDir, "at.sh").apply { writeText(atSh) }
            val setupCmd = "mkdir -p $dir/q 2>&1; " +
                "for f in /vendor/lib64/libqmi*.so /vendor/lib64/libdiag.so /vendor/lib64/libdsutils.so /vendor/lib64/libidl.so /vendor/lib64/libmdmdetect.so; do " +
                "[ -e \$f ] && cp \$f $dir/q/ 2>&1; done; " +
                "chmod 644 $dir/q/*.so 2>&1; " +
                "cp ${at.absolutePath} $dir/at.sh 2>&1; chmod 755 $dir/at.sh 2>&1; " +
                "echo \"modem libraries copied: \$(ls $dir/q | wc -l)\""
            rep.append(su(setupCmd).trim()).append("\n")
        } catch (e: Exception) { rep.append("helper script failed: ${e.message}") }
        try {
            val tmp = File(cacheDir, "deps_resolve")
            assets.open("deps_resolve").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            rep.append(su("cp ${tmp.absolutePath} $dir/deps_resolve 2>&1; chmod 700 $dir/deps_resolve 2>&1; /system/bin/sh $dir/deps_resolve $dir 2>&1").trim()).append("\n")
        } catch (e: Exception) {
            rep.append("deps_resolve: NOT inside the app (${e.message}).\n")
        }
        return rep.toString().trim()
    }

    // ---- logs ----
    internal fun addLog(kind: String, title: String, text: String) {
        logs.add(LogEntry(System.currentTimeMillis(), kind, title, text.take(2500)))
        while (logs.size > 200) logs.removeAt(0)
        saveLogs()
    }

    internal fun saveLogs() {
        val a = JSONArray()
        for (e in logs) a.put(JSONObject().put("t", e.time).put("k", e.kind).put("n", e.title).put("x", e.text))
        prefs.edit().putString("logs", a.toString()).apply()
    }

    private fun loadLogs() {
        try {
            val a = JSONArray(prefs.getString("logs", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                logs.add(LogEntry(o.getLong("t"), o.getString("k"), o.getString("n"), o.getString("x")))
            }
        } catch (e: Exception) { /* ignore a corrupt log */ }
    }

    // Runs a root command, logs it, and shows a short summary in the page's result box.
    // check: decides if the output means a failure (default: looks for the usual failure words).
    internal fun runRoot(
        title: String, cmd: String, setup: Boolean = false, kind: String = "result",
        check: ((String) -> Boolean)? = null, done: ((String) -> Unit)? = null
    ) {
        if (busy) { toast("Please wait, another action is still running"); return }
        busy = true
        resultBox?.text = "Working…  $title"
        addLog("action", title, cmd.take(300))
        Thread {
            val pre = if (setup) prepare() + "\n" else ""
            val out = (pre + su(cmd)).trim()
            runOnUiThread {
                busy = false
                val bad = if (check != null) check(out) else
                    out.startsWith("error") || out.contains("FAILED") || out.contains("failed") || out.contains("No such file")
                addLog(if (bad) "error" else kind, title, out.ifEmpty { "(no output)" })
                val head = out.lines().take(12).joinToString("\n")
                resultBox?.text = (if (bad) "✖ " else "✔ ") + title + "\n" + head +
                    (if (out.lines().size > 12) "\n… full output in the Logs tab" else "")
                done?.invoke(out)
                pollNow()
            }
        }.start()
    }

    // ---- saved IPs ----
    internal fun hist(): MutableList<List<String>> =
        (prefs.getString("hist", "") ?: "").lines().filter { it.isNotBlank() }.map { it.split("|") }.filter { it.size == 3 }.toMutableList()

    private fun noteIp(iface: String, ip: String) {
        val h = hist()
        if (h.lastOrNull()?.get(0) == ip) return
        h.add(listOf(ip, System.currentTimeMillis().toString(), iface))
        prefs.edit().putString("hist", h.takeLast(30).joinToString("\n") { it.joinToString("|") }).apply()
    }

    internal fun mainAddr(): Pair<String, String>? {
        val want = pf("iface")
        return mon.addrs.firstOrNull { it.first == want } ?: mon.addrs.firstOrNull()
    }

    // ---- bands the modem has shown ----
    internal fun knownBands(): List<Int> {
        val s = TreeSet<Int>()
        for (b in Radio.defaultBands) s.add(b)
        (prefs.getString("known", "") ?: "").split(",").mapNotNullTo(s) { it.trim().toIntOrNull() }
        return s.filter { it in 1..Radio.MAX_BAND }
    }

    private fun learnBands(found: List<Int>) {
        val have = knownBands()
        val add = found.filter { it in 1..Radio.MAX_BAND && it !in have }
        if (add.isEmpty()) return
        val all = TreeSet<Int>(have)
        all.addAll(add)
        prefs.edit().putString("known", all.joinToString(",")).apply()
    }

    internal fun enabledBands(): List<Int>? = mon.masks?.let { Radio.bandsIn(it) }

    // ---- what the radio sees ----
    internal fun observed(): Obs {
        val m = mon.modem
        val sv = mon.snap?.serving
        val tac = if (m != null && m.tac > 0) m.tac else sv?.tac
        return Obs(m?.earfcn ?: sv?.earfcn, m?.servingPci ?: sv?.pci, tac)
    }

    internal fun lockVerdict(): String {
        val o = observed()
        val e = o.earfcn
        val p = o.pci
        val t = o.tac
        return when {
            mon.snap == null && mon.modem == null -> "waiting for the first scan"
            e == null || p == null -> "no serving cell (service lost?)"
            e != active.earfcn || p != active.pci -> "WRONG CELL ${CellScan.bandLabel(e)} $e / PCI $p"
            t != null && t != active.tac -> "cell ok, but TAC $t (expected ${active.tac})"
            else -> "OK"
        }
    }

    internal fun selectProfile(p: Profiles.Profile) {
        active = p
        Profiles.setActive(this, p.name)
        mon.lastLock = null
        refreshLive()
    }

    // ---- live monitor ----
    internal fun pollNow() {
        if (polling) return
        polling = true
        val heavy = pollCount++ % 6 == 0
        // The radio scan runs only while a radio page is open, and never while a command is running.
        val scan = mon.root == true && !busy && prefs.getBoolean("scan", true)
        val modemQ = scan && mon.tools == true
        val sb = StringBuilder()
        sb.append("echo @@root; id -u\n")
        sb.append("echo @@addr; ip -o -4 addr show | grep rmnet\n")
        sb.append("echo @@net; getprop gsm.operator.alpha; getprop gsm.network.type\n")
        sb.append("echo @@data; dumpsys telephony.registry | grep -m1 -oE 'mDataConnectionState=-?[0-9]+'\n")
        sb.append("echo @@hook; getprop debug.modemkit.on; getprop debug.modemkit.reason; getprop debug.modemkit.addr; getprop debug.modemkit.apn\n")
        sb.append("echo @@tools; [ -x $dir/qwds ] && [ -x $dir/qband ] && [ -x $dir/qband_run ] && [ -x $dir/qwds_run ] && [ -d $dir/q ] && echo yes || echo no\n")
        if (heavy) {
            sb.append("echo @@apn; content query --uri content://telephony/carriers/preferapn --projection apn 2>/dev/null\n")
            sb.append("echo @@seen; logcat -d -s MODEMKIT:I | tail -n 5\n")
            sb.append("echo @@orig; cat $dir/qband.orig 2>/dev/null\n")
        }
        if (scan) sb.append("echo @@reg; dumpsys telephony.registry | grep -E 'mServiceState=|mCellIdentity=|mCellInfo='\n")
        if (modemQ) sb.append("echo @@modem; $qbandCell\n")
        Thread {
            val out = su(sb.toString())
            runOnUiThread { parseMon(out) }
        }.start()
    }

    private fun parseMon(out: String) {
        polling = false
        var sec = ""
        val addrs = ArrayList<Pair<String, String>>()
        val net = ArrayList<String>()
        val hook = ArrayList<String>()
        val reg = StringBuilder()
        val modemOut = StringBuilder()
        val orig = StringBuilder()
        var root = false
        var tools: Boolean? = mon.tools
        for (line in out.lines()) {
            if (line.startsWith("@@")) { sec = line; continue }
            when (sec) {
                "@@root" -> if (line.trim() == "0") root = true
                "@@addr" -> rmnetRe.find(line)?.let { addrs.add(it.groupValues[1] to it.groupValues[2]) }
                "@@net" -> net.add(line.trim())
                "@@data" -> Regex("mDataConnectionState=(-?\\d+)").find(line)?.let { mon.data = it.groupValues[1].toInt() }
                "@@hook" -> hook.add(line.trim())
                "@@tools" -> if (line.trim() == "yes") tools = true else if (line.trim() == "no") tools = false
                "@@apn" -> Regex("apn=([^,\\s]+)").find(line)?.let { mon.apn = it.groupValues[1] }
                "@@seen" -> if (line.contains("setupDataCall")) mon.hookSeen = true
                "@@orig" -> orig.append(line).append(' ')
                "@@reg" -> reg.append(line).append('\n')
                "@@modem" -> modemOut.append(line).append('\n')
            }
        }
        mon.root = root
        mon.tools = if (root) tools else mon.tools
        mon.addrs = addrs
        mon.oper = net.getOrElse(0) { "" }
        mon.net = net.getOrElse(1) { "" }
        mon.hookOn = hook.getOrElse(0) { "" } == "1"
        mon.hookReason = hook.getOrElse(1) { "" }
        mon.hookAddr = hook.getOrElse(2) { "" }
        mon.hookApn = hook.getOrElse(3) { "" }
        val main = mainAddr()
        if (main != null) {
            if (pf("iface").isEmpty()) setPf("iface", main.first)
            if (main.second != mon.lastMain) {
                if (mon.lastMain.isNotEmpty()) {
                    mon.changes++
                    addLog("info", "IP changed", "${mon.lastMain}  →  ${main.second}")
                }
                mon.lastMain = main.second
                mon.ipSince = System.currentTimeMillis()
                noteIp(main.first, main.second)
            }
        }
        val target = pf("ip")
        mon.aliasOn = target.isNotEmpty() && main?.second != target && addrs.any { it.second == target }
        mon.updated = System.currentTimeMillis()

        if (orig.isNotBlank()) Radio.parseOrig(orig.toString())?.let { mon.origMasks = it; learnBands(Radio.bandsIn(it)) }
        if (out.contains("@@reg")) {
            mon.snap = CellScan.parse(reg.toString())
            mon.radioUpdated = mon.updated
        }
        if (out.contains("@@modem")) {
            val mo = modemOut.toString()
            val masks = Radio.parseMasks(mo)
            mon.modemOk = masks != null
            mon.modemMsg = if (masks == null) mo.trim().lines().take(3).joinToString(" | ") else ""
            if (masks != null) {
                mon.masks = masks
                learnBands(Radio.bandsIn(masks))
                if (!selInit) { selBands.clear(); selBands.addAll(Radio.bandsIn(masks)); selInit = true }
            }
            Radio.parseMode(mo)?.let { mon.mode = it }
            mon.modem = CellScan.parseModem(mo)
        }
        if (out.contains("@@reg") || out.contains("@@modem")) trackRadio()

        // The helper tools install themselves once, as soon as root works and they are missing.
        if (root && (tools == false || prefs.getInt("tools_ver", 0) != toolsVer) && !autoSetupTried && !busy) {
            autoSetupTried = true
            runRoot("Set up tools (automatic)", "echo setup done", setup = true, done = { prefs.edit().putInt("tools_ver", toolsVer).apply() })
        }
        detectDrift()
        updaters.forEach { it() }
    }

    // Writes a log line when the serving cell, TAC, data state, CA flag, bands or mode change.
    private fun trackRadio() {
        val snap = mon.snap ?: return
        val o = observed()
        val e = o.earfcn
        val p = o.pci
        val cell = if (e != null && p != null) "${CellScan.bandLabel(e)} $e/$p" else null
        val eci = snap.serving?.ci
        val verdict = lockVerdict()
        val bands = enabledBands()?.let { Radio.bandList(it) }
        val own = busy || System.currentTimeMillis() - mon.lastOwnWrite < 25000
        if (!mon.haveLast) {
            mon.haveLast = true
            val sb0 = prefs.getString("last_bands", null)
            if (bands != null && sb0 != null && sb0 != bands) noteExternal("While the app was closed, the bands changed:\n$sb0\n→ $bands")
            val sm0 = prefs.getInt("last_mode", -1)
            if (mon.mode != null && sm0 != -1 && sm0 != mon.mode) noteExternal("While the app was closed, the network mode changed: ${Radio.modeName(sm0)} → ${Radio.modeName(mon.mode!!)}")
            addLog("cell", "Cell scan started", "${snap.dataState ?: "?"}, cell ${cell ?: "none"}, TAC ${o.tac ?: "unknown"}")
        } else {
            if (cell != mon.lastCell) addLog("cell", "Cell changed", "${mon.lastCell ?: "none"}  →  ${cell ?: "none"}" + (if (eci != null) "  (ECI $eci)" else ""))
            if (o.tac != mon.lastTac) addLog("cell", "TAC changed", "${mon.lastTac ?: "unknown"}  →  ${o.tac ?: "unknown"}")
            if (snap.dataState != mon.lastData) addLog("cell", "Data state", "${mon.lastData ?: "?"}  →  ${snap.dataState ?: "?"}")
            if (snap.ca != mon.lastCa) addLog("cell", "Carrier aggregation", "${mon.lastCa ?: "?"}  →  ${snap.ca ?: "?"}")
            if (verdict != mon.lastLock) addLog("cell", "Lock check", verdict)
            if (bands != null && bands != mon.lastBands && mon.lastBands != null) {
                addLog("cell", "Bands changed", "${mon.lastBands}\n→ $bands")
                if (!own) noteExternal("Bands changed without this app doing it (another app, a PC command, a reboot or the modem):\n${mon.lastBands}\n→ $bands")
            }
            if (mon.mode != mon.lastMode && mon.lastMode != null) {
                addLog("cell", "Network mode changed", "${mon.lastMode?.let { Radio.modeName(it) }}  →  ${mon.mode?.let { Radio.modeName(it) }}")
                if (!own) noteExternal("Network mode changed without this app doing it: ${mon.lastMode?.let { Radio.modeName(it) }} → ${mon.mode?.let { Radio.modeName(it) }}")
            }
        }
        mon.lastCell = cell
        mon.lastTac = o.tac
        mon.lastData = snap.dataState
        mon.lastCa = snap.ca
        mon.lastLock = verdict
        if (bands != null) { mon.lastBands = bands; prefs.edit().putString("last_bands", bands).apply() }
        if (mon.mode != null) { mon.lastMode = mon.mode; prefs.edit().putInt("last_mode", mon.mode!!).apply() }
    }

    // ---- conflict checker ----
    internal fun conflicts(): List<Warn> {
        val w = ArrayList<Warn>()
        val all = setOf(Scope.HOME, Scope.BANDS, Scope.CELLS, Scope.KEEP, Scope.MODEM, Scope.HOOK)
        val needTools = setOf(Scope.HOME, Scope.BANDS, Scope.CELLS, Scope.MODEM)
        val radio = setOf(Scope.HOME, Scope.BANDS, Scope.CELLS)
        val bandsHome = setOf(Scope.HOME, Scope.BANDS)
        val keepHome = setOf(Scope.HOME, Scope.KEEP)
        val hookHome = setOf(Scope.HOME, Scope.HOOK)

        if (mon.root == false) w.add(Warn(2, "No root access. Open Magisk, allow “Modem Kit” in Superuser, then come back.", all))
        if (mon.root == true && mon.tools == false) w.add(Warn(1, "The helper tools are not installed on the phone yet. They install themselves; if this stays, tap “Set up tools” on Home.", needTools))
        if (mon.root == true && mon.tools == true && mon.modemOk == false)
            w.add(Warn(1, "The modem did not answer the band query" + (if (mon.modemMsg.isNotEmpty()) " (${mon.modemMsg})" else "") + ". Tap “Set up tools” on Home, look at the Logs tab, or restart the phone.", radio))
        if (mon.root == true && mon.data == 0) w.add(Warn(0, "Mobile data is disconnected right now.", keepHome))
        val target = pf("ip")
        val main = mainAddr()
        if (target.isNotEmpty() && main?.second == target) w.add(Warn(0, "You already have your target IP, nothing to do.", keepHome))
        if (mon.root == true && target.isNotEmpty() && main?.second != target && !mon.aliasOn)
            w.add(Warn(1, "Your target IP $target is NOT active. The phone has ${main?.second ?: "no address"}. Nothing this app did has put the target in place yet.", keepHome))

        // ---- bands and mode ----
        val enabled = enabledBands()
        val known = knownBands()
        val permFlag = prefs.getBoolean("perm_radio", false)
        if (enabled != null && enabled.isNotEmpty()) {
            val locked = known.any { it !in enabled }
            if (locked) {
                val shown = enabled.filter { !Radio.isInternal(it) }
                w.add(Warn(0, "Bands are limited to ${if (shown.isEmpty()) "none of the normal bands" else Radio.bandList(shown)}. " +
                    (if (permFlag) "A permanent lock was saved, so it stays after a reboot. " else "It disappears at the next reboot. ") +
                    "Use “Restore original bands” on the Bands page when you are done.", bandsHome))
            } else if (permFlag) {
                w.add(Warn(1, "All bands are on right now, but a permanent lock was saved earlier. It comes back after a reboot until you restore with “Keep across reboots” ticked.", bandsHome))
            }
        }
        val m = mon.mode
        if (m != null && m == 0x10)
            w.add(Warn(1, "Network mode is LTE only: if LTE is weak there is no fallback to 3G or 2G, so calls and data may drop.", bandsHome))
        if (m != null && (m and 0x10) == 0)
            w.add(Warn(2, "LTE is switched off in the network mode, so mobile data will be slow or missing. Choose Auto or LTE only on the Bands page.", bandsHome))
        if (selInit) {
            if (selBands.isEmpty()) w.add(Warn(1, "No band is ticked. Tick at least one before applying.", setOf(Scope.BANDS)))
            else {
                val seen = seenBands().map { it.band }.toSet()
                if (seen.isNotEmpty() && selBands.none { it in seen })
                    w.add(Warn(2, "None of the ticked bands were seen around you. If you apply this you will probably lose service.", setOf(Scope.BANDS)))
                if (enabled != null && enabled.toSet() != selBands.toSet())
                    w.add(Warn(0, "Your ticked bands are not applied yet. Press “Apply band lock”.", setOf(Scope.BANDS)))
            }
        }
        val pb = CellScan.bandNumber(active.earfcn)
        if (enabled != null && pb != null && pb !in enabled)
            w.add(Warn(if (prefs.getString("exp_cell", null) == active.name) 2 else 0, "Cell profile “${active.name}” is on B$pb, but B$pb is not enabled in the modem, so it cannot hold that cell. Enable it on the Bands page.", setOf(Scope.BANDS, Scope.CELLS)))

        // ---- IP routes ----
        if (mon.hookOn) {
            when (mon.hookReason) {
                "2" -> w.add(Warn(2, "The hook is sending SHUTDOWN: Android tells the modem to close the connection instead of opening one, so mobile data will probably stay off. Choose Normal or Handover.", hookHome))
                "3" -> w.add(Warn(1, "Handover says “this connection already exists elsewhere”. If it does not, the network may refuse and mobile data may not return until you switch the hook off.", hookHome))
            }
            if (mon.hookAddr.isNotEmpty() && mon.hookReason != "3")
                w.add(Warn(1, "An address is being sent but the reason is not Handover. Android only passes the address along with Handover, so it will likely be ignored.", hookHome))
            if (mon.hookAddr.isEmpty() && mon.hookReason.isEmpty())
                w.add(Warn(0, "The hook is on but changes nothing: it only logs. That is fine for watching.", setOf(Scope.HOOK)))
            if (!mon.hookSeen)
                w.add(Warn(0, "The hook is on, but no data request has been seen yet. Enable the module in LSPosed, restart the phone process, then reconnect.", hookHome))
            if (prefs.getBoolean("prof_written", false))
                w.add(Warn(1, "The modem profile holds an IP request AND the hook is on. If an address arrives you will not know which caused it. Test one at a time.", setOf(Scope.HOME, Scope.MODEM, Scope.HOOK)))
        } else if (prefs.getBoolean("hook_on", false)) {
            w.add(Warn(1, "You switched the hook on but did not press Apply (or the phone rebooted, which resets it).", hookHome))
        }
        if (prefs.getBoolean("prof_written", false))
            w.add(Warn(0, "A modem profile has an IP request saved. Clear it on the IP page (Modem) when you are done testing.", setOf(Scope.HOME, Scope.MODEM)))
        val apn = pf("apn")
        if (apn.isNotEmpty() && apn.equals(mon.apn, true))
            w.add(Warn(1, "Your APN “$apn” is the one Android already uses. Networks often refuse a second connection to the same APN.", setOf(Scope.MODEM)))
        if (mon.aliasOn)
            w.add(Warn(0, "The target IP is added as an alias. It disappears when the phone reconnects.", keepHome))
        w.addAll(driftWarnings())
        return w
    }

    // ---- bands seen around you ----
    class Seen(val band: Int, val best: Double?, val count: Int, val serving: Boolean)

    internal fun seenBands(): List<Seen> {
        val best = HashMap<Int, Double>()
        val count = HashMap<Int, Int>()
        val listed = HashSet<Int>()
        var servingBand: Int? = null
        val o = observed()
        o.earfcn?.let { servingBand = CellScan.bandNumber(it) }
        mon.modem?.let { m ->
            for (c in m.cells) {
                val b = CellScan.bandNumber(c.earfcn) ?: continue
                count[b] = (count[b] ?: 0) + 1
                if (best[b] == null || c.rsrp > best[b]!!) best[b] = c.rsrp
            }
            for (e in m.unmeasured) CellScan.bandNumber(e)?.let { listed.add(it) }
        }
        if (mon.modem == null) {
            mon.snap?.cells?.forEach { c ->
                val b = CellScan.bandNumber(c.earfcn) ?: return@forEach
                count[b] = (count[b] ?: 0) + 1
                val r = c.rsrp?.toDouble()
                if (r != null && (best[b] == null || r > best[b]!!)) best[b] = r
            }
        }
        val bands = TreeSet<Int>()
        bands.addAll(count.keys); bands.addAll(listed)
        servingBand?.let { bands.add(it) }
        val out = bands.map { Seen(it, best[it], count[it] ?: 0, it == servingBand) }
        return out.sortedWith(compareByDescending<Seen> { it.serving }.thenByDescending { it.best ?: -999.0 })
    }

    // ---- help ----
    private fun helpIndex(): Int = when (cur) { 0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3 + ipSub; else -> 6 }

    internal fun showHelp(i: Int) {
        val p = Help.pages[i]
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        box.addM(label(p.what, 14f), 4)
        box.addM(label("How to use this page", 15f, true, Pal.primary), 14)
        p.steps.forEachIndexed { n, s -> box.addM(label("${n + 1}. $s", 14f), 6) }
        box.addM(label("Words on this page", 15f, true, Pal.primary), 14)
        for (t in p.terms) {
            box.addM(label(t, 14f, true), 8)
            box.addM(label(Help.glossary[t] ?: "", 13f, false, Pal.muted), 2)
        }
        AlertDialog.Builder(this).setTitle(p.title).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Got it", null).setNeutralButton("Full glossary") { _, _ -> showGlossary() }.show()
    }

    private fun showGlossary() {
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        for ((t, d) in Help.glossary.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
            box.addM(label(t, 14f, true), 10)
            box.addM(label(d, 13f, false, Pal.muted), 2)
        }
        AlertDialog.Builder(this).setTitle("Glossary").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Close", null).show()
    }

    // ---- shell ----
    internal fun show(i: Int) {
        cur = i
        updaters.clear()
        resultBox = null
        syncSel = null
        titleView.text = if (i == 3) ipTitles[ipSub] else titles[i]
        content.removeAllViews()
        val body = when (i) { 0 -> buildHome(); 1 -> buildBands(); 2 -> buildCells(); 3 -> buildIp(); else -> buildLogs() }
        content.addView(ScrollView(this).apply { addView(body) })
        tabViews.forEachIndexed { n, t ->
            t.setTextColor(if (n == i) Pal.primary else Pal.muted)
            t.typeface = if (n == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        pollNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        loadLogs()
        profiles = Profiles.load(this)
        val savedName = Profiles.activeName(this)
        active = profiles.firstOrNull { it.name == savedName } ?: profiles.first()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Pal.bg) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Pal.primary); setPadding(dp(16), dp(6), dp(8), dp(6))
        }
        titleView = TextView(this).apply { textSize = 20f; setTextColor(android.graphics.Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        top.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(btn("? Help") { showHelp(helpIndex()) })
        root.addView(top)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(android.graphics.Color.WHITE) }
        tabLabels.forEachIndexed { i, s ->
            val t = TextView(this).apply {
                text = s; gravity = Gravity.CENTER; textSize = 12f; setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { show(i) }
            }
            tabViews.add(t)
            nav.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(nav)
        setContentView(root)
        show(0)
        if (!prefs.getBoolean("welcomed", false)) {
            prefs.edit().putBoolean("welcomed", true).apply()
            showHelp(0)
        }
    }

    private val pollTask = object : Runnable {
        override fun run() { pollNow(); handler.postDelayed(this, 5000) }
    }

    override fun onResume() { super.onResume(); handler.post(pollTask) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(pollTask); saveLogs() }
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
