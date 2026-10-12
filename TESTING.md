Modem Kit test checklist (Pixel 2 XL, rooted, Magisk). Tick a box only after seeing the result on the phone, not just a success message. Write the date and version next to anything that fails.

**Setup**
- [ ] Fresh install or update: within about 10 s of opening the app, the file /data/local/tmp/qband_run is several hundred bytes (not 114) and the log shows "Set up tools (automatic)"
- [ ] Root denied in Magisk: Home shows ACTION NEEDED with the no-root message
- [ ] Home Set up tools button rebuilds /data/local/tmp/q without errors

**Home dashboard**
- [ ] No target IP, no lock: big button is green (ALL GOOD)
- [ ] Target IP set but not active: button is amber and says the target is not active
- [ ] Status lines match reality: bands, mode, cell lock, target IP, hook
- [ ] After a band lock, change the bands from outside (adb qband set) and the app reports "changed behind its back" and auto-repairs, at most 3 times, and stops
- [ ] Copy a full report puts device, status, warnings and log lines on the clipboard

**Bands**
- [ ] Lock one reachable band: result box lists only that band, and ./modemkit-cli bands agrees
- [ ] Lock a band with no coverage: the app warns before applying
- [ ] Restore original bands: all bands come back
- [ ] Network mode auto / LTE only / 3G+2G each take effect and show in status
- [ ] Keep across reboots ticked: lock survives a reboot; restore with the box ticked clears it
- [ ] Region presets tick the expected bands
- [ ] A set that fails shows red NOT APPLIED and a log entry, never a green check

**Cells**
- [ ] Scan lists the serving cell and neighbours with EARFCN, PCI, signal
- [ ] Save a cell profile, select it, apply its band lock
- [ ] Cell lock request and its HOLDING / NOT holding verdict match what the phone is really on

**IP tab**
- [ ] Keep IP: reconnect by airplane toggle and by data off/on; new addresses appear in Saved IPs
- [ ] Tap a saved IP: it becomes the target and Home reflects it
- [ ] Add alias and Remove alias work; Test from the target IP prints a public address
- [ ] Modem: QMI status and List profiles print sensible output; Start network with a target works or fails with a readable error
- [ ] Hook: module enabled in LSPosed with scope com.android.phone; Apply sets the debug.modemkit.* properties; after a reconnect Home no longer says no data request seen
- [ ] Hook with Handover and an address keeps DNS and routes (data still works after reconnect)
- [ ] Warnings appear for SHUTDOWN, Handover without address, hook plus modem profile both on

**Logs**
- [ ] Every action from the app and the CLI appears with time, title and output; errors are marked

**modemkit-cli (from the PC)**
- [ ] status, bands, cell, ip, logs, report, poll, prepare
- [ ] lock 7 then bands then restore (use a band you can reach)
- [ ] mode lte then mode auto
- [ ] target-ip 10.1.2.3 changes the target in the app
- [ ] hook on --reason 3 --addr 10.1.2.3 and hook off (empty values must not break it)
- [ ] raw "ls -l /data/local/tmp" (a command with spaces must work)
- [ ] A normal app cannot broadcast to the receiver (manifest has android.permission.DUMP)
