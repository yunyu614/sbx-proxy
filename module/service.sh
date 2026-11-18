#!/system/bin/sh
MODDIR=${0%/*}
DATA=/data/adb/sbx
CTL="$MODDIR/sbxctl.sh"
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do sleep 2; i=$((i+1)); done
sleep 8
auto_on(){ grep -q "^autostart=0" "$DATA/settings.prop" 2>/dev/null && return 1 || return 0; }
subs_auto(){ grep -q "^subs_auto=1" "$DATA/settings.prop" 2>/dev/null; }
auto_on && sh "$CTL" start >/dev/null 2>&1
fails=0
last_if=""
subs_ts=0
while :; do
  sleep 10
  # 网络接口变化 -> 重启 sing-box 适配新网络
  iface=$(ip route show default 2>/dev/null | grep -v tun0 | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}' | head -1)
  if [ -n "$iface" ] && [ -n "$last_if" ] && [ "$iface" != "$last_if" ] && auto_on; then
    sh "$CTL" restart >/dev/null 2>&1
  fi
  last_if="$iface"
  auto_on || { fails=0; continue; }
  if ! sh "$CTL" status 2>/dev/null | grep -q running; then
    [ $fails -lt 5 ] && sh "$CTL" start >/dev/null 2>&1
    fails=$((fails+1))
    sleep $((fails*10))
  else
    fails=0
  fi
  # AdGuardHome DNS 端口变化（AGH 重启/随机端口）→ 重启 sing-box 重新指向
  if sh "$CTL" status 2>/dev/null | grep -q running; then
    agp=$(iptables -w 2 -t nat -L ADGUARD -n 2>/dev/null | grep -o "redir ports [0-9]*" | head -1 | awk '{print $3}')
    curp=$(sed -n 's/.*dns=127.0.0.1:\([0-9]*\).*/\1/p' "$DATA/state/runtime.state" 2>/dev/null)
    if [ -n "$agp" ] && [ -n "$curp" ] && [ "$agp" != "$curp" ]; then
      sh "$CTL" restart >/dev/null 2>&1
    fi
  fi
  # 定时更新订阅（每 24 小时）
  if subs_auto; then
    now=$(date +%s)
    if [ $((now - subs_ts)) -ge 86400 ]; then
      subs_ts=$now
      sh "$CTL" subs >/dev/null 2>&1 && sh "$CTL" restart >/dev/null 2>&1
    fi
  fi
done
