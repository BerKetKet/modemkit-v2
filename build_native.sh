#!/bin/sh
# Builds the helper binaries into app/src/main/assets. Needs the Android NDK (set NDK=/path/to/ndk).
# qband.c is your existing source, copy it next to qwds.c first if you want it bundled too.
set -e
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
for f in qwds qband; do
  [ -f native/$f.c ] && $CC -O2 -o app/src/main/assets/$f native/$f.c -ldl
done
ls -l app/src/main/assets
