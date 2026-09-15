#!/usr/bin/env bash
#
# Starts the MyLibrary emulator with the configuration that actually works on this machine.
#
# ## Why these flags
#
# `-gpu angle` is the whole point. On this host (Fedora 44, kernel 7.2.x, Mesa 26.1.8) the emulator
# 37.1.11 crashes with every other backend:
#
#   -gpu swiftshader_indirect   SIGSEGV in the emulator process (systemd records a core dump)
#   -gpu swiftshader            same
#   -gpu off / -gpu guest       same, with or without -no-window
#   -gpu host                   reaches window creation, then exits 1 silently
#   -gpu angle                  boots in ~23 seconds
#
# The headless build (`-no-window`, which selects `qemu-system-x86_64-headless`) segfaults
# regardless of backend, so this script runs the windowed emulator and lets it open a window on the
# desktop session.
#
# `-avd MyLibrary35` (API 35) rather than `MyLibrary` (API 36): the emulator itself logs
# "Guest Angle is still unstable for API > 35", and API 36 is above that line. API 35 is also what
# the app's own `targetSdk` is, so it is the more representative device anyway.
#
# ## Usage
#
#   tools/start-emulator.sh          start it detached, then wait for boot
#   tools/start-emulator.sh --stop   shut it down
#
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
AVD="${MYLIBRARY_AVD:-MyLibrary35}"
APP_ID="com.mylibrary.debug"

if [[ "${1:-}" == "--stop" ]]; then
    adb emu kill 2>/dev/null || true
    echo "emulator stopped"
    exit 0
fi

# Fully detached: the emulator must outlive this shell, and a plain `&` does not survive its parent
# exiting in this environment.
setsid nohup "$SDK/emulator/emulator" \
    -avd "$AVD" \
    -no-audio \
    -no-boot-anim \
    -no-snapshot \
    -no-metrics \
    -gpu angle \
    > /tmp/mylibrary-emulator.log 2>&1 < /dev/null &
disown

echo "emulator starting (log: /tmp/mylibrary-emulator.log)"

echo -n "waiting for boot "
for _ in $(seq 1 60); do
    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        echo " — ready"
        echo
        echo "Install and launch:"
        echo "  adb install -r app/build/outputs/apk/debug/app-debug.apk"
        echo "  adb shell am start -n $APP_ID/com.mylibrary.MainActivity"
        exit 0
    fi
    echo -n "."
    sleep 4
done

echo
echo "emulator did not finish booting — see /tmp/mylibrary-emulator.log" >&2
exit 1
