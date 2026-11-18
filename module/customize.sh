#!/system/bin/sh
[ "$KSU" ] || abort "! 仅支持 KernelSU"
[ "$ARCH" = "arm64" ] || abort "! 仅支持 arm64"
DATA=/data/adb/sbx
mkdir -p "$DATA/log" "$DATA/rules" "$DATA/state" "$DATA/run"
[ -f "$DATA/config.json" ] || cp -f "$MODPATH/config.json" "$DATA/config.json"
[ -f "$DATA/nodes.json" ] || cp -f "$MODPATH/nodes.json" "$DATA/nodes.json"
[ -f "$DATA/settings.prop" ] || cp -f "$MODPATH/settings.prop" "$DATA/settings.prop"
cp -f "$MODPATH/rules/"*.srs "$DATA/rules/" 2>/dev/null
cp -f "$MODPATH/tool.dex" "$DATA/tool.dex" 2>/dev/null
for f in apps.json apps_rules.json direct_rules.json subs.txt; do
  [ -f "$DATA/$f" ] || cp -f "$MODPATH/$f" "$DATA/$f"
done
chmod 755 "$MODPATH/sing-box" "$MODPATH/sbxctl.sh" "$MODPATH/service.sh" "$MODPATH/uninstall.sh"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/sing-box" 0 0 0755
ui_print "- 安装完成：$DATA"
ui_print "- 重启后自动启动；WebUI 可开关自启"
