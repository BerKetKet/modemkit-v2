package com.minebutt.modemkit

object Reasons {
    // Spinner index 0 = leave Android's own reason; index k = reason k-1.
    val names = listOf(
        "Leave as Android sends it",
        "0 · Unknown",
        "1 · Normal (a fresh connection)",
        "2 · Shutdown (close the connection)",
        "3 · Handover (keep my address)"
    )
    val help = listOf(
        "Nothing is changed. The hook only writes down what Android asks for, so you can see the request.",
        "Tells the modem the reason is not known. Rarely useful.",
        "The normal case: open a brand new connection. The network picks the address, unless your account has a fixed one.",
        "Tells the modem to CLOSE the connection. Mobile data will probably not come up while this is on.",
        "Says “this connection already exists somewhere else, keep its address”. It is the only reason where Android passes an address to the modem, so it is the one to try. The network may refuse it."
    )
    fun label(code: String): String = when (code) {
        "0" -> "UNKNOWN (0)"; "1" -> "NORMAL (1)"; "2" -> "SHUTDOWN (2)"; "3" -> "HANDOVER (3)"
        "" -> "unchanged"; else -> code
    }
}

object Help {
    class Page(val title: String, val what: String, val steps: List<String>, val terms: List<String>)

    val glossary = linkedMapOf(
        // ---- addresses and connections ----
        "IP address" to "The number (like 10.20.30.40) that identifies your phone on the mobile network. Servers see it when you connect.",
        "Provisioned / static IP" to "An address your carrier reserved for you, so you get the same one every time instead of a random one. Your router can ask for it. This app tries to make the phone ask too.",
        "Target IP" to "The address you want. The IP page uses it. Type it on Home or pick one you saved.",
        "Saved IPs" to "Each address the phone receives is saved automatically (the last 30), so you can try to reuse one later.",
        "Interface (rmnet)" to "The phone's mobile-data “plug”. Linux names it rmnet_data0 or similar. Its address is the one the carrier gave you.",
        "Modem" to "The separate chip that talks to the cell tower. Android asks it to open data connections and to use certain bands.",
        "APN" to "Access Point Name: the “door” your carrier uses for a kind of connection, for example internet. The carrier often decides which address you get from the APN and login.",
        "Data connection (PDN / PDP)" to "One open data session between the phone and the carrier. Each has its own IP. 3G calls it a PDP context, 4G a PDN connection.",
        "Public address / CGNAT" to "Carriers often share one public address between many phones. The 10.x number is private; the public address is what websites see.",
        "Reconnect" to "Switching mobile data (or airplane mode) off and on closes the data connection and opens a new one, so the carrier hands out an address again.",
        "Alias" to "An extra address added by hand to the phone's network plug. The phone then sends traffic from it even though the carrier did not give it this time. It only works if the network does not block it, and it disappears on reconnect.",
        "Root / Magisk" to "Administrator access this app needs to run its commands. Magisk is the tool that grants it.",
        "QMI" to "Qualcomm's private language for apps to talk to the modem chip.",
        "WDS" to "The “data” part of QMI: starting and stopping connections and reading saved connection profiles.",
        "NAS" to "The “network” part of QMI: which bands and network types the modem may use, and what cells it hears.",
        "Profile" to "A saved connection recipe inside the modem (APN, login, address request). Changing one affects every connection that uses it. The app saves a copy first.",
        "IP request (IPv4 preference)" to "A field that says “I would like this address”. The network may agree or ignore it.",
        "IP family" to "4 means the short old-style address (10.20.30.40). 6 means the long new-style address.",
        "Login type (PAP / CHAP)" to "Two ways of sending a username and password to the carrier. Leave empty unless your carrier gave you credentials.",
        "Call end reason" to "The reason the network gave when it refused a request. The most useful clue when a test connection fails.",
        "Test connection (hold seconds)" to "The app opens its own short connection to test a request, keeps it open this many seconds, then closes it.",
        "Mux / endpoint" to "Advanced: which “lane” of the modem's data link to use. Leave empty unless you know it.",
        "Swap bytes" to "If an address shows reversed (4.3.2.1 instead of 1.2.3.4), turn this on.",
        "AT command" to "Old-style text commands (AT+...) sent to the modem. Handy, but this phone may not expose a usable port.",
        "CGDCONT / CGPADDR / CGACT" to "AT+CGDCONT defines a connection (APN and requested address). AT+CGPADDR reads the address. AT+CGACT lists or switches connections on.",
        "cid" to "Connection number inside the modem. 1 is usually Android's own internet connection, so changing it can cut your data.",
        "AT port" to "The device file used to send AT commands. “Find AT ports” tries the candidates and shows which answer.",
        "Hook / LSPosed" to "A way to watch and change what Android's phone program does. LSPosed (or Vector) loads this app's module inside that program. The hook catches Android's request to open a data connection.",
        "Scope" to "Which app the LSPosed module is allowed to touch. It must be com.android.phone.",
        "Phone process" to "The Android program (com.android.phone) that manages calls and data. Restarting it makes it load the module.",
        "Reason (Normal / Shutdown / Handover)" to "A number Android sends with every data request: Normal opens a new connection, Shutdown closes it, Handover moves an existing one and keeps its address.",
        "Handover" to "Moving a connection between networks (for example Wi-Fi calling to LTE) without changing the address. The hook uses it to ask for a specific address.",
        "Requested address" to "The address the hook adds to the request. Android only forwards it when the reason is Handover.",
        "Network type" to "The kind of mobile network you are on: LTE (4G), UMTS/HSPA (3G), GSM (2G).",
        "Logcat" to "Android's system diary. The hook writes a line for each data request under the name MODEMKIT.",
        // ---- bands and cells ----
        "LTE band" to "A slice of radio spectrum, named B plus a number, for example B7 (2600 MHz). The phone can only use bands both the modem and the carrier support. Low bands (700 to 900 MHz) reach farther and go through walls better. High bands (2300 MHz and up) are usually faster but cover less.",
        "Band lock" to "Telling the modem to use only the bands you choose. Useful to stay on a faster or steadier band. If you choose a band your carrier does not offer where you are, you lose service until you restore.",
        "Bands the modem reported" to "This app only lists bands that the modem itself has shown as enabled, so you never see bands that this phone cannot use. The list grows if the modem ever reports more.",
        "Modem-internal ids" to "Numbers above B88 that this modem lists (such as B250, B252 and B255). They are not normal LTE bands you pick between. They are kept so that “Restore” can put everything back exactly as it was.",
        "Original (baseline) bands" to "The set of bands the modem had enabled the first time one of these tools changed it. It is saved on the phone (qband.orig) and “Restore original bands” sends it back.",
        "Keep across reboots" to "“Permanent”: the change is stored in the modem and survives a reboot. Without it a band or mode lock disappears at the next reboot. To undo a permanent lock, restore with this box ticked.",
        "Network mode" to "Which generations the modem may use: Auto (2G, 3G and LTE), LTE only, or 3G/2G only. LTE only avoids slow fallbacks, but calls may fail where LTE is weak.",
        "Saved band sets" to "Named lists of ticked bands. Save the ones you use often and load them with one tap.",
        "Bands seen here" to "Bands where the modem or Android measured at least one cell, or that the network lists as neighbours. They are the ones worth trying where you are now.",
        "EARFCN" to "A channel number for one LTE frequency. The app turns it into a band and a frequency (for example 2850 is B7 at about 2620 MHz).",
        "PCI" to "Physical Cell ID (0 to 503): which sector of a tower you are hearing. Two cells on the same channel have different PCIs.",
        "TAC" to "Tracking Area Code: the area of the network the cell belongs to. It changes when you move between areas.",
        "ECI / tower / cell" to "The 28-bit cell identity. The top 20 bits are the tower (eNodeB) and the last 8 bits are the cell (sector) on that tower.",
        "RSRP / RSRQ / RSSI" to "Signal measurements. RSRP (dBm) is power: about -80 is great, -100 weak, -110 poor. RSRQ (dB) is quality: about -10 is good, -15 poor. RSSI is total received power.",
        "Carrier aggregation (CA)" to "Using two or more bands at the same time to get more speed.",
        "Neighbour cells" to "Other cells the phone can hear. The network lists them so the phone can move to a better one.",
        "Cell profile" to "A saved cell: a name, EARFCN, PCI and TAC. The Cells page compares what you are on with the active profile.",
        "Lock check" to "Compares the cell you are on with your active cell profile and says OK or WRONG CELL. It only reports; it does not force anything.",
        "Cell lock (EFS file)" to "Makes the modem stay on one cell (EARFCN plus PCI) by writing a small file in the modem's own storage with a PC tool (qcsuper). It needs a reboot and survives reboots. The app only prepares the command for you."
    )

