package com.minebutt.modemkit

import android.content.Context
import java.util.Locale

// Band and mask helpers. The modem reports which bands are enabled as four 64-bit masks
// (bands 1-64, 65-128, 129-192, 193-256), and qband prints them as text.
object Radio {
    const val MAX_BAND = 256
    const val REAL_MAX = 88 // roughly what 3GPP has defined for LTE; higher ids are modem-internal

    // What this Pixel 2 XL modem reported the first time. The app adds to this list whenever the modem
    // reports more, so only bands the modem has really shown are ever offered.
    val defaultBands = intArrayOf(1, 3, 4, 5, 7, 8, 20, 28, 34, 38, 39, 40, 41, 66, 71)

    fun isInternal(b: Int) = b > REAL_MAX

    // Common name of the band (centre of the spectrum, in MHz).
    private val names = mapOf(
        1 to "2100", 2 to "1900 PCS", 3 to "1800", 4 to "AWS 1700/2100", 5 to "850", 7 to "2600", 8 to "900",
        12 to "700 a", 13 to "700 c", 14 to "700 PS", 17 to "700 b", 18 to "800 lower", 19 to "800 upper",
        20 to "800 DD", 25 to "1900+", 26 to "850+", 28 to "700 APT", 29 to "700 SDL", 30 to "2300 WCS",
        32 to "1500 SDL", 34 to "2000 TDD", 38 to "2600 TDD", 39 to "1900 TDD", 40 to "2300 TDD",
        41 to "2500 TDD", 42 to "3500 TDD", 43 to "3700 TDD", 46 to "5 GHz LAA", 48 to "3500 CBRS",
        66 to "AWS-3", 71 to "600"
    )

    // Plain-language notes, shown under each band on the Bands page.
    private val facts = mapOf(
        1 to "2100 MHz, FDD. Good capacity, shorter reach.",
        2 to "1900 MHz, FDD. Medium reach and capacity.",
        3 to "1800 MHz, FDD. Good balance of reach and capacity.",
        4 to "1700/2100 MHz AWS, FDD. Medium reach and capacity.",
        5 to "850 MHz, FDD. Long reach, good indoors, lower capacity.",
        7 to "2600 MHz, FDD. High capacity, short reach.",
        8 to "900 MHz, FDD. Long reach and good indoor coverage.",
        12 to "700 MHz, FDD. Long reach and good indoor coverage.",
        13 to "700 MHz, FDD. Long reach.",
        14 to "700 MHz public safety, FDD. Reserved for emergency services.",
        17 to "700 MHz, FDD. Long reach.",
        18 to "800 MHz lower, FDD. Long reach.",
        19 to "800 MHz upper, FDD. Long reach.",
        20 to "800 MHz, FDD. Long reach and good indoor coverage.",
        25 to "1900 MHz extended, FDD. Medium reach.",
        26 to "850 MHz extended, FDD. Long reach.",
        28 to "700 MHz, FDD. Long reach and good indoor coverage.",
        29 to "700 MHz, download only. Adds speed alongside another band; cannot be used alone.",
        30 to "2300 MHz, FDD. Short reach.",
        32 to "1500 MHz, download only. Adds speed alongside another band; cannot be used alone.",
        34 to "2000 MHz, TDD. Uplink and downlink share one frequency.",
        38 to "2600 MHz, TDD. Large capacity, short reach.",
        39 to "1900 MHz, TDD. Large capacity.",
        40 to "2300 MHz, TDD. Large capacity.",
        41 to "2500 MHz, TDD. Large capacity.",
        42 to "3500 MHz, TDD. High capacity, short reach.",
        43 to "3700 MHz, TDD. High capacity, short reach.",
        46 to "5 GHz unlicensed, TDD. Only adds speed together with a licensed band.",
        48 to "3500 MHz CBRS, TDD. Shared spectrum.",
        66 to "1700/2100 MHz AWS-3, FDD. Medium reach and capacity.",
        71 to "600 MHz, FDD. Very long reach and good indoor coverage."
    )

    fun describe(b: Int): String = when {
        isInternal(b) -> "Not a real radio band. The modem uses this bit internally, so the app leaves it alone."
        else -> facts[b] ?: "Band $b. No notes yet."
    }

