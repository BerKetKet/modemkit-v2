package com.minebutt.modemkit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Broadcast receiver enabling headless CLI control and automated debugging
 * from a PC over adb via `am broadcast -a com.minebutt.modemkit.CLI`
 */
class CliReceiver : BroadcastReceiver() {
    private val tag = "MODEMKIT_CLI"
    private val dir = "/data/local/tmp"

    private lateinit var ctx: Context

    override fun onReceive(context: Context, intent: Intent) {
        ctx = context
        val cmd = intent.getStringExtra("cmd") ?: intent.getStringExtra("action") ?: "status"
        Log.i(tag, "Received CLI command: $cmd")

        Thread {
            try {
                val act = MainActivity.instance
                val out = when (cmd.lowercase()) {
                    "status" -> handleStatus(act)
                    "cell" -> handleCell()
                    "bands" -> handleBands(act)
                    "set_bands" -> handleSetBands(intent.getStringExtra("bands") ?: "", intent.getBooleanExtra("perm", false))
                    "restore_bands" -> handleRestoreBands(intent.getBooleanExtra("perm", false))
                    "set_mode" -> handleSetMode(intent.getStringExtra("mode") ?: "", intent.getBooleanExtra("perm", false))
                    "poll" -> {
                        act?.runOnUiThread { act.pollNow() }
                        "Poll triggered"
                    }
                    "logs" -> handleLogs(act)
                    "report" -> handleReport(act)
                    "prepare" -> {
                        act?.let { a ->
                            val r = a.prepare()
                            writeOut("modemkit.out", r)
                            r
                        } ?: su("$dir/qband_run cell 2>&1")
                    }
                    "exec" -> {
                        val toExec = intent.getStringExtra("exec") ?: intent.getStringExtra("sh") ?: ""
                        if (toExec.isEmpty()) "error: missing exec command"
                        else su(toExec)
                    }
                    "set_target_ip" -> {
                        val ip = intent.getStringExtra("ip") ?: ""
                        act?.let {
                            it.prefs.edit().putString("f_ip", ip).apply()
                            it.runOnUiThread { it.refreshLive(); it.pollNow() }
                        }
                        "Target IP updated to '$ip'"
                    }
                    "hook" -> {
                        val on = intent.getBooleanExtra("on", false)
                        val r = intent.getStringExtra("reason") ?: ""
                        val addr = intent.getStringExtra("addr") ?: ""
                        val apn = intent.getStringExtra("apn") ?: ""
                        val cmdStr = "setprop debug.modemkit.on ${if (on) 1 else 0}; " +
                            "setprop debug.modemkit.reason '$r'; " +
                            "setprop debug.modemkit.addr '$addr'; " +
                            "setprop debug.modemkit.apn '$apn'"
                        su(cmdStr)
                        act?.let {
                            it.prefs.edit().putBoolean("hook_on", on).apply()
                            it.runOnUiThread { it.pollNow() }
                        }
                        "Hook updated: on=$on reason='$r' addr='$addr' apn='$apn'"
                    }
                    else -> "unknown command '$cmd'. Valid: status, cell, bands, set_bands, restore_bands, set_mode, poll, logs, report, prepare, exec, set_target_ip, hook"
                }

                writeOut("modemkit.out", out)
                Log.i(tag, "Command '$cmd' result:\n$out")
            } catch (e: Throwable) {
                val err = "CLI execution failed: ${e.message}"
                Log.e(tag, err, e)
                writeOut("modemkit.out", err)
            }
        }.start()
    }

