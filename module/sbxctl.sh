#!/system/bin/sh
DATA=/data/adb/sbx
BIN=/data/adb/modules/sbx_proxy/sing-box
[ -x "$BIN" ] || BIN=/data/adb/modules_update/sbx_proxy/sing-box
BB=/data/adb/ksu/bin/busybox
[ -x "$BB" ] || BB=busybox
RUN="$DATA/run"; STATE="$DATA/state"
mkdir -p "$DATA/log" "$STATE" "$RUN"
pidfile="$RUN/sing-box.pid"
update_desc(){ export KSU_MODULE=sbx_proxy; /data/adb/ksud module config set --temp override.description "$1" >/dev/null 2>&1; }
is_running(){ [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile" 2>/dev/null)" 2>/dev/null; }
clean_rules(){
  while ip rule del pref 9000 2>/dev/null; do :; done
  while ip rule del pref 9001 2>/dev/null; do :; done
  ip -6 rule del pref 9000 2>/dev/null
  ip -6 rule del pref 9001 2>/dev/null
  ip route flush table 2022 2>/dev/null
  ip -6 route flush table 2022 2>/dev/null
  ip link del tun0 2>/dev/null
  nft delete table inet sing-box 2>/dev/null
  nft delete table ip sing-box 2>/dev/null
}
agh_dns(){
  p=$(iptables -w 2 -t nat -L ADGUARD -n 2>/dev/null | grep -o "redir ports [0-9]*" | head -1 | awk '{print $3}')
  if [ -n "$p" ]; then AGH_HOST=127.0.0.1; AGH_PORT=$p; else AGH_HOST=223.5.5.5; AGH_PORT=53; fi
}
mode(){ sed -n 's/.*"mode":"\([a-z]*\)".*/\1/p' "$DATA/apps.json" 2>/dev/null | head -1; }
assemble(){
  agh_dns
  M=$(mode)
  APPR=$(cat "$DATA/apps_rules.json" 2>/dev/null)
  [ -z "$APPR" ] && APPR='{"user_id":[-1],"outbound":"direct"}'
  case "$M" in
    global) DR='{"user_id":[-1],"outbound":"direct"}'; FIN=proxy;;
    direct) APPR='{"user_id":[-1],"outbound":"direct"}'; DR='{"user_id":[-1],"outbound":"direct"}'; FIN=direct;;
    *) DR=$(cat "$DATA/direct_rules.json" 2>/dev/null); [ -z "$DR" ] && DR='{"user_id":[-1],"outbound":"direct"}'; FIN=proxy;;
  esac
  sed -e "s/__AGH_HOST__/$AGH_HOST/" -e "s/__AGH_PORT__/$AGH_PORT/" \
      -e "s|\"__APP_RULES__\"|$APPR|" -e "s|\"__DIRECT_RULES__\"|$DR|" \
      -e "s|\"__ROUTE_FINAL__\"|\"$FIN\"|" \
      "$DATA/config.json" > "$RUN/config.json"
}
start(){
  is_running && { echo "already-running pid=$(cat "$pidfile")"; return 0; }
  # 清理任何残留 sing-box 实例（pidfile 丢失/多实例场景）
  for p in $(pidof sing-box); do kill -9 "$p" 2>/dev/null; done
  sleep 1
  timeout 5 ip link del tun0 2>/dev/null
  clean_rules
  assemble || { echo "error-config"; return 1; }
  timeout 5 ip rule show > "$STATE/ip-rule.state" 2>/dev/null
  timeout 5 ip -6 rule show > "$STATE/ipv6-rules.state" 2>/dev/null
  timeout 5 ip route show > "$STATE/route-table.state" 2>/dev/null
  timeout 5 iptables-save > "$STATE/iptables.state" 2>/dev/null
  rm -f "$STATE/startup-error"
  try=0
  while [ $try -lt 3 ]; do
    try=$((try+1))
    setsid nohup "$BIN" run -c "$RUN/config.json" </dev/null > "$DATA/log/sing-box.log" 2>&1 &
    echo $! > "$pidfile"
    i=0
    ok=0
    while [ $i -lt 15 ]; do
      sleep 0.5
      kill -0 "$(cat "$pidfile")" 2>/dev/null && { ok=1; break; }
      i=$((i+1))
    done
    if [ "$ok" = "1" ]; then break; fi
    if grep -q "xtables" "$DATA/log/sing-box.log" 2>/dev/null; then
      sleep 2
      for p in $(pidof sing-box); do kill -9 "$p" 2>/dev/null; done
      clean_rules
      sleep 1
      continue
    fi
    tail -3 "$DATA/log/sing-box.log" 2>/dev/null; rm -f "$pidfile"; date > "$STATE/startup-error"; update_desc "启动失败，查看日志"; echo "error-start"; return 1
  done
  echo "pid=$(cat "$pidfile") since=$(date +%s) dns=$AGH_HOST:$AGH_PORT" > "$STATE/runtime.state"
  update_desc "运行中 · PID $(cat "$pidfile") · WebUI 管理"
  echo "running pid=$(cat "$pidfile")"
}
stop(){
  if is_running; then kill "$(cat "$pidfile")" 2>/dev/null; fi
  for p in $(pidof sing-box); do kill "$p" 2>/dev/null; done
  i=0; while [ $i -lt 20 ] && pidof sing-box >/dev/null 2>&1; do sleep 0.3; i=$((i+1)); done
  for p in $(pidof sing-box); do kill -9 "$p" 2>/dev/null; done
  rm -f "$pidfile"
  clean_rules
  update_desc "已停止 · 点击启动"
  echo "stopped"
}
restart(){ stop >/dev/null; sleep 1; start; }
status(){
  if is_running; then
    pid=$(cat "$pidfile")
    since=$(stat -c %Y /proc/$pid 2>/dev/null || date +%s)
    echo "running pid=$pid uptime=$(( $(date +%s) - since ))"
  else
    echo "stopped"
  fi
}
node(){
  t="$1"; [ -z "$t" ] && { echo "error-usage"; return 1; }
  if [ "$t" != "auto" ]; then
    grep -q "\"$t\"" "$DATA/nodes.json" || { echo "error-unknown"; return 1; }
  fi
  sed -i "s/\"default\":\"[^\"]*\"/\"default\":\"$t\"/" "$DATA/config.json"
  restart >/dev/null
  echo "node=$t"
}
now(){ $BB wget -T 4 -qO- "http://127.0.0.1:39090/proxies/proxy" 2>/dev/null | sed -n 's/.*"now": *"\([^"]*\)".*/\1/p' | head -1; }
delay(){ t="$1"; [ -z "$t" ] && { echo error; return 1; }; $BB wget -T 6 -qO- "http://127.0.0.1:39090/proxies/$t/delay?timeout=4000&url=http%3A%2F%2Fwww.gstatic.com%2Fgenerate_204" 2>/dev/null; }
traffic(){ $BB wget -T 2 -qO- "http://127.0.0.1:39090/connections" 2>/dev/null; }
labels(){ /system/bin/app_process -Djava.class.path="$DATA/tool.dex" / com.dsh.sbxtool.Main labels 2>&1; }
task(){ n="$1"; shift; rm -f "$STATE/$n.done" "$STATE/$n.out"; ( setsid sh -c "$*" > "$STATE/$n.out" 2>&1; echo 1 > "$STATE/$n.done" ) & echo started; }
taskstatus(){ n="$1"; if [ -f "$STATE/$n.done" ]; then echo done; else echo running; fi; cat "$STATE/$n.out" 2>/dev/null; }
nowauto(){ $BB wget -T 4 -qO- "http://127.0.0.1:39090/proxies/auto" 2>/dev/null | sed -n 's/.*"now": *"\([^"]*\)".*/\1/p' | head -1; }
subs(){ /system/bin/app_process -Djava.class.path="$DATA/tool.dex" / com.dsh.sbxtool.Main subs 2>&1; }
case "$1" in
  start) start;; stop) stop;; restart) restart;; status) status;;
  node) shift; node "$1";; log) shift; tail -n "${1:-80}" "$DATA/log/sing-box.log" 2>/dev/null;;
  now) now;; delay) shift; delay "$1";; traffic) traffic;;
  labels) labels;; subs) subs;; task) shift; n="$1"; shift; task "$n" "$*";; taskstatus) shift; taskstatus "$1";; nowauto) nowauto;;
  *) echo "usage: $0 start|stop|restart|status|node <tag>|now|delay <tag>|traffic|labels|subs|log [N]";;
esac
