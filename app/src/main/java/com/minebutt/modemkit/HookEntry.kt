package com.minebutt.modemkit

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.net.InetAddress

// LSPosed module scoped to com.android.phone. Hooks DataServiceManager.setupDataCall (the framework's
// data-setup call). Args on Android 10-14: 0 accessNetwork, 1 DataProfile, 2 isRoaming, 3 allowRoaming,
// 4 reason (1 NORMAL, 2 SHUTDOWN, 3 HANDOVER), 5 LinkProperties, ... Settings come from system properties
// that the app sets as root: debug.modemkit.on (1), .reason (int or empty = unchanged), .addr (a.b.c.d),
// .apn (substring filter, empty = all). Every call is logged to logcat tag MODEMKIT.
class HookEntry : IXposedHookLoadPackage {
    private fun prop(k: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, k, "") as String
    } catch (e: Throwable) { "" }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.phone") return
        val cls = XposedHelpers.findClassIfExists("com.android.internal.telephony.data.DataServiceManager", lpparam.classLoader)
            ?: XposedHelpers.findClassIfExists("com.android.internal.telephony.dataconnection.DataServiceManager", lpparam.classLoader)
        if (cls == null) { XposedBridge.log("MODEMKIT: DataServiceManager not found"); return }
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                try {
                    val a = p.args
                    val apn = try {
                        (XposedHelpers.callMethod(a[1], "getApn") as? String)
                            ?: (XposedHelpers.callMethod(XposedHelpers.callMethod(a[1], "getApnSetting"), "getApnName") as? String)
                            ?: ""
                    } catch (e: Throwable) { "?" }
                    val oldReason = a[4] as Int
                    val sb = StringBuilder("setupDataCall apn=$apn reason=$oldReason lp=${a[5]}")
                    val filter = prop("debug.modemkit.apn")
                    if (prop("debug.modemkit.on") == "1" && (filter.isEmpty() || apn.contains(filter, true))) {
                        prop("debug.modemkit.reason").toIntOrNull()?.let { a[4] = it; sb.append(" -> reason=$it") }
                        val addr = prop("debug.modemkit.addr")
                        if (addr.isNotEmpty()) {
                            val la = XposedHelpers.newInstance(XposedHelpers.findClass("android.net.LinkAddress", null),
                                InetAddress.getByName(addr), 32)
                            val existingLp = a[5]
                            if (existingLp != null) {
                                XposedHelpers.callMethod(existingLp, "addLinkAddress", la)
                            } else {
                                val lp = XposedHelpers.newInstance(XposedHelpers.findClass("android.net.LinkProperties", null))
                                XposedHelpers.callMethod(lp, "addLinkAddress", la)
                                a[5] = lp
                            }
                            sb.append(" -> addr=$addr")
                        }
                    }
                    Log.i("MODEMKIT", sb.toString())
                } catch (e: Throwable) { Log.e("MODEMKIT", "hook failed: $e") }
            }
        }
        var n = 0
        for (m in cls.declaredMethods) if (m.name == "setupDataCall") { XposedBridge.hookMethod(m, hook); n++ }
        XposedBridge.log("MODEMKIT: hooked $n setupDataCall method(s) in ${cls.name}")
    }
}
