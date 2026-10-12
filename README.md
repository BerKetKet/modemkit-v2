# Modem Kit v3

One app for a rooted Pixel 2 XL (`taimen`, MSM8998 / Snapdragon X16 LTE modem, Magisk + Vector/LSPosed, Android 15 Evolution X). It combines two earlier apps:

* **ipreq**: keep or request a mobile IP address (reconnect + saved IPs, QMI/AT, LSPosed hook).
* **qbandpixel**: LTE band lock, network mode, cell scan and cell profiles.

Layout follows ipreq: plain Android views, five bottom tabs (Home, Bands, Cells, IP, Logs), a ? Help on every page, a conflict checker, one log for everything, and an adb CLI controller for PC debugging.

## Build & Install
    ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

The `qband` and `qwds` binaries are already bundled in `app/src/main/assets/`. To rebuild them from `native/`, run `./build_native.sh`.

## PC Command-Line Interface (`modemkit-cli`)
Control and debug the app directly from your PC terminal over adb:

```bash
./modemkit-cli status                  # Live status, health, bands & cell
./modemkit-cli cell                    # Scan serving cell & neighbor towers
./modemkit-cli bands                   # Show enabled, baseline & mode
./modemkit-cli lock 3,7,20 [--perm]    # Lock modem to specific bands
./modemkit-cli restore [--perm]        # Restore original baseline bands
./modemkit-cli mode lte [--perm]       # Switch RAT mode (auto, lte, 3g2g)
./modemkit-cli ip                      # Mobile IP interfaces
./modemkit-cli logs                    # Dump in-app activity logs
./modemkit-cli report                  # Generate full diagnostic report
./modemkit-cli poll                    # Trigger immediate app poll
```

## LSPosed / Vector
Enable the "Modem Kit" module in LSPosed. On v3, the scope **com.android.phone** is automatically detected via `@array/xposedscope`. The hook uses `debug.modemkit.*` properties and logcat tag `MODEMKIT`.

## Android 15 Architecture in v3
* **Helper scripts**: `qband_run` and `qwds_run` run the QMI tools as root and retry with `QUID=1001` (the tools own privilege drop) only if the QMI call is refused. Reads and band sets work as root on the Pixel 2 XL, so the earlier theory that QMI needs UID 1001 was wrong.
* **Vendor Libraries**: Automatically copies required Qualcomm vendor libraries (`libqmi*.so`, `libdiag.so`, `libdsutils.so`, `libidl.so`, `libmdmdetect.so`) into `/data/local/tmp/q` to satisfy Android 15 dynamic linker namespace isolation.
* **Handover Preservation**: `LinkProperties` instances are mutated instead of clobbered during mobile data handovers.