    val pages = listOf(
        Page("Home",
            "Your dashboard: what address you have, what the radio is doing, how the phone is doing, and anything that could clash.",
            listOf(
                "Look at the big button at the top. Green = all good. Orange = check something. Red = act now; the text under it says what, and a fix button appears when the app can repair it. Tap the button to scan again.",
                "“What is on right now” lists every lock and setting: ● on, ○ off, ▲ needs a look, ✖ failed.",
                "“Changed without this app” appears when another app, a PC command, a reboot or the modem changed your bands or mode. If a band lock gets undone, the app puts it back (up to 3 times) unless you turn Auto-repair off.",
                "“Radio now” shows the band, cell and signal you have, whether bands are locked, and the lock check.",
                "Set your target IP in “What I'm asking for” if you use the IP page. Type it, pick a saved one, or tap “Use my current IP”.",
                "Read “Conflicts and warnings” before changing anything. Red means it will probably break something.",
                "Pick a page in the bottom bar: Bands, Cells or IP. Watch Logs afterward."),
            listOf("IP address", "Target IP", "LTE band", "Band lock", "APN", "Network type", "Root / Magisk")),
        Page("Bands",
            "Chooses which LTE bands and which network type the modem may use. Only bands this modem has reported are listed.",
            listOf(
                "Read “Right now” to see what is enabled. “Not locked” means all of the modem's bands are on.",
                "Open “Bands seen here” and tap a band to tick only that one, or tick bands yourself, or tap a preset or a saved set.",
                "Leave “Keep across reboots” off while testing. A lock then disappears at the next reboot.",
                "Tap Apply band lock. The answer appears in the result box and the Logs tab. If you lose service, tap Restore original bands or reboot.",
                "Network mode: Auto, LTE only, or 3G/2G. “Reset radio to defaults” undoes everything, including permanent locks."),
            listOf("LTE band", "Band lock", "Bands the modem reported", "Modem-internal ids", "Original (baseline) bands", "Keep across reboots", "Network mode", "Saved band sets", "Bands seen here", "QMI", "NAS")),
        Page("Cells",
            "Shows the cell you are connected to and its neighbours, and checks that you are on the cell you want.",
            listOf(
                "Read “Serving cell”: band, channel (EARFCN), sector (PCI), tower and signal. It updates every 5 seconds while this page is open.",
                "Pick a cell profile (or tap “Save current cell as a profile” while you are on a cell you like). The lock check then says OK or WRONG CELL.",
                "“Apply band lock for this profile” locks the modem to that cell's band. It does not pick the tower by itself.",
                "To stay on one exact cell, use “Cell lock: copy the PC command”, run it on a computer, then reboot. “Remove cell lock command” gives the undo."),
            listOf("EARFCN", "PCI", "TAC", "ECI / tower / cell", "RSRP / RSRQ / RSSI", "Carrier aggregation (CA)", "Neighbour cells", "Cell profile", "Lock check", "Cell lock (EFS file)")),
        Page("Keep my IP",
            "The gentlest IP route. It does not change the modem or Android. It reconnects so you can see what the network gives, and it can add a saved address to the phone by hand.",
            listOf(
                "Tap Reconnect and wait about 30 seconds. The new address appears on Home and is saved.",
                "Tap a saved address to make it your target.",
                "Tap Add alias, then Test. If the test replies from that address, the network still accepts it.",
                "Tap Remove alias when finished."),
            listOf("Reconnect", "Saved IPs", "Alias", "Public address / CGNAT", "Interface (rmnet)")),
        Page("Ask the modem",
            "Asks the modem chip directly for your address, bypassing Android. “Look” buttons only read. “Try” opens a short test connection. “Change” edits the modem's saved profile.",
            listOf(
                "Tap Status and List profiles to see what the modem has.",
                "Pick the profile Android uses (same APN) and tap Show this profile.",
                "Tap Start test connection. Read the answer in the result box. A refusal shows a call end reason.",
                "Only if you want the modem to remember the request, use “Write target IP into the profile”. Clear it afterward."),
            listOf("Modem", "QMI", "WDS", "Profile", "IP request (IPv4 preference)", "IP family", "Login type (PAP / CHAP)", "Call end reason", "Test connection (hold seconds)", "Mux / endpoint", "Swap bytes", "AT command", "CGDCONT / CGPADDR / CGACT", "cid", "AT port")),
        Page("Android hook",
            "Watches Android's own request to open a data connection and can change its reason and add your target address. This is the way Android normally hands the modem an address.",
            listOf(
                "In LSPosed (Vector), enable the Modem Kit module and tick only com.android.phone in its scope.",
                "Tap Restart phone process so the module loads.",
                "Choose a reason, tick “pass the target IP”, and tap Apply.",
                "Tap Reconnect, then Show hook log to see what Android sent. The settings reset when the phone reboots."),
            listOf("Hook / LSPosed", "Scope", "Phone process", "Reason (Normal / Shutdown / Handover)", "Handover", "Requested address", "Logcat")),
        Page("Logs",
            "A diary of everything the app did: what you tapped, the answer, errors, every change of your IP, and every change of cell, band or mode.",
            listOf(
                "Use the filter to show only errors, only hook and IP changes, or only cell and band changes.",
                "Tap Copy to send the log to a message or to me when something fails.",
                "Fetch hook log pulls the latest lines from the phone's system diary."),
            listOf("Logcat", "Call end reason", "Saved IPs", "Lock check"))
    )
}
