#!/system/bin/sh
MODDIR=${0%/*}
[ -f "$MODDIR/sbxctl.sh" ] && sh "$MODDIR/sbxctl.sh" stop >/dev/null 2>&1
rm -rf /data/adb/sbx
