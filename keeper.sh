#!/system/bin/sh
# xgimi-keeper — keeps an XGIMI projector in the brightness mode you last chose,
# boosting the cooling fans so Performance mode can actually turn on and stay on.
#
# Runs ON the projector as the adb "shell" user (no root), started by the
# "Performance Keeper" app at boot. How it works:
#   * reads the real light-engine mode every TICK s:  gmpfUnit getDlpLumensMode
#   * switches mode exactly like the Brightness Mode menu does:
#       gmpfUnit setDlpLumensMode <0 Standard | 18 Performance | 3 Eco>
#     (the hardware service still refuses Performance if it is too hot — HL_HIGHTEMP)
#   * learns which mode YOU pick in the menu from live logcat
#   * if you want Performance and it is off (refused/dropped for heat):
#       save fan speeds -> both fans to max -> wait until cool -> switch Performance on
#   * while in Performance: hold fans at FAN_HOLD (default max); if FAN_HOLD is lower,
#       go to max whenever the internal temperature reaches HOT_SYS
#   * Standard/Eco: firmware fan speeds, or at least STD_FAN if set
#   * keeper stopped: firmware fan speeds restored
# Fans are only ever set ABOVE the firmware's own speeds, never below.

D=/data/local/tmp/xgimi-keeper
LOG=$D/keeper.log
EVQ=$D/events
PIDF=$D/keeper.pid
DESIRED_F=$D/desired
SNAP=$D/fan_snapshot
[ -f "$D/config" ] && . "$D/config"

MODE_STANDARD=0; MODE_PERFORMANCE=18; MODE_ECO=3
FAN_MAX=255
FAN_HOLD=${FAN_HOLD:-255}       # fan level while in Performance (lower = quieter)
STD_FAN=${STD_FAN:-0}           # minimum fan level in Standard/Eco (0 = firmware speeds)
TARGET_SYS=${TARGET_SYS:-51}    # try Performance once internal temp <= this...
TARGET_ENV=${TARGET_ENV:-35}    # ...and environment sensor <= this
MAX_WAIT=${MAX_WAIT:-240}       # ...or after this many seconds of max fans
RETRY=${RETRY:-90}              # seconds between attempts while boosting
MAX_TRIES=${MAX_TRIES:-6}       # then rest (fans restored) for REST seconds
REST=${REST:-1200}
HOT_SYS=${HOT_SYS:-55}
TICK=15

now() { date +%s; }
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }
rotate_log() {
  [ -f "$LOG" ] || return 0
  [ "$(wc -c < "$LOG")" -gt 262144 ] && tail -n 1500 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
}
desired() { cat "$DESIRED_F" 2>/dev/null || echo none; }
set_desired() { echo "$1" > "$DESIRED_F"; }
awake() { dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake'; }
boot_time() { echo $(( $(now) - $(cut -d. -f1 /proc/uptime) )); }

# ---------- hardware ----------
lumens_mode() { gmpfUnit getDlpLumensMode 2>/dev/null | tr -dc '0-9'; }
set_mode() { gmpfUnit setDlpLumensMode "$1" >/dev/null 2>&1; sleep 2; }
is_perf() { case "$1" in 1|18) return 0 ;; *) return 1 ;; esac; }
fanctl() { CLASSPATH=$D/fanctl.jar app_process32 /system/bin Fanctl "$@" 2>/dev/null; }
readings() {  # "sys s1 fan0 fan1"
  fanctl status | sed -n 's/.*sys=\([0-9-]*\).* s1=\([0-9-]*\).*fan0=\([0-9-]*\) fan1=\([0-9-]*\).*/\1 \2 \3 \4/p'
}
set_fans() { fanctl setfan 0 "$1" >/dev/null; fanctl setfan 1 "$1" >/dev/null; }
snapshot_fans() {  # remember firmware speeds once, before we touch them
  [ -s "$SNAP" ] && return 0
  set -- $(readings)
  [ -n "$4" ] && echo "$3 $4 $(now)" > "$SNAP" && log "saved firmware fan speeds: fan0=$3 fan1=$4"
}
restore_fans() {  # $1 = reason
  [ -s "$SNAP" ] || return 0
  reason=$1
  set -- $(cat "$SNAP")
  fanctl setfan 0 "$1" >/dev/null; fanctl setfan 1 "$2" >/dev/null
  rm -f "$SNAP"; FANS_SET=""; HOT=0
  log "fans back to firmware speeds (fan0=$1 fan1=$2) — $reason"
}

