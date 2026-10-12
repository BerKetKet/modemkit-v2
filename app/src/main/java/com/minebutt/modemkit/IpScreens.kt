package com.minebutt.modemkit

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The IP tab holds three routes behind a row of switch buttons, so the bottom bar stays at five tabs.
internal fun MainActivity.buildIp(): View {
    val s = page()
    s.addM(segmented(listOf("Keep IP", "Modem", "Hook"), ipSub) { ipSub = it; show(3) }, 0)
    when (ipSub) {
        0 -> keepSection(s)
        1 -> modemSection(s)
        else -> hookSection(s)
    }
    resultCard(s)
    return s
}

// ---------------- Keep IP ----------------
private fun MainActivity.keepSection(s: LinearLayout) {
    s.addM(note("The gentlest route. It changes nothing inside the modem or Android: it reconnects so you can see what the network gives, and can add a saved address to the phone by hand."), 0)
    s.addM(warnBox(Scope.KEEP), 8)

    val c1 = cardView()
    c1.addView(label("1 · Reconnect and watch", 16f, true))
    c1.addM(note("Turns mobile data off and on so the carrier hands out an address again. New addresses are saved automatically."), 4)
    c1.addM(btn("Reconnect (airplane mode toggle)", 1) {
        confirm("Reconnect now?", "Mobile data and Wi-Fi switch off for about 30 seconds (adb over Wi-Fi will drop; USB is fine). Any alias you added is removed.") {
            runRoot("Reconnect (airplane)", "cmd connectivity airplane-mode enable; sleep 4; cmd connectivity airplane-mode disable; sleep 25; ip -o -4 addr show | grep rmnet")
        }
    }, 8)
    c1.addM(btn("Reconnect (mobile data off/on, keeps Wi-Fi)") {
        runRoot("Reconnect (data)", "svc data disable; sleep 3; svc data enable; sleep 15; ip -o -4 addr show | grep rmnet")
    }, 6)
    s.addM(c1, 10)

    val c2 = cardView()
    c2.addView(label("2 · Saved IPs", 16f, true))
    c2.addM(note("Tap one to make it your target."), 0)
    val hb = vbox()
    c2.addM(hb, 4)
    fun fill() {
        hb.removeAllViews()
        val list = hist().reversed()
        if (list.isEmpty()) hb.addM(note("Nothing saved yet. Addresses are saved as the phone receives them."), 4)
        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
        for (e in list) hb.addM(btn("${e[0]}   ·   ${fmt.format(Date(e[1].toLong()))}") {
            setPf("ip", e[0]); toast("Target set to ${e[0]}"); pollNow()
        }, 4)
    }
    live { fill() }
    c2.addM(btn("Clear saved IPs") {
        confirm("Clear the saved IP list?", "Addresses you saved will be forgotten.", "Clear") { prefs.edit().remove("hist").apply(); fill() }
    }, 8)
    s.addM(c2, 10)

    val c3 = cardView()
    c3.addView(label("3 · Use the target IP on the phone", 16f, true))
    c3.addM(note("Adds the target as an extra address on the mobile interface, then tests it. Works only if the network still accepts it."), 4)
    input(c3, "Ping this address for the test", "target", "1.1.1.1")
    c3.addM(btn("Add alias", 1) {
        val ip = valid("ip", ipRe, "target IP (on Home)"); val ifc = valid("iface", nameRe, "interface")
        if (ip != null && ifc != null) runRoot("Add alias $ip", "ip addr add $ip/32 dev $ifc; ip -o -4 addr show dev $ifc")
    }, 8)
    c3.addM(btn("Test from the target IP") {
        val ip = valid("ip", ipRe, "target IP (on Home)"); val t = valid("target", nameRe, "ping address")
        if (ip != null && t != null) runRoot("Test from $ip",
            "ping -c 3 -W 2 -I $ip $t | tail -n 2; pub=\$(curl -s -m 8 --interface $ip https://ifconfig.me 2>/dev/null || toybox wget -q -O - https://ifconfig.me 2>/dev/null || echo 'curl/wget unavailable'); echo \"public address seen by websites: \$pub\"")
    }, 6)
    c3.addM(btn("Remove alias") {
        val ip = valid("ip", ipRe, "target IP (on Home)"); val ifc = valid("iface", nameRe, "interface")
        if (ip != null && ifc != null) runRoot("Remove alias $ip", "ip addr del $ip/32 dev $ifc; ip -o -4 addr show dev $ifc")
    }, 6)
    s.addM(c3, 10)

}

// ---------------- Modem ----------------
internal fun MainActivity.startCmd(): String {
    val a = StringBuilder("$qwds start")
    for (k in listOf("apn", "ip", "fam", "auth", "user", "pass", "prof", "hold", "mux")) if (pf(k).isNotEmpty()) a.append(" $k=${q(pf(k))}")
    if (pf("swap") == "1") a.append(" swap=1")
    return a.toString()
}

