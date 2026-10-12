package com.minebutt.modemkit

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val ipRe = Regex("""\d{1,3}(\.\d{1,3}){3}""")
internal val nameRe = Regex("""[A-Za-z0-9_.\-]+""")

internal fun MainActivity.page(): LinearLayout = vbox().apply { setPadding(dp(12), dp(12), dp(12), dp(24)) }

internal fun MainActivity.input(parent: LinearLayout, label: String, key: String, def: String = "", hint: String = "", pass: Boolean = false): EditText {
    parent.addM(note(label), 8)
    val act = this
    val e = EditText(this).apply {
        setSingleLine(); textSize = 14f; setText(act.pf(key, def)); this.hint = hint
        if (pass) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { act.setPf(key, s.toString().trim()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }
    parent.addView(e)
    return e
}

// A field value that matches the pattern, or null with a toast.
internal fun MainActivity.valid(k: String, re: Regex, what: String): String? {
    val s = pf(k)
    if (s.isEmpty() || !re.matches(s)) { toast("Please fill in a valid $what"); return null }
    return s
}

internal fun MainActivity.warnBox(scope: Int): LinearLayout {
    val box = vbox()
    live {
        box.removeAllViews()
        val list = conflicts().filter { scope < 0 || scope in it.scopes }
        if (list.isEmpty()) box.addM(label("✔ No conflicts found", 13f, true, Pal.ok), 0)
        else for (w in list.sortedByDescending { it.level }) box.addM(banner(w.level, w.text), 6)
    }
    return box
}

internal fun MainActivity.resultCard(parent: LinearLayout) {
    val c = cardView()
    c.addView(label("Last result", 15f, true))
    val t = mono("Nothing run yet. Full details are always in the Logs tab.")
    c.addM(t, 6)
    resultBox = t
    parent.addM(c, 10)
}

internal fun MainActivity.pickSaved(onPick: (String) -> Unit) {
    val list = hist().reversed()
    if (list.isEmpty()) { toast("Nothing saved yet"); return }
    val fmt = SimpleDateFormat("MMM d HH:mm", Locale.US)
    val items = list.map { "${it[0]}   (${fmt.format(Date(it[1].toLong()))})" }.toTypedArray()
    AlertDialog.Builder(this).setTitle("Saved IPs").setItems(items) { _, i -> onPick(list[i][0]) }.show()
}

// "Keep across reboots" box, shared by the Bands and Cells pages.
internal fun MainActivity.permCheck(): CheckBox {
    val act = this
    return CheckBox(this).apply {
        text = "Keep across reboots (permanent). Tick it again when restoring, to undo a permanent lock."
        isChecked = act.prefs.getBoolean("perm", false)
        setOnCheckedChangeListener { _, on -> act.prefs.edit().putBoolean("perm", on).apply() }
    }
}

// ---------------- Home ----------------
internal fun MainActivity.buildHome(): View {
    val s = page()

    // ---- 1. Big status button: the first thing on Home ----
    val statusCard = cardView()
    val statusButton = btn("CHECKING…", 1) { toast("Scanning…"); pollNow() }
    statusButton.textSize = 22f
    statusCard.addView(statusButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(84)))
    val statusExplanation = label("Waiting for the first scan…", 14f)
    statusCard.addM(statusExplanation, 8)
    statusCard.addM(note("Tap the big button to scan again. The app also scans by itself every 5 seconds while it is open."), 4)
    val fixBox = vbox()
    statusCard.addM(fixBox, 8)
    s.addM(statusCard, 0)

    // ---- 2. What is on and what is off ----
    val onCard = cardView()
    onCard.addView(label("WHAT IS ON RIGHT NOW", 14f, true, Pal.primary))
    val onBox = vbox()
    onCard.addM(onBox, 6)
    s.addM(onCard, 10)

    // ---- 3. Changes made behind the app's back ----
    val chgCard = cardView()
    chgCard.addView(label("CHANGED WITHOUT THIS APP", 14f, true, Pal.warn))
    chgCard.addM(note("Another app, a PC command, a reboot or the modem changed these:"), 2)
    val chgText = mono("", 12f)
    chgCard.addM(chgText, 6)
    chgCard.addM(btn("Got it, clear this list") { mon.external.clear(); refreshLive() }, 8)
    s.addM(chgCard, 10)

    live {
        val state = currentAppStatus()
        statusButton.text = statusTitle()
        statusButton.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(when (state) {
                AppStatus.GREEN -> Pal.ok
                AppStatus.AMBER -> Pal.warn
                AppStatus.RED -> Pal.bad
                AppStatus.UNKNOWN -> Pal.muted
            })
            cornerRadius = dp(12).toFloat()
        }
        statusExplanation.text = statusDetail()
        onBox.removeAllViews()
        for (l in statusLines()) {
            val mark = when (l.state) { 1 -> "● "; 2 -> "▲ "; -1 -> "✖ "; else -> "○ " }
            val col = when (l.state) { 1 -> Pal.ok; 2 -> Pal.warn; -1 -> Pal.bad; else -> Pal.muted }
            onBox.addM(label(mark + l.text, 14f, l.state != 0, col), 5)
        }
        chgCard.visibility = if (mon.external.isEmpty()) View.GONE else View.VISIBLE
        chgText.text = mon.external.takeLast(8).joinToString("\n\n")
        fixBox.removeAllViews()
        fixAction()?.let { (title, go) -> fixBox.addM(btn(title, 1) { go() }, 0) }
    }

    val more = vbox().apply { visibility = View.GONE }

    val hero = cardView()
    hero.addView(note("Your mobile IP address right now"))
    val big = label("…", 26f, true, Pal.primary)
    val sub = note("")
    val match = label("", 13f, true)
    hero.addM(big, 2); hero.addM(sub, 2); hero.addM(match, 8)
    s.addM(hero, 0)
    live {
        val m = mainAddr()
        big.text = m?.second ?: "No address"
        sub.text = if (m == null) "Mobile data is off or still connecting." else
            "on ${m.first} · seen since ${hm(mon.ipSince)} (${(System.currentTimeMillis() - mon.ipSince) / 60000} min) · changes this session: ${mon.changes}"
        val t = pf("ip")
        val ok = m != null && m.second == t || mon.aliasOn
        match.text = when {
            t.isEmpty() -> "No target IP set yet (only needed for the IP page)."
            m != null && m.second == t -> "✔ You have your target IP"
            mon.aliasOn -> "✔ Your target IP is active as an alias"
            else -> "Your target $t is not active yet. Try a route on the IP page."
        }
        match.setTextColor(if (ok) Pal.ok else if (t.isEmpty()) Pal.muted else Pal.warn)
    }

    val rc = cardView()
    rc.addView(label("Radio now", 16f, true))
    rc.addM(note("Band, cell and signal. Details on the Bands and Cells pages."), 0)
    val rt = mono("", 12.5f)
    rc.addM(rt, 6)
    live { rt.text = radioBrief() }
    s.addM(rc, 10)

    val chk = cardView()
    chk.addView(label("Getting started", 16f, true))
    val chkBox = vbox()
    chk.addM(chkBox, 6)
    live {
        chkBox.removeAllViews()
        fun item(ok: Boolean?, good: String, bad: String) {
            val txt = when (ok) { true -> "✔ $good"; false -> "✖ $bad"; null -> "… checking" }
            val col = when (ok) { true -> Pal.ok; false -> Pal.bad; null -> Pal.muted }
            chkBox.addM(label(txt, 14f, false, col), 4)
        }
        item(mon.root, "Root access works", "No root access: open Magisk and allow this app")
        item(mon.tools, "Helper tools are installed", "Helper tools missing: tap “Set up tools” below")
        item(if (mon.tools == true) mon.modemOk else null, "The modem answers", "The modem did not answer: tap “Set up tools”, then check Logs")
        item(hist().isNotEmpty(), "An IP address has been saved", "No IP saved yet (saved automatically when mobile data connects)")
    }
    val setupMsg = mono("", 11f)
    chk.addM(btn("Set up tools", 1) {
        resultBox = setupMsg
        toast("Setting up…")
        runRoot("Set up tools", "echo setup done", setup = true)
    }, 8)
    chk.addM(setupMsg, 6)
    more.addM(chk, 10)

    val mc = cardView()
    mc.addView(label("Live monitor", 16f, true))
    mc.addM(note("Updates every 5 seconds"), 0)
    val mt = mono("", 12.5f)
    mc.addM(mt, 6)
    live {
        val data = when (mon.data) { 2 -> "connected"; 1 -> "connecting"; 0 -> "disconnected"; 3 -> "suspended"; else -> "unknown" }
        val hook = if (mon.hookOn) "ON  reason ${Reasons.label(mon.hookReason)}  address ${mon.hookAddr.ifEmpty { "-" }}" else "off"
        mt.text = "Operator       ${mon.oper.ifEmpty { "?" }}\n" +
            "Network        ${mon.net.ifEmpty { "?" }}\n" +
            "Mobile data    $data\n" +
            "Android's APN  ${mon.apn.ifEmpty { "?" }}\n" +
            "Addresses      ${if (mon.addrs.isEmpty()) "none" else mon.addrs.joinToString("\n               ") { it.first + "  " + it.second }}\n" +
            "Hook           $hook\n" +
            "Checked        ${if (mon.updated == 0L) "-" else hm(mon.updated)}"
    }
    val act0 = this
    val scanBox = CheckBox(this).apply {
        text = "Scan the radio every 5 s while the app is open"
        isChecked = act0.prefs.getBoolean("scan", true)
        setOnCheckedChangeListener { _, on -> act0.prefs.edit().putBoolean("scan", on).apply() }
    }
    mc.addM(scanBox, 8)
    mc.addM(btn("Refresh now") { pollNow() }, 8)
    more.addM(mc, 10)

    val tc = cardView()
    tc.addView(label("What I'm asking for (IP page)", 16f, true))
    tc.addM(note("The IP page uses these. Tap ? Help for what each word means."), 0)
    val ipEdit = input(tc, "Target IP address (a.b.c.d)", "ip", hint = "for example 10.20.30.40")
    input(tc, "APN (connection name; empty keeps Android's)", "apn")
    input(tc, "Network interface (found automatically)", "iface", "rmnet_data0")
    val act = this
    tc.addM(row(
        btn("Pick a saved IP") { act.pickSaved { ipEdit.setText(it) } },
        btn("Use my current IP") { act.mainAddr()?.let { ipEdit.setText(it.second) } ?: act.toast("No address yet") }
    ), 10)
    s.addM(tc, 10)

    val wc = cardView()
    wc.addView(label("Conflicts and warnings", 16f, true))
    wc.addM(warnBox(-1), 8)
    s.addM(wc, 10)

    val guardBox = CheckBox(this).apply {
        text = "Auto-repair: if something undoes my band lock, apply it again (up to 3 times)"
        isChecked = act.prefs.getBoolean("guard", true)
        setOnCheckedChangeListener { _, on -> act.prefs.edit().putBoolean("guard", on).apply(); act.refreshLive() }
    }
    val tools = cardView()
    tools.addView(label("Testing tools", 16f, true))
    tools.addM(guardBox, 6)
    tools.addM(btn("Copy a full report (for bug reports)") { act.copyReport() }, 8)
    s.addM(tools, 10)

    val moreBtn = btn("Show more details (monitor, setup check)") {
        more.visibility = if (more.visibility == View.GONE) View.VISIBLE else View.GONE
    }
    s.addM(moreBtn, 10)
    s.addM(more, 6)

    resultCard(s)
    return s
}