    fun label(b: Int): String = when {
        isInternal(b) -> "B$b · modem id"
        names.containsKey(b) -> "B$b · ${names[b]}"
        else -> "B$b"
    }

    // Groups by what the band does, not by where it is used.
    val presets = linkedMapOf(
        "Long reach (below 1 GHz)" to intArrayOf(5, 8, 12, 13, 17, 18, 19, 20, 26, 28, 71),
        "Mid range (1 to 2 GHz)" to intArrayOf(1, 2, 3, 4, 25, 34, 66),
        "High capacity (2 GHz and up)" to intArrayOf(7, 30, 38, 40, 41, 42, 43),
        "FDD only" to intArrayOf(1, 2, 3, 4, 5, 7, 8, 12, 13, 17, 18, 19, 20, 25, 26, 28, 30, 66, 71),
        "TDD only" to intArrayOf(34, 38, 39, 40, 41, 42, 43, 48)
    )

    // Four masks, or null if the text has none.
    fun parseMasks(out: String): LongArray? {
        val ext = Regex("EXT masks: 0x([0-9a-fA-F]+) 0x([0-9a-fA-F]+) 0x([0-9a-fA-F]+) 0x([0-9a-fA-F]+)")
            .findAll(out).lastOrNull()
        if (ext != null) {
            return LongArray(4) { java.lang.Long.parseUnsignedLong(ext.groupValues[it + 1], 16) }
        }
        val low = Regex("LTE band mask: 0x([0-9a-fA-F]+)").findAll(out).lastOrNull() ?: return null
        return longArrayOf(java.lang.Long.parseUnsignedLong(low.groupValues[1], 16), 0L, 0L, 0L)
    }

    fun parseMode(out: String): Int? =
        Regex("MODE pref: 0x([0-9a-fA-F]+)").findAll(out).lastOrNull()?.groupValues?.get(1)?.toIntOrNull(16)

    fun bandsIn(m: LongArray): List<Int> =
        (1..MAX_BAND).filter { ((m[(it - 1) / 64] shr ((it - 1) % 64)) and 1L) == 1L }

    // The saved baseline file holds four unsigned decimal numbers.
    fun parseOrig(text: String): LongArray? {
        val p = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (p.isEmpty()) return null
        val out = LongArray(4)
        for (i in 0 until 4) {
            val v = p.getOrNull(i) ?: continue
            try { out[i] = java.lang.Long.parseUnsignedLong(v) } catch (e: Exception) { return null }
        }
        return out
    }

    fun modeName(m: Int): String {
        if (m == 0x10) return "LTE only"
        val parts = mutableListOf<String>()
        if ((m and 0x04) != 0) parts.add("2G")
        if ((m and 0x08) != 0) parts.add("3G")
        if ((m and 0x10) != 0) parts.add("LTE")
        if ((m and 0x20) != 0) parts.add("TD-SCDMA")
        if ((m and 0x03) != 0) parts.add("CDMA")
        return if (parts.isEmpty()) "0x" + m.toString(16) else parts.joinToString(" + ")
    }

    fun bandList(l: List<Int>): String = l.joinToString(", ") { "B$it" }

    // RSRP in dBm to a short word and bars.
    fun quality(rsrp: Double?): String {
        if (rsrp == null) return ""
        val n = when { rsrp >= -80 -> 5; rsrp >= -90 -> 4; rsrp >= -100 -> 3; rsrp >= -110 -> 2; else -> 1 }
        val word = when (n) { 5 -> "excellent"; 4 -> "good"; 3 -> "fair"; 2 -> "poor"; else -> "very poor" }
        return "▰".repeat(n) + "▱".repeat(5 - n) + " " + word
    }
}

// Parsing and lookup logic for the passive scanner. It reads the text that "dumpsys telephony.registry"
// prints (read-only, does not touch the connection) and the text "qband cell" prints.
object CellScan {
    const val UNKNOWN = Int.MAX_VALUE

    data class Cell(
        val earfcn: Int,
        val pci: Int,
        val ci: Long?, // 28-bit ECI: top 20 bits = tower (eNodeB), low 8 bits = cell on that tower
        val tac: Int?,
        val bwKhz: Int?,
        val rsrp: Int?,
        val rsrq: Int?,
        val registered: Boolean
    )

