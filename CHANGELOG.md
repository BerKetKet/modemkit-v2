# Modem Kit Changelog

## [3.0] - 2026-10-12

### Summary
Major stability, modern Android 15 (Evolution X) compatibility, and remote automation update for Google Pixel 2 XL (`taimen`, Qualcomm MSM8998 / Snapdragon X16 LTE modem).

---

### Key Improvements & Fixes

#### 1. Android 15 & Qualcomm Kernel QMI Security (`AID_RADIO` UID 1001)
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