// ---------------- Logs ----------------
internal fun MainActivity.buildLogs(): View {
    val s = page()
    s.addM(note("Everything the app does is recorded here: what you tapped, the answer, errors, every change of your IP, and every change of cell, band or mode."), 0)
    val act = this
    var filter = 0
    var shown = -1
    val tv = mono("", 11.5f)
    fun sym(k: String) = when (k) { "action" -> "▶"; "result" -> "✔"; "error" -> "✖"; "hook" -> "🪝"; "cell" -> "📡"; else -> "•" }
    fun render(force: Boolean) {
        if (!force && shown == logs.size) return
        shown = logs.size
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val list = logs.filter {
            when (filter) {
                1 -> it.kind == "action" || it.kind == "result"
                2 -> it.kind == "error"
                3 -> it.kind == "hook" || (it.kind == "info")
                4 -> it.kind == "cell"
                else -> true
            }
        }
        tv.text = if (list.isEmpty()) "Nothing here yet." else
            list.asReversed().joinToString("\n\n") { e -> "${fmt.format(Date(e.time))} ${sym(e.kind)} ${e.title}\n${e.text.prependIndent("   ")}" }
    }
    val sp = Spinner(this).apply {
        adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item,
            listOf("Everything", "Actions and answers", "Errors only", "Hook and IP changes", "Cell and band changes"))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { filter = pos; render(true) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
    s.addM(sp, 8)
    s.addM(row(
        btn("Copy") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("modemkit log", tv.text))
            toast("Log copied")
        },
        btn("Fetch hook log") { runRoot("Hook log", "logcat -d -s MODEMKIT:I | tail -n 60", kind = "hook") },
        btn("Clear") {
            confirm("Clear the log?", "This removes every entry.", "Clear") { logs.clear(); saveLogs(); render(true) }
        }
    ), 8)
    val c = cardView()
    c.addView(tv)
    s.addM(c, 10)
    live { render(false) }
    return s
}