    data class Snapshot(
        val dataState: String?,
        val voiceState: String?,
        val carrier: String?,
        val ca: Boolean?,
        val serving: Cell?,
        val cells: List<Cell>
    )

    // Android reports "unknown" as Integer.MAX_VALUE.
    private fun known(v: String): Int? {
        val n = v.toLongOrNull() ?: return null
        return if (n >= UNKNOWN) null else n.toInt()
    }

    private val reIdentity = Regex("""mCellIdentity=CellIdentityLte:\{ mCi=(\d+) mPci=(\d+) mTac=(\d+) mEarfcn=(\d+) mBands=\[[^\]]*\] mBandwidth=(\d+)""")
    private val reInfo = Regex("""CellInfoLte:\{mRegistered=(\w+) .*?CellIdentityLte:\{ mCi=(\d+) mPci=(\d+) mTac=(\d+) mEarfcn=(\d+) mBands=\[[^\]]*\] mBandwidth=(\d+).*?rsrp=(-?\d+) rsrq=(-?\d+)""")
    private val reData = Regex("""mDataRegState=(\d+)\((\w+)\)""")
    private val reVoice = Regex("""mVoiceRegState=(\d+)\((\w+)\)""")
    private val reCarrier = Regex("""mOperatorAlphaLong=([^,]*),""")
    private val reCa = Regex("""isUsingCarrierAggregation=(true|false)""")

    // Input: the lines of the registry dump that contain mServiceState=, mCellIdentity= or mCellInfo=.
    fun parse(out: String): Snapshot {
        val lines = out.lines()
        val svc = lines.firstOrNull { it.contains("mServiceState=") } ?: ""
        val idLine = lines.firstOrNull { it.contains("mCellIdentity=") } ?: ""
        val infoLine = lines.firstOrNull { it.contains("mCellInfo=") } ?: ""

        val serving = reIdentity.find(idLine)?.let { m ->
            val g = m.groupValues
            val earfcn = known(g[4])
            val pci = known(g[2])
            if (earfcn == null || pci == null) null
            else Cell(earfcn, pci, known(g[1])?.toLong(), known(g[3]), known(g[5]), null, null, true)
        }

        val cells = reInfo.findAll(infoLine).mapNotNull { m ->
            val g = m.groupValues
            val earfcn = known(g[5])
            val pci = known(g[3])
            if (earfcn == null || pci == null) null
            else Cell(earfcn, pci, known(g[2])?.toLong(), known(g[4]), known(g[6]), known(g[7]), known(g[8]), g[1] == "YES")
        }.toList()

        return Snapshot(
            dataState = reData.find(svc)?.groupValues?.get(2),
            voiceState = reVoice.find(svc)?.groupValues?.get(2),
            carrier = reCarrier.find(svc)?.groupValues?.get(1)?.trim(),
            ca = reCa.find(svc)?.groupValues?.get(1)?.let { it == "true" },
            serving = serving ?: cells.firstOrNull { it.registered },
            cells = cells
        )
    }

    // Downlink EARFCN ranges and the band's lowest downlink frequency (3GPP TS 36.101, table 5.7.3-1).
    // Bands not listed here show as "B?".
    private class Range(val band: Int, val lo: Int, val hi: Int, val fdlLowMhz: Double)