# ---------- learn the mode you pick in the menu (live logcat) ----------
watcher() {
  logcat -v brief -T 1 WindowSystem:E misckey:D '*:S' 2>/dev/null | while IFS= read -r line; do
    case "$line" in
      *"key:performance_key start"*|*"key:performance_18_key start"*) echo "select performance" >> "$EVQ" ;;
      *"key:standard_key start"*)                                     echo "select standard" >> "$EVQ" ;;
      *"key:energy_saving_key start"*|*"changeEcoMode: true"*)        echo "select eco" >> "$EVQ" ;;
      *HL_HIGHTEMP*)                                                  echo "hightemp" >> "$EVQ" ;;
    esac
  done
}
process_events() {
  [ -s "$EVQ" ] || return 0
  mv "$EVQ" "$EVQ.work" 2>/dev/null || return 0
  while read -r ev arg; do
    case "$ev" in
      select)
        [ "$arg" != "$(desired)" ] && log "you chose $arg in the menu — keeping it"
        set_desired "$arg"
        [ "$arg" = performance ] && REST_UNTIL=0 ;;
      request)                                   # from the app (or the Mac)
        set_desired "$arg"; REST_UNTIL=0; log "requested: $arg"
        case "$arg" in
          standard) is_perf "$(lumens_mode)" && set_mode $MODE_STANDARD ;;
          eco)      set_mode $MODE_ECO ;;
        esac ;;
      boost)
        BOOST_UNTIL=$(( $(now) + ${arg:-120} )); snapshot_fans; set_fans $FAN_MAX; FANS_SET=$FAN_MAX
        log "manual boost: fans at max for ${arg:-120} s" ;;
      hightemp) log "firmware: temperature too high for Performance" ;;
    esac
  done < "$EVQ.work"
  rm -f "$EVQ.work"
}

# ---------- main loop ----------
BOOST_START=0; LAST_TRY=0; TRIES=0; REST_UNTIL=0; FANS_SET=""; LAST_HOLD_CHECK=0; HOT=0; BOOST_UNTIL=0

stop_all() {
  kill "$WPID" 2>/dev/null
  pkill -f "logcat -v brief -T 1 WindowSystem" 2>/dev/null
  restore_fans "keeper stopped"
  log "keeper stopped"; rm -f "$PIDF"; exit 0
}

