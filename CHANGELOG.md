# Modem Kit Changelog

## [3.0] - 2026-10-12

### Summary
Major stability, modern Android 15 (Evolution X) compatibility, and remote automation update for Google Pixel 2 XL (`taimen`, Qualcomm MSM8998 / Snapdragon X16 LTE modem).

---

### Key Improvements & Fixes

#### 1. Android 15 & Qualcomm Kernel QMI Security (`AID_RADIO` UID 1001)

> **Erratum (3.1.1):** the root cause below was never verified and is wrong. `get` works as root, with `QUID=1001` and with `su 1001`. The band lock failed because the `su 1001` wrapper passed a stray `dummy` word to `qband` as its command, so the set never ran, and the app reported success anyway. See 3.1.1.

* **Root Cause Fixed:** On Qualcomm basebands running Linux kernels, the MSM IPC Router (`net/ipc_router/ipc_router_core.c` / `msm_ipc_router_security.c`) restricts `sendto()` on `AF_MSM_IPC` (socket family 27) QMI ports to `AID_RADIO` (`UID 1001`) or `AID_SYSTEM` (`UID 1000`). Executing `qband` or `qwds` purely as root (`UID 0`) resulted in `send failed rc=-16` (`-EPERM: permission failure for qband`).
* **Solution:** Introduced dedicated executable runner wrappers (`/data/local/tmp/qband_run` and `/data/local/tmp/qwds_run`) that launch under `su 1001 -c` while preserving the unconfined root SELinux domain (`u:r:magisk:s0`). This guarantees full access to both QMI and IPC sockets across all operations.

#### 2. Comprehensive Vendor Library Ingestion
* **Root Cause Fixed:** Android 15 Bionic dynamic linker strictly enforces linker namespace isolation. Binaries executed from `/data/local/tmp` run inside the `default` namespace and are prohibited from searching `/vendor/lib64`. Previously, only `libqmi*.so` were copied, causing `dlopen` failures for missing transitive dependencies (`libdiag.so`, `libdsutils.so`, `libidl.so`, `libmdmdetect.so`).
* **Solution:** Updated `prepare()` in `MainActivity` to automatically pull all required Qualcomm vendor libraries from `/vendor/lib64/` into `/data/local/tmp/q/`, set standard permissions (`chmod 644`), and grant read/write access to `/dev/diag` (`chmod 666`).

#### 3. Automatic LSPosed Scope Resolution
* **Root Cause Fixed:** `AndroidManifest.xml` previously declared `<meta-data android:name="xposedscope" android:value="com.android.phone" />`. LSPosed's module parser expects an array resource rather than a plain string, causing LSPosed to ignore the scope and require manual user intervention.
* **Solution:** Added `app/src/main/res/values/arrays.xml` defining `@array/xposedscope` and updated the manifest to reference `android:resource="@array/xposedscope"`.

#### 4. LSPosed Hook Telephony Handover Preservation
* **Root Cause Fixed:** When mobile data is handed over between radio access technologies (`reason == 3 HANDOVER`), Android telephony passes the active `LinkProperties` containing interface routes, MTU, and DNS servers. `HookEntry.kt` previously overwrote `a[5]` with a blank `LinkProperties` instance, clobbering DNS and routes.
* **Solution:** `HookEntry.kt` now preserves and mutates existing `LinkProperties` when present (`addLinkAddress` on existing instance) and only instantiates a new one if `a[5]` is null. Also added defensive reflective fallback for APN resolution (`getApnSetting()?.getApnName()`).

#### 5. Fallback Serving Cell Resolution on Android 15
* **Root Cause Fixed:** On modern Android ROMs, `dumpsys telephony.registry` frequently reports `mCellIdentity=null` in the global registry line while supplying the serving cell under `mCellInfo` (`mRegistered=YES`).
* **Solution:** `Radio.kt` now falls back to `cells.firstOrNull { it.serving }` if `serving` is null, ensuring tower identity, PCI, TAC, and EARFCN are always populated.

#### 6. Process Timeout Protection
* **Root Cause Fixed:** `MainActivity.su()` previously waited indefinitely with `p.waitFor()`. If a command or shell prompt hung, background threads stalled and halted subsequent polling cycles.
* **Solution:** Added a 15-second execution timeout (`p.waitFor(15, TimeUnit.SECONDS)`).

#### 7. PC Command-Line Interface (`modemkit-cli` & `CliReceiver`)
* **New Feature:** Added `CliReceiver` broadcast receiver (`com.minebutt.modemkit.CLI`) allowing headless control and automated debugging from a connected PC over adb:
  - `./modemkit-cli status` — Live radio status, active bands, cell & health
  - `./modemkit-cli cell` — Scan serving cell & neighbor towers
  - `./modemkit-cli bands` — Enabled bands, baseline original bands, mode
  - `./modemkit-cli lock <bands> [--perm]` — Lock modem to bands (e.g. 3,7,20)
  - `./modemkit-cli restore [--perm]` — Restore original baseline bands
  - `./modemkit-cli mode <auto|lte|3g2g> [--perm]` — Set network mode
  - `./modemkit-cli ip` — Inspect mobile IP interfaces
  - `./modemkit-cli logs` — Dump recent in-app activity logs
  - `./modemkit-cli report` — Generate complete diagnostic report
  - `./modemkit-cli poll` — Trigger background poll
  - `./modemkit-cli raw "<cmd>"` — Execute arbitrary root command