    private val ranges = listOf(
        Range(1, 0, 599, 2110.0),
        Range(2, 600, 1199, 1930.0),
        Range(3, 1200, 1949, 1805.0),
        Range(4, 1950, 2399, 2110.0),
        Range(5, 2400, 2649, 869.0),
        Range(7, 2750, 3449, 2620.0),
        Range(8, 3450, 3799, 925.0),
        Range(12, 5010, 5179, 729.0),
        Range(13, 5180, 5279, 746.0),
        Range(14, 5280, 5379, 758.0),
        Range(17, 5730, 5849, 734.0),
        Range(18, 5850, 5999, 860.0),
        Range(19, 6000, 6149, 875.0),
        Range(20, 6150, 6449, 791.0),
        Range(25, 8040, 8689, 1930.0),
        Range(26, 8690, 9039, 859.0),
        Range(28, 9210, 9659, 758.0),
        Range(29, 9660, 9769, 717.0),
        Range(30, 9770, 9869, 2350.0),
        Range(32, 9920, 10359, 1452.0),
        Range(34, 36200, 36349, 2010.0),
        Range(38, 37750, 38249, 2570.0),
        Range(39, 38250, 38649, 1880.0),
        Range(40, 38650, 39649, 2300.0),
        Range(41, 39650, 41589, 2496.0),
        Range(42, 41590, 43589, 3400.0),
        Range(43, 43590, 45589, 3600.0),
        Range(46, 46790, 54539, 5150.0),
        Range(48, 55240, 56739, 3550.0),
        Range(66, 66436, 67335, 2110.0),
        Range(71, 68586, 68935, 617.0)
    )

    private fun rangeOf(earfcn: Int): Range? = ranges.firstOrNull { earfcn in it.lo..it.hi }

    fun bandLabel(earfcn: Int): String = rangeOf(earfcn)?.let { "B${it.band}" } ?: "B?"

    fun bandNumber(earfcn: Int): Int? = rangeOf(earfcn)?.band

    fun freqLabel(earfcn: Int): String =
        rangeOf(earfcn)?.let { String.format(Locale.US, "%.1f MHz", it.fdlLowMhz + 0.1 * (earfcn - it.lo)) } ?: "? MHz"

    fun eciLabel(ci: Long?): String =
        if (ci == null) "unknown" else "$ci (tower ${ci shr 8}, cell ${ci and 0xFFL})"

    fun bwLabel(kHz: Int?): String = when {
        kHz == null -> "?"
        kHz % 1000 == 0 -> "${kHz / 1000} MHz"
        else -> String.format(Locale.US, "%.1f MHz", kHz / 1000.0)
    }

    // ---- modem cell info: the text "qband cell" prints (NAS Get Cell Location Info, read-only) ----

    data class ModemCell(val earfcn: Int, val pci: Int, val rsrp: Double, val rsrq: Double, val rssi: Double)

    data class ModemInfo(
        val tac: Int,
        val ci: Long,
        val earfcn: Int,
        val servingPci: Int,
        val cells: List<ModemCell>, // measured cells, the serving one included
        val unmeasured: List<Int>   // EARFCNs the network lists that have no measured cell
    ) {
        fun serving(): ModemCell? = cells.firstOrNull { it.earfcn == earfcn && it.pci == servingPci }
    }

    private val reIntra = Regex("""LTE intra-frequency: idle=\d+ tac=(\d+) eci=(\d+) \(tower \d+ cell \d+\) earfcn=(\d+) serving pci=(\d+)""")
    private val reInterHdr = Regex("""LTE inter-frequency:""")
    private val reInterFreq = Regex("""^\s+EARFCN (\d+) priority \d+ cells \d+""")
    private val reCellLine = Regex("""^\s+PCI\s+(\d+)\s+RSRP\s+(-?\d+\.\d)\s+RSRQ\s+(-?\d+\.\d)\s+RSSI\s+(-?\d+\.\d)""")