tick() {
  t=$(now)
  mode=$(lumens_mode)
  [ -n "$mode" ] || return 0

  # in standby the firmware owns the fans — don't touch them
  if ! awake; then BOOST_START=0; return 0; fi

  # manual "boost fans now"
  if [ $t -lt $BOOST_UNTIL ]; then return 0; fi
  if [ $BOOST_UNTIL -gt 0 ]; then BOOST_UNTIL=0; FANS_SET=""; log "manual boost finished"; fi

  # ----- Standard / Eco wanted -----
  if [ "$(desired)" != performance ]; then
    if [ "$STD_FAN" -gt 0 ]; then
      snapshot_fans
      if [ "$FANS_SET" != "std$STD_FAN" ] && [ -s "$SNAP" ]; then
        set -- $(cat "$SNAP"); f0=$1; f1=$2
        [ "$STD_FAN" -gt "$f0" ] && f0=$STD_FAN
        [ "$STD_FAN" -gt "$f1" ] && f1=$STD_FAN
        fanctl setfan 0 "$f0" >/dev/null; fanctl setfan 1 "$f1" >/dev/null
        FANS_SET="std$STD_FAN"; log "$(desired): fans at fan0=$f0 fan1=$f1 (minimum $STD_FAN)"
      fi
    else
      [ -s "$SNAP" ] && restore_fans "you chose $(desired)"
    fi
    BOOST_START=0; TRIES=0; return 0
  fi

  # ----- Performance wanted and ON -----
  if is_perf "$mode"; then
    [ $BOOST_START -gt 0 ] && log "✔ Performance is ON"
    BOOST_START=0; TRIES=0
    snapshot_fans
    if [ "$HOT" != 1 ] && [ "$FANS_SET" != "$FAN_HOLD" ]; then
      set_fans "$FAN_HOLD"; FANS_SET=$FAN_HOLD; log "holding fans at $FAN_HOLD while in Performance"
    fi
    if [ "$FAN_HOLD" -lt $FAN_MAX ] && [ $(( t - LAST_HOLD_CHECK )) -ge 60 ]; then
      LAST_HOLD_CHECK=$t; set -- $(readings)
      if [ -n "$1" ] && [ "$HOT" != 1 ] && [ "$1" -ge $HOT_SYS ]; then
        set_fans $FAN_MAX; FANS_SET=$FAN_MAX; HOT=1; log "internal ${1}°C ≥ $HOT_SYS — fans to max"
      elif [ -n "$1" ] && [ "$HOT" = 1 ] && [ "$1" -le $(( HOT_SYS - 5 )) ]; then
        HOT=0; FANS_SET=""; log "internal ${1}°C — back to fan level $FAN_HOLD"
      fi
    fi
    return 0
  fi

  # ----- Performance wanted but OFF: cool down, then switch it on -----
  [ $t -lt $REST_UNTIL ] && return 0
  if [ $BOOST_START -eq 0 ]; then
    snapshot_fans
    set_fans $FAN_MAX; FANS_SET=$FAN_MAX
    BOOST_START=$t; TRIES=0; LAST_TRY=0
    set -- $(readings)
    log "Performance is off — fans to MAX to cool down (internal ${1}°C, env ${2}°C)"
    return 0
  fi
  [ $(( t - LAST_TRY )) -lt $RETRY ] && return 0
  set -- $(readings)
  sys=${1:-99}; env=${2:-99}
  if { [ "$sys" -le $TARGET_SYS ] && [ "$env" -le $TARGET_ENV ]; } || [ $(( t - BOOST_START )) -ge $MAX_WAIT ]; then
    TRIES=$((TRIES + 1)); LAST_TRY=$t
    log "cooled to internal ${sys}°C / env ${env}°C — switching Performance on (try $TRIES)"
    set_mode $MODE_PERFORMANCE
    if is_perf "$(lumens_mode)"; then
      log "✔ Performance is ON"; BOOST_START=0; TRIES=0; FANS_SET=""
    elif [ $TRIES -ge $MAX_TRIES ]; then
      log "projector still refusing after $TRIES tries — resting $(( REST / 60 )) min with normal fans"
      restore_fans "resting"; BOOST_START=0; TRIES=0; REST_UNTIL=$(( t + REST ))
    else
      log "refused (still too hot) — fans stay at max, retry in $RETRY s"
    fi
  fi
}

main() {
  mkdir -p "$D"
  if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then echo "already running"; exit 0; fi
  echo $$ > "$PIDF"; : > "$EVQ"
  # a fan snapshot from before the last reboot is stale (firmware reset the fans)
  if [ -s "$SNAP" ]; then set -- $(cat "$SNAP"); [ "${3:-0}" -lt "$(boot_time)" ] && rm -f "$SNAP"; fi
  trap stop_all TERM INT HUP
  log "keeper started (pid $$) — keeping: $(desired), Performance fan level: $FAN_HOLD"
  watcher & WPID=$!
  while :; do
    kill -0 "$WPID" 2>/dev/null || { watcher & WPID=$!; }
    process_events
    tick
    rotate_log
    sleep $TICK
  done
}

main "$@"