## [3.1] - 2026-10-12
- CliReceiver now requires android.permission.DUMP (adb shell only).
- qband_run/qwds_run: root first, uid 1001 only as a fallback.
- Removed magiskpolicy rules and chmod 666 /dev/diag from setup.
- su() timeout really works (70 s default).
- Home no longer shows ALL GOOD while the target IP is not active.

## [3.1.1] - 2026-10-12
- Band lock actually runs again: the uid fallback no longer injects a stray "dummy" argument (qband treated it as the command and silently did nothing).
- A set that never printed "set result=" is now reported as a failure instead of a success.
- Helper scripts are reinstalled automatically after an app update.
- modemkit-cli quotes its arguments properly (raw and hook with spaces or empty values).

## [Unreleased] - 2026-10-13

Everything since 3.1.1. The 3.0, 3.1 and 3.1.1 notes above are unchanged.

### Added

**Settings page**
- New gear button (⚙ Settings) in the title bar, next to Help.
- **Show theoretical bands** (off by default). Theoretical bands are bands the modem supports but nothing around you has shown.
- **Show only bands found here** (off by default). Shows only the bands from the last location scan.
- Shows the last scan time and the bands it found, the number of cells measured, and how many the last scan missed.

**Location scan**
- **Scan this location** measures what is really around the phone. It switches every band on for about 45 seconds, records the bands and cells it sees, then puts the lock back the way it was.
- Asks for confirmation first, and nothing changes until you press Scan.
- Refuses to run when root or the helper tools are missing, when the radio scanner is off, when "Keep across reboots" is on, when the modem's bands have not been read, or when another band command is running.
- Auto-repair pauses during a scan so it cannot fight the unlock.
- If the app is closed during a scan, the previous lock is put back the next time the app starts.
- A scan that measures nothing keeps the previous result.

**Cell memory, serving towers, and rescan reminder**
- Every cell the modem measures is saved on the phone (up to 2000, oldest dropped first). A cell is identified by EARFCN and PCI, because the modem does not report a tower ID for neighbour cells.
- The tower ID of the cell you camp on is also saved (up to 500). That gives a real count of the towers you have actually used.
- The Bands page shows an info note when the phone measures cells the last scan did not see. The note clears after the next scan.

**Band descriptions**
- Each band on the Bands page has a plain-language line: frequency, duplex type (FDD or TDD), and what it is good for (reach, capacity, or download only).
- Modem-internal ids are labelled as not real radio bands.

**Presets by function**
- Long reach (below 1 GHz), Mid range (1 to 2 GHz), High capacity (2 GHz and up), FDD only, and TDD only.

**Bands page**
- "Showing X of Y bands" line explains what is hidden and why.
- Checkboxes are one column, so each description fits.

**PC command line (`modemkit-cli`)**
- `./modemkit-cli scan --yes` — run a location scan. Without `--yes` it refuses, because the scan switches every band on for about 45 seconds.
- `./modemkit-cli local` — last scan time, bands found here, and the two band settings.
- `./modemkit-cli seen` — cells measured on the phone, how many the last scan missed, the serving towers you have camped on, and the 30 most recent cells.
- `./modemkit-cli setting [show_theory|show_local on|off]` — show both settings, or change one.

**Vendor libraries**
- The app now resolves the Qualcomm library dependencies itself (`deps_resolve`, shipped in the APK). It copies missing libraries from `/vendor/lib64`, follows their dependencies, and reports anything it cannot find. This replaces the manual `deps.sh` that lived in `/data/local/tmp`.

### Changed

- The Bands page lists only the bands that are seen around you, plus any enabled while a lock is on. Theoretical bands are hidden unless the setting above is on.
- "All modem bands" becomes "All seen bands", or "All bands found here" in that mode, unless theoretical bands are on.
- The section heading "Bands the modem reported" is now "Bands".
- Setup output puts each line on its own line.

### Fixed

- Home no longer turns RED because of a selected cell profile whose band is not enabled, unless a cell lock was actually requested for it.
- After a restore, the Bands page showed the modem's full baseline (almost every band) as available. Now a band is listed only when it is seen, found by a scan, or enabled while a lock is on.
- Ticked bands the page was not showing could still be applied. Any tick that is not visible is dropped, so Apply only sends what you can see.
- Presets and the "all" button could tick bands that were not on the page. They now only pick bands the page shows.
- The setup output and the vendor library list no longer depend on a manually created `deps.sh`.

### Removed

- The regional presets (Africa / ME, India, China) and the regional wording in band descriptions.
- Modem-internal ids 250, 252 and 255 from the default band list. They are still kept for restore and still appear if the modem has them enabled.
- The manual `deps.sh` and `deps.seen` from `/data/local/tmp`.

### Known limits

- Neighbour cells are counted by EARFCN and PCI. The modem query does not report their tower IDs, so two neighbour cells may belong to the same tower or to different ones.
- The band list belongs to the phone, not to a place. After you move, the list is from the old spot until you scan again. The reminder tells you when that is likely.
- A scan is a snapshot of what the modem can measure at that moment. The result can differ at another time of day.
- A scan drops the connection for a short time. Do not run one during a call or a download.
- Tested on one phone only (Pixel 2 XL, Android 15, root through Magisk). The band numbers are standard LTE definitions. The app does not check which bands a given carrier actually uses.
- Band descriptions are general. They say what a band is good for, not whether a particular carrier uses it.

### Docs

- Corrected the false UID 1001 root cause in the 3.0 notes and the README.
- Added TESTING.md, a per-feature checklist.