    // Returns null when there is no intra-frequency block: the modem not answering, or no service.
    fun parseModem(out: String): ModemInfo? {
        var tac = -1
        var ci = -1L
        var earfcn = -1
        var servingPci = -1
        var section = 0 // 0 = none, 1 = intra-frequency, 2 = inter-frequency
        var interEarfcn = -1
        var interMeasured = false
        val cells = mutableListOf<ModemCell>()
        val unmeasured = mutableListOf<Int>()

        fun closeInter() {
            if (interEarfcn >= 0 && !interMeasured) unmeasured.add(interEarfcn)
            interEarfcn = -1
            interMeasured = false
        }

        for (line in out.lines()) {
            val intra = reIntra.find(line)
            if (intra != null) {
                val g = intra.groupValues
                tac = g[1].toIntOrNull() ?: -1
                ci = g[2].toLongOrNull() ?: -1L
                earfcn = g[3].toIntOrNull() ?: -1
                servingPci = g[4].toIntOrNull() ?: -1
                section = 1
                continue
            }
            if (reInterHdr.containsMatchIn(line)) {
                closeInter()
                section = 2
                continue
            }
            val freq = reInterFreq.find(line)
            if (freq != null && section == 2) {
                closeInter()
                interEarfcn = freq.groupValues[1].toIntOrNull() ?: -1
                continue
            }
            val c = reCellLine.find(line) ?: continue
            val g = c.groupValues
            val pci = g[1].toIntOrNull() ?: continue
            val rsrp = g[2].toDoubleOrNull() ?: continue
            val rsrq = g[3].toDoubleOrNull() ?: continue
            val rssi = g[4].toDoubleOrNull() ?: continue
            if (section == 1) {
                cells.add(ModemCell(earfcn, pci, rsrp, rsrq, rssi))
            } else if (section == 2 && interEarfcn >= 0 && !(pci == 0 && rsrp == 0.0)) {
                // All-zero entries are placeholders for frequencies the modem has not measured.
                cells.add(ModemCell(interEarfcn, pci, rsrp, rsrq, rssi))
                interMeasured = true
            }
        }
        closeInter()
        if (earfcn < 0 || servingPci < 0) return null
        return ModemInfo(tac, ci, earfcn, servingPci, cells, unmeasured)
    }
}

// Saved cell profiles: name, EARFCN, PCI and the TAC the cell normally shows. Stored as plain text
// lines "name|earfcn|pci|tac" in SharedPreferences.
object Profiles {
    data class Profile(val name: String, val earfcn: Int, val pci: Int, val tac: Int)

    private const val FILE = "mk_cells"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"

    // Cells seen at home on the Pixel 2 XL. Edit, delete or add more in the app.
    private val defaults = listOf(
        Profile("B7 home", 2850, 395, 11206),
        Profile("B20 home A", 6200, 200, 11206),
        Profile("B20 home B", 6200, 430, 11206),
        Profile("B3 test", 1301, 502, 11206)
    )

    fun load(ctx: Context): MutableList<Profile> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LIST, null)
            ?: return defaults.toMutableList()
        val out = mutableListOf<Profile>()
        for (line in raw.lines()) {
            val p = line.split("|")
            if (p.size != 4) continue
            val e = p[1].toIntOrNull() ?: continue
            val c = p[2].toIntOrNull() ?: continue
            val t = p[3].toIntOrNull() ?: continue
            out.add(Profile(p[0], e, c, t))
        }
        return if (out.isEmpty()) defaults.toMutableList() else out
    }

    fun save(ctx: Context, list: List<Profile>) {
        val raw = list.joinToString("\n") { "${it.name}|${it.earfcn}|${it.pci}|${it.tac}" }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_LIST, raw).apply()
    }

    fun activeName(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ACTIVE, null)

    fun setActive(ctx: Context, name: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, name).apply()
    }

    fun cleanName(s: String): String = s.replace("|", " ").replace("\n", " ").trim().take(24)

    // The 4 bytes of the modem's pci_lock item: EARFCN, then PCI, both 16-bit little-endian.
    fun lockBytes(p: Profile): String =
        String.format(Locale.US, "%02x %02x %02x %02x", p.earfcn and 0xff, (p.earfcn shr 8) and 0xff, p.pci and 0xff, (p.pci shr 8) and 0xff)
}

// Saved band sets: a name and the bands that were ticked. Lines "name|1,3,7".
object BandSets {
    data class BandSet(val name: String, val bands: List<Int>)

    private const val FILE = "mk_bandsets"
    private const val KEY = "list"

    fun load(ctx: Context): MutableList<BandSet> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return mutableListOf()
        val out = mutableListOf<BandSet>()
        for (line in raw.lines()) {
            val p = line.split("|")
            if (p.size != 2) continue
            val b = p[1].split(",").mapNotNull { it.trim().toIntOrNull() }
            if (b.isNotEmpty()) out.add(BandSet(p[0], b))
        }
        return out
    }

    fun save(ctx: Context, list: List<BandSet>) {
        val raw = list.joinToString("\n") { "${it.name}|${it.bands.joinToString(",")}" }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }

    fun cleanName(s: String): String = s.replace("|", " ").replace("\n", " ").trim().take(24)
}