internal fun MainActivity.setProfileIp(ip: String) {
    val p = valid("prof", Regex("""\d{1,3}"""), "modem profile number") ?: return
    confirm("Change modem profile $p?",
        "This writes the IP request $ip into the modem's saved profile $p, which every connection using it will see. The current profile is first copied to $dir/profile_$p.bak. Use “Clear” to undo.", "Write") {
        runRoot("Profile $p IP request = $ip", "$qwds profile $p | tee $dir/profile_$p.bak; $qwds setip $p $ip; $qwds profile $p") {
            if (!it.contains("FAILED")) prefs.edit().putBoolean("prof_written", ip != "0.0.0.0").apply()
        }
    }
}

internal fun MainActivity.atSend(cmds: List<String>) {
    val port = valid("atport", Regex("""/dev/[A-Za-z0-9_]+"""), "AT port") ?: return
    runRoot("AT on $port", cmds.joinToString("; ") { "echo '>> '${q(it)}; timeout 6 sh $dir/at.sh $port ${q(it)} 2" })
}

private fun MainActivity.modemSection(s: LinearLayout) {
    s.addM(note("These buttons talk to the phone's modem chip directly. Start with the safe “Look” buttons."), 0)
    s.addM(warnBox(Scope.MODEM), 8)

    val c = cardView()
    c.addView(label("Ask with QMI (recommended)", 16f, true))
    val tgt = note("")
    c.addM(tgt, 4)
    live { tgt.text = "Will use target IP ${pf("ip").ifEmpty { "(none)" }} and APN ${pf("apn").ifEmpty { "(Android's default)" }}. Change them on Home." }
    input(c, "IP family (4 or 6)", "fam", "4")
    input(c, "Login type (0 none, 1 PAP, 2 CHAP, 3 both; usually empty)", "auth")
    input(c, "Username (usually empty)", "user")
    input(c, "Password (usually empty)", "pass", pass = true)
    input(c, "Modem profile number (find it with “List profiles”)", "prof")
    input(c, "Keep the test connection open for (seconds)", "hold", "20")

    c.addM(label("Look (safe, read only)", 14f, true, Pal.primary), 14)
    c.addM(row(
        btn("Status") { runRoot("QMI status", "$qwds status", setup = true) },
        btn("List profiles") { runRoot("QMI profiles", "$qwds profiles", setup = true) }
    ), 4)
    c.addM(btn("Show this profile") {
        valid("prof", Regex("""\d{1,3}"""), "modem profile number")?.let { runRoot("QMI profile $it", "$qwds profile $it") }
    }, 4)

    c.addM(label("Try (opens a short test connection)", 14f, true, Pal.primary), 14)
    c.addM(btn("Start test connection", 1) {
        val apn = pf("apn")
        val go = { runRoot("QMI start network", startCmd(), setup = true) }
        if (apn.isNotEmpty() && apn.equals(mon.apn, true))
            confirm("Same APN as Android's connection", "Android already has a connection on “$apn”. Carriers often refuse a second one on the same APN, so this may fail with a refusal code. That is still useful to learn.") { go() }
        else go()
    }, 4)

    c.addM(label("Change the modem (careful)", 14f, true, Pal.bad), 14)
    c.addM(btn("Write target IP into the profile", 2) {
        valid("ip", ipRe, "target IP (on Home)")?.let { setProfileIp(it) }
    }, 4)
    c.addM(btn("Clear the profile's IP request") { setProfileIp("0.0.0.0") }, 4)

    val adv = vbox().apply { visibility = View.GONE }
    input(adv, "Mux id (advanced, usually empty)", "mux")
    input(adv, "Swap bytes (type 1 if an address looks reversed)", "swap")
    c.addM(btn("Show advanced options") { adv.visibility = if (adv.visibility == View.GONE) View.VISIBLE else View.GONE }, 12)
    c.addView(adv)
    s.addM(c, 10)

    val at = cardView()
    at.addView(label("Ask with AT commands (advanced)", 16f, true))
    at.addM(note("Old-style text commands. This phone may not expose a usable port, so use “Find AT ports” first."), 4)
    val body = vbox().apply { visibility = View.GONE }
    input(body, "AT port", "atport", "/dev/smd11")
    input(body, "Connection number (cid). 1 is usually Android's own data.", "cid", "1")
    input(body, "Command to send", "atcmd", "AT+CGDCONT?")
    body.addM(btn("Find AT ports") {
        runRoot("Find AT ports", "for d in /dev/smd* /dev/at* /dev/ttyGS* /dev/ttyMSM*; do [ -c \"\$d\" ] || continue; echo \"\$d -> \$(timeout 4 sh $dir/at.sh \$d AT 1 | tr -d '\\r\\n')\"; done", setup = true)
    }, 10)
    body.addM(btn("Send the command above") { atSend(listOf(pf("atcmd"))) }, 4)
    body.addM(btn("Read addresses (CGPADDR, CGCONTRDP, CGACT)") { atSend(listOf("AT+CGPADDR", "AT+CGCONTRDP", "AT+CGACT?")) }, 4)
    body.addM(btn("Define a connection with APN + target IP", 2) {
        val ip = valid("ip", ipRe, "target IP (on Home)"); val apn = valid("apn", nameRe, "APN (on Home)")
        val cid = valid("cid", Regex("""\d{1,2}"""), "cid")
        if (ip != null && apn != null && cid != null)
            confirm("Define connection $cid?",
                (if (cid == "1") "Connection 1 is usually Android's own internet connection. Changing it can cut your mobile data.\n\n" else "") +
                    "Sends AT+CGDCONT=$cid,\"IP\",\"$apn\",\"$ip\" to the modem.", "Send") {
                atSend(listOf("AT+CGDCONT=$cid,\"IP\",\"$apn\",\"$ip\"", "AT+CGDCONT?"))
            }
    }, 4)
    at.addM(btn("Show or hide AT commands") { body.visibility = if (body.visibility == View.GONE) View.VISIBLE else View.GONE }, 8)
    at.addView(body)
    s.addM(at, 10)

}

// ---------------- Hook ----------------
private fun MainActivity.hookSection(s: LinearLayout) {
    s.addM(note("Android asks the modem to open a data connection with a “reason”. This page lets you watch that request, change the reason, and add your target address."), 0)
    s.addM(warnBox(Scope.HOOK), 8)

    val c0 = cardView()
    c0.addView(label("Set up once", 16f, true))
    c0.addM(label("1. LSPosed → Modules → enable “Modem Kit”.\n2. Scope: tick only com.android.phone.\n3. Tap the button below to load the module.", 13f), 4)
    c0.addM(btn("Restart phone process") {
        confirm("Restart the phone process?", "Calls and mobile data drop for a few seconds while Android restarts its phone program.", "Restart") {
            runRoot("Restart phone process", "am force-stop com.android.phone; sleep 3; echo done")
        }
    }, 8)
    s.addM(c0, 10)

    val c = cardView()
    c.addView(label("Hook settings", 16f, true))
    val act = this
    val sw = Switch(this).apply {
        text = "Hook active"; isChecked = act.prefs.getBoolean("hook_on", false)
        setOnCheckedChangeListener { _, on -> act.prefs.edit().putBoolean("hook_on", on).apply() }
    }
    c.addM(sw, 8)
    c.addM(note("Reason sent with the data request"), 10)
    val desc = note("")
    val sp = Spinner(this).apply {
        adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, Reasons.names)
        setSelection(act.prefs.getInt("reason", 0))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                act.prefs.edit().putInt("reason", pos).apply(); desc.text = Reasons.help[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
    c.addView(sp)
    c.addM(desc, 4)
    val inj = CheckBox(this).apply {
        text = "Also pass the target IP as the requested address"; isChecked = act.prefs.getBoolean("hook_addr", true)
        setOnCheckedChangeListener { _, on -> act.prefs.edit().putBoolean("hook_addr", on).apply() }
    }
    c.addM(inj, 10)
    input(c, "Only for APNs containing (empty = all)", "hookapn")
    c.addM(btn("Apply", 1) {
        val r = prefs.getInt("reason", 0)
        val go = {
            val addr = if (inj.isChecked) pf("ip") else ""
            val apn = pf("hookapn")
            if (addr.isNotEmpty() && !ipRe.matches(addr)) toast("The target IP on Home is not valid")
            else if (apn.isNotEmpty() && !nameRe.matches(apn)) toast("The APN filter has odd characters")
            else runRoot("Apply hook settings",
                "setprop debug.modemkit.on ${if (sw.isChecked) 1 else 0}; setprop debug.modemkit.reason ${if (r == 0) "''" else (r - 1)}; " +
                    "setprop debug.modemkit.addr ${q(addr)}; setprop debug.modemkit.apn ${q(apn)}; getprop | grep modemkit")
        }
        if (sw.isChecked && r == 3)
            confirm("Reason SHUTDOWN", "This tells the modem to close connections instead of opening them. Mobile data will likely stay off until you switch the hook off.", "Apply anyway") { go() }
        else go()
    }, 12)
    s.addM(c, 10)

    val c2 = cardView()
    c2.addView(label("Try it and read the answer", 16f, true))
    c2.addM(note("The hook only acts on a new data request. Reconnect, then read what Android sent."), 0)
    c2.addM(btn("Reconnect mobile data") {
        runRoot("Reconnect (data)", "svc data disable; sleep 3; svc data enable; sleep 15; ip -o -4 addr show | grep rmnet")
    }, 8)
    c2.addM(btn("Show hook log") {
        runRoot("Hook log", "logcat -d -s MODEMKIT:I | tail -n 40", kind = "hook")
    }, 6)
    c2.addM(note("The settings reset when the phone reboots; press Apply again after a restart."), 8)
    s.addM(c2, 10)

}

