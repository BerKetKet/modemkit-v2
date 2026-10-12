package com.minebutt.modemkit

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Pal {
    val primary = Color.parseColor("#00796B")
    val bg = Color.parseColor("#F1F5F4")
    val ink = Color.parseColor("#1F2A2A")
    val muted = Color.parseColor("#5F6F6F")
    val ok = Color.parseColor("#2E7D32")
    val warn = Color.parseColor("#E65100")
    val bad = Color.parseColor("#C62828")
    val info = Color.parseColor("#1565C0")
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.cardView(): LinearLayout = vbox().apply {
    setPadding(dp(14), dp(12), dp(14), dp(14))
    background = GradientDrawable().apply {
        setColor(Color.WHITE); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.parseColor("#D8E0DF"))
    }
}

fun Context.label(s: String, size: Float = 14f, bold: Boolean = false, color: Int = Pal.ink) = TextView(this).apply {
    text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.note(s: String) = label(s, 12f, false, Pal.muted)

fun Context.mono(s: String = "", size: Float = 11.5f) = TextView(this).apply {
    text = s; textSize = size; typeface = Typeface.MONOSPACE; setTextColor(Pal.ink); setTextIsSelectable(true)
}

// level 0 info, 1 warning, 2 danger
fun Context.banner(level: Int, s: String): TextView {
    val fg = when (level) { 2 -> Pal.bad; 1 -> Pal.warn; else -> Pal.info }
    val bg = when (level) { 2 -> "#FDECEA"; 1 -> "#FFF3E0"; else -> "#E8F1FB" }
    val icon = when (level) { 2 -> "⛔ "; 1 -> "⚠️ "; else -> "ℹ️ " }
    return TextView(this).apply {
        text = icon + s; textSize = 13f; setTextColor(fg)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { setColor(Color.parseColor(bg)); cornerRadius = dp(10).toFloat() }
    }
}

// kind 0 normal, 1 main action (teal), 2 risky (red)
fun Context.btn(s: String, kind: Int = 0, onClick: () -> Unit) = Button(this).apply {
    text = s; isAllCaps = false; textSize = 14f
    if (kind != 0) {
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(if (kind == 2) Pal.bad else Pal.primary); cornerRadius = dp(10).toFloat()
        }
    }
    setOnClickListener { onClick() }
}

// A row of buttons that act like tabs inside a page. The picked one is teal.
fun Context.segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
    val btns = ArrayList<Button>()
    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val radius = dp(10).toFloat()
    fun paint(sel: Int) {
        btns.forEachIndexed { i, b ->
            b.setTextColor(if (i == sel) Color.WHITE else Pal.ink)
            b.background = GradientDrawable().apply {
                setColor(if (i == sel) Pal.primary else Color.parseColor("#E3EAE9")); cornerRadius = radius
            }
        }
    }
    labels.forEachIndexed { i, s ->
        val b = Button(this).apply {
            text = s; isAllCaps = false; textSize = 13f
            setOnClickListener { paint(i); onPick(i) }
        }
        btns.add(b)
        r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    paint(selected)
    return r
}

fun Context.row(vararg v: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    for (x in v) addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
}

fun LinearLayout.addM(v: View, topDp: Int = 8) {
    addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (topDp * resources.displayMetrics.density).toInt()
    })
}