    private fun handleStatus(act: MainActivity?): String {
        val sb = StringBuilder()
        sb.append("=== Modem Kit v3 Status ===\n")
        if (act != null) {
            val mon = act.mon
            sb.append("UI State: ACTIVE\n")
            sb.append("Health: ${act.currentAppStatus().name}\n")
            sb.append("Root: ${mon.root}\n")
            sb.append("Tools: ${mon.tools}\n")
            sb.append("Modem Ok: ${mon.modemOk}\n")
            sb.append("Data State: ${mon.snap?.dataState ?: if (mon.data == 1) "CONNECTED" else "DISCONNECTED"}\n")
            sb.append("Carrier: ${mon.oper.ifEmpty { mon.snap?.carrier ?: "unknown" }}\n")
            val obs = act.observed()
            sb.append("Serving Cell: EARFCN=${obs.earfcn ?: "?"} PCI=${obs.pci ?: "?"} TAC=${obs.tac ?: "?"}\n")
            val bands = act.enabledBands()?.let { Radio.bandList(it) } ?: "unknown"
            sb.append("Enabled Bands: $bands\n")
            sb.append("Network Mode: ${mon.mode?.let { Radio.modeName(it) } ?: "unknown"}\n")
            sb.append("IP Address: ${act.mainAddr()?.second ?: "none"}\n")
            sb.append("Recent warnings: ${act.conflicts().size}\n")
        } else {
            sb.append("UI State: BACKGROUND (Reading directly from modem via root)\n")
            val cellOut = su("$dir/qband_run cell 2>&1")
            val origOut = su("cat $dir/qband.orig 2>/dev/null").trim()
            val masks = Radio.parseMasks(cellOut)
            val mode = Radio.parseMode(cellOut)
            sb.append("Original bands: ${if (origOut.isEmpty()) "not recorded yet" else origOut}\n")
            sb.append("Active masks: ${masks?.let { Radio.bandList(Radio.bandsIn(it)) } ?: "unknown"}\n")
            sb.append("Active mode: ${mode?.let { Radio.modeName(it) } ?: "unknown"}\n")
        }
        val text = sb.toString()
        writeOut("modemkit.status", text)
        return text
    }

    private fun handleCell(): String {
        return su("$dir/qband_run cell 2>&1")
    }

    private fun handleBands(act: MainActivity?): String {
        val sb = StringBuilder()
        val cur = su("$dir/qband_run get 2>&1")
        val orig = su("cat $dir/qband.orig 2>/dev/null").trim()
        val masks = Radio.parseMasks(cur)
        val mode = Radio.parseMode(cur)
        sb.append("Enabled Bands: ").append(masks?.let { Radio.bandList(Radio.bandsIn(it)) } ?: "error reading").append("\n")
        sb.append("Original Bands: ").append(orig.ifEmpty { "none saved yet" }).append("\n")
        sb.append("Network Mode: ").append(mode?.let { Radio.modeName(it) } ?: "unknown").append("\n")
        if (act != null) {
            sb.append("Known Bands: ").append(act.knownBands().joinToString(", ")).append("\n")
        }
        return sb.toString()
    }

    private fun handleSetBands(bands: String, perm: Boolean): String {
        if (bands.isEmpty()) return "error: missing bands argument (e.g. bands=3,7,20)"
        val cmd = "$dir/qband_run set $bands" + if (perm) " perm" else ""
        return su(cmd)
    }

    private fun handleRestoreBands(perm: Boolean): String {
        val cmd = "$dir/qband_run set all" + if (perm) " perm" else ""
        return su(cmd)
    }

    private fun handleSetMode(mode: String, perm: Boolean): String {
        if (mode.isEmpty()) return "error: missing mode argument (auto, lte, 3g2g)"
        val cmd = "$dir/qband_run mode $mode" + if (perm) " perm" else ""
        return su(cmd)
    }

    private fun handleLogs(act: MainActivity?): String {
        val text = act?.logs?.reversed()?.joinToString("\n\n") {
            "[${it.kind.uppercase()}] ${it.title} (${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(it.time)})\n${it.text}"
        } ?: "No logs available (MainActivity not running in foreground)"
        writeOut("modemkit.logs", text)
        return text
    }

    private fun handleReport(act: MainActivity?): String {
        val text = act?.diagReport() ?: "App is in background. Run 'status' for direct telemetry."
        writeOut("modemkit.report", text)
        return text
    }

    private fun su(cmd: String): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(20, TimeUnit.SECONDS)
        out.trim()
    } catch (e: Exception) {
        "error: ${e.message}"
    }

    private fun writeOut(fileName: String, content: String) {
        try {
            val local = File(ctx.filesDir, fileName)
            local.writeText(content)
            su("cp ${local.absolutePath} $dir/$fileName 2>/dev/null; chmod 666 $dir/$fileName 2>/dev/null")
        } catch (e: Exception) {
            Log.e(tag, "writeOut error: ${e.message}")
        }
    }
}
