#!/system/bin/sh
# Bring-up log collector (userdebug/eng builds only; see debug/Android.bp and sepolicy/debug).
# The e-ink panel shows nothing until the display stack works, and adb in Android may not work
# either, so every boot leaves logs in /metadata/hbdebug. Recovery's adb can read them:
#   adb shell 'mount -t ext4 /dev/block/by-name/metadata /metadata'; adb pull /metadata/hbdebug
# If /metadata/hbdebug.reboot exists, the marker is removed and the phone reboots into recovery
# once the logs are written. That gives a button-free flash -> boot -> read-logs loop.
# /metadata is only 32 MB and holds the encryption keys: keep this well under 10 MB.

D=/metadata/hbdebug
RUN_SECONDS=240

rm -rf "$D.old"
[ -d "$D" ] && mv "$D" "$D.old"
mkdir -p "$D"
echo "start uptime=$(cat /proc/uptime)" > "$D/status"

# Bigger in-memory log buffers, so `adb logcat -d` from the host still has the early boot.
logcat -b all -G 16M 2>/dev/null
logcat -b all -v threadtime -f "$D/logcat.txt" -r 1536 -n 2 &
LOGCAT=$!

n=0
while [ $((n * 15)) -lt $RUN_SECONDS ]; do
    dmesg > "$D/dmesg.txt" 2>&1
    getprop > "$D/props.txt" 2>&1
    ps -AZ > "$D/ps.txt" 2>&1
    echo "loop $n uptime=$(cat /proc/uptime)" >> "$D/status"
    # Debug builds: allow `adb root` only when explicitly armed via /metadata/hbdebug.adbroot.
    if [ -z "$ADBROOT_DONE" ] && [ -e /metadata/hbdebug.adbroot ] && [ -d /data/adbroot ]; then
        echo 1 > /data/adbroot/enabled && setprop ctl.restart adb_root && ADBROOT_DONE=1
        echo "adb root enabled: $ADBROOT_DONE" >> "$D/status"
    fi
    n=$((n + 1))
    sleep 15
done

dmesg > "$D/dmesg.txt" 2>&1
kill $LOGCAT
wait "$LOGCAT" 2>/dev/null || true
echo "done uptime=$(cat /proc/uptime)" >> "$D/status"
sync

if [ -e /metadata/hbdebug.reboot ]; then
    rm -f /metadata/hbdebug.reboot
    echo "rebooting to recovery (marker)" >> "$D/status"
    sync
    setprop sys.powerctl reboot,recovery
fi
