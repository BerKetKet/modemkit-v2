# Modem Kit

One app for a rooted Pixel 2 XL (Magisk + Vector/LSPosed, Android 15). It combines two earlier apps without changing them:

* **ipreq**: keep or request a mobile IP address (reconnect + saved IPs, QMI/AT, LSPosed hook).
* **qbandpixel**: LTE band lock, network mode, cell scan and cell profiles.

Layout follows ipreq: plain Android views, five bottom tabs (Home, Bands, Cells, IP, Logs), a ? Help on every page, a conflict checker and one log for everything.

## Build
    cp ../ipreq/local.properties .        # or create it with sdk.dir=...
    ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

The `qband` and `qwds` binaries are already in `app/src/main/assets/`. To rebuild them from `native/`, run `./build_native.sh`.

## LSPosed / Vector
Enable the "Modem Kit" module, scope **com.android.phone** only. The hook uses `debug.modemkit.*` properties and logcat tag `MODEMKIT`, so it does not clash with ipreq. Do not enable both modules at once.

## Shared with the old apps
Binaries and libraries are copied to `/data/local/tmp` (same place as the old apps), and the saved original band set is `/data/local/tmp/qband.orig`. Preferences, saved IPs, cell profiles and band sets are separate.

## Home dashboard
Big status button (green / orange / red), a list of what is on right now, and a list of changes made outside the app. Band locks are checked after every apply and retried once; if something later undoes a lock the app re-applies it up to 3 times (Auto-repair, can be turned off). "Copy a full report" on Home puts device, status, warnings and recent log lines on the clipboard for bug reports.
