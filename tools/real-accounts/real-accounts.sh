#!/usr/bin/env bash
# Pruebas con las cuentas reales de prueba, conducidas desde el PC por adb.
#
# Corre sobre la app debug que la persona dejó conectada a mano en un emulador, con su destino:
# WebDAV o la nube del teléfono. Nunca lee, imprime ni pasa la credencial: el trabajo de respaldo
# de producción la abre solo. Todo lo que escribe este guion son conteos, horas, duraciones,
# estados y resultados.
#
# Uso:  real-accounts.sh -s <serial> <orden> [opciones]
#
#   preflight             qué destino tiene conectado la app (lo lee de la app, sin la credencial)
#   seed [--volume]       siembra un negocio inventado: pequeño por defecto, o el volumen de referencia
#   slots                 lecturas de las ranuras: permisos, tamaño estable, relectura, raíces, flags
#   run                   la corrida comprimida de varios días con el trabajo de respaldo de producción
#   drill [--volume]      los pasos del simulacro que caben en un teléfono
#   scenario <nombre>     airplane | reboot | provider-off (solo ranuras)
#   collect               copia al PC las sumas de las ranuras (datos inventados) y el resumen
#
# Opciones de run: DAYS=8 RUNS_PER_DAY=7 MIN_VALID=50 DEADLINE_MIN=40 MODE=A (o B, que no abre la app)
# Variables: ADB (por defecto adb), OUT (por defecto ./real-accounts-out).
#
# Cada emulador va con su serial y su carpeta de salida: dos copias de este guion corren a la vez,
# una por emulador, sin pisarse. Nunca use Gradle para estas pruebas: connectedAndroidTest
# desinstala la app al terminar y se lleva el destino conectado.
set -u

ADB="${ADB:-adb}"
SERIAL=""
if [ "${1:-}" = "-s" ]; then SERIAL="$2"; shift 2; fi
[ -n "$SERIAL" ] || { echo "Falta -s <serial>" >&2; exit 2; }
CMD="${1:-}"; shift || true
PKG="io.github.yesidrobayo.shopledger.debug"
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
TESTS="io.github.yesidrobayo.shopledger.realaccounts"
OUT_DIR="${OUT:-./real-accounts-out}/$SERIAL"
mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/session.log"

a() { "$ADB" -s "$SERIAL" "$@"; }
sh_() { a shell "$@" | tr -d '\r'; }
now_ms() { echo $(( $(sh_ date +%s) * 1000 )); }
note() { echo "$(date -u +%FT%TZ) $*" | tee -a "$LOG"; }

# Corre una prueba instrumentada. Devuelve 0 si pasó. Guarda la salida en el registro.
instr() {
  local class="$1"; shift
  local out
  out=$(a shell am instrument -w -e realAccounts true "$@" -e class "$TESTS.$class" "$RUNNER" \
    | tr -d '\r')
  echo "$out" >> "$OUT_DIR/instrument.log"
  echo "$out" | grep -q '^OK (' && ! echo "$out" | grep -q 'FAILURES'
}

# Las líneas de las pruebas desde la hora [segundos] del dispositivo.
lines_since() { a logcat -d -v epoch -T "$1.000" -s RealAccounts:I | tr -d '\r'; }

# El valor de una clave en una línea `clave=valor`.
field() { echo "$1" | grep -o " $2=[^ ]*" | head -1 | cut -d= -f2; }

dev_s() { sh_ date +%s; }

preflight() {
  local t; t=$(dev_s)
  instr RealPreflightTest "$@" || { note "preflight: falla"; lines_since "$t" | tail -3; return 1; }
  local line; line=$(lines_since "$t" | grep 'event=preflight' | tail -1)
  KIND=$(field "$line" kind)
  note "preflight: kind=$KIND link=$(field "$line" link) policy=$(field "$line" policy)" \
    "status=$(field "$line" status) grantsDrive=$(field "$line" grantsDrive)"
  [ "$KIND" = "WEBDAV" ] || [ "$KIND" = "DOCUMENT_SLOTS" ]
}

seed() {
  # El negocio inventado de VolumeSeedTest. El pequeño basta para que cada subida tarde segundos.
  local args=(-e volumeProducts 120 -e volumeSales 600 -e volumePurchases 40
    -e volumeExpenses 60 -e volumeDays 60)
  if [ "${1:-}" = "--volume" ]; then
    # El volumen de referencia, el mismo de las mediciones de rendimiento.
    args=(-e volumeProducts 5000 -e volumeSales 100000 -e volumePurchases 10000
      -e volumeExpenses 20000 -e volumeDays 1826)
  fi
  note "seed: ${1:-pequeño}"
  local out
  out=$(a shell am instrument -w -e volumeSeed true "${args[@]}" \
    -e class io.github.yesidrobayo.shopledger.perf.VolumeSeedTest "$RUNNER" | tr -d '\r')
  echo "$out" >> "$OUT_DIR/instrument.log"
  echo "$out" | grep -q '^OK (' || { note "seed: falla"; return 1; }
  note "seed: listo"
}

# --- Control de energía del emulador -------------------------------------------------------

screen_off() { sh_ input keyevent KEYCODE_SLEEP >/dev/null; }
screen_on() { sh_ input keyevent KEYCODE_WAKEUP >/dev/null; sh_ wm dismiss-keyguard >/dev/null; }
deep_state() { sh_ dumpsys deviceidle get deep; }
force_idle() { sh_ dumpsys deviceidle force-idle >/dev/null; }
# Sale de Doze: suelta el forzado y prende y apaga la pantalla, que lo saca del reposo profundo.
leave_idle() {
  sh_ dumpsys deviceidle unforce >/dev/null
  screen_on; sleep 1; screen_off
}
set_bucket() {
  if [ "$1" = "active" ] || [ "$1" = "working_set" ] || [ "$1" = "frequent" ] ||
    [ "$1" = "rare" ] || [ "$1" = "restricted" ]; then
    sh_ am set-standby-bucket "$PKG" "$1" >/dev/null
  fi
}
bucket_now() { sh_ am get-standby-bucket "$PKG"; }

# Abre una ventana de mantenimiento de Doze: avanza con `step` hasta IDLE_MAINTENANCE.
open_maintenance() {
  local i
  for i in 1 2 3 4 5 6 7 8; do
    [ "$(deep_state)" = "IDLE_MAINTENANCE" ] && return 0
    sh_ dumpsys deviceidle step >/dev/null
  done
  [ "$(deep_state)" = "IDLE_MAINTENANCE" ]
}

# --- Una ejecución ---------------------------------------------------------------------------

# Espera el final de una ejecución del trabajo de respaldo en el registro de la app, desde [s].
# Imprime "OUTCOME|detalle|t_inicio_ms|t_fin_ms". Las líneas del registro de la app no llevan
# datos del negocio: solo motivos, códigos y la generación.
await_outcome() {
  local since="$1" timeout_s="$2" phase="$3" waited=0 start_ms="" retries=0 last=""
  while [ "$waited" -lt "$timeout_s" ]; do
    local logs; logs=$(a logcat -d -v epoch -T "$since.000" -b main,system | tr -d '\r')
    if [ -z "$start_ms" ]; then
      local sp; sp=$(echo "$logs" | grep -E "Start proc [0-9]+:$PKG/" | head -1)
      [ -n "$sp" ] && start_ms=$(echo "$sp" | awk '{printf "%d", $1 * 1000}')
    fi
    local outcome
    # Prefijos sin tildes, para no depender de la configuración regional del PC.
    local pattern="ShopLedger: (Copia verificada|Copia confirmada solo|Respaldo sin nada"
    pattern="$pattern|Respaldo para m|Respaldo detenido|Falla no prevista del trabajo)"
    outcome=$(echo "$logs" | grep -E "$pattern" | tail -1)
    if [ -n "$outcome" ] && [ "$outcome" != "$last" ]; then
      last="$outcome"
      local t_ms; t_ms=$(echo "$outcome" | awk '{printf "%d", $1 * 1000}')
      local text; text=$(echo "$outcome" | sed 's/.*ShopLedger: //; s/ /_/g')
      case "$outcome" in
        *"Copia verificada"*) echo "VERIFIED|$text|${start_ms:--}|$t_ms"; return 0 ;;
        *"Copia confirmada solo"*) echo "UNRESTORABLE|$text|${start_ms:--}|$t_ms"; return 0 ;;
        *"Respaldo detenido"*|*"Falla no prevista"*)
          echo "STOPPED|$text|${start_ms:--}|$t_ms"; return 0 ;;
        *"Respaldo sin nada"*) echo "SKIPPED|$text|${start_ms:--}|$t_ms"; return 0 ;;
        # Un reintento no termina la ejecución: WorkManager la repite. Se sigue esperando.
        *"Respaldo para m"*) retries=$((retries + 1)); note "  reintento: $text" >&2 ;;
      esac
    fi
    # En Doze, si nada empezó, se abre otra ventana de mantenimiento, como en la corrida anterior en emulador.
    if [ "$phase" = "doze" ] && [ -z "$start_ms" ] && [ $((waited % 30)) -eq 25 ]; then
      open_maintenance >/dev/null
    fi
    sleep 5; waited=$((waited + 5))
  done
  echo "TIMEOUT|retries=$retries|${start_ms:--}|-"
  return 1
}

# Espera a que WorkManager anote el final del trabajo, para que la prueba siguiente, que reinicia
# el proceso de la app, no lo corte a medias.
await_worker_done() {
  local since="$1" i
  for i in $(seq 1 12); do
    a logcat -d -v epoch -T "$since.000" -s WM-WorkerWrapper:I | grep -q 'Worker result' &&
      { sleep 2; return 0; }
    sleep 2
  done
  return 1
}

PHASES_A=(active working_set frequent rare rare doze doze)
PHASES_B=(frequent rare rare restricted doze doze doze)

run() {
  local days="${DAYS:-8}" per_day="${RUNS_PER_DAY:-7}" min_valid="${MIN_VALID:-50}"
  local deadline=$(( $(date +%s) + ${DEADLINE_MIN:-40} * 60 )) mode="${MODE:-A}"
  local tsv="$OUT_DIR/runs.tsv"
  preflight || return 1
  note "run: kind=$KIND mode=$mode days>=$days runs/day=$per_day min_valid=$min_valid"
  printf 'run\tday\tphase\tbucket_req\tbucket_at_start\tunblocked\tdue_ms\trelease_ms\tstart_ms\tend_ms\tdelay_due_ms\tdelay_release_ms\tduration_ms\toutcome\tdetail\thttp\tvalid\tattempts\tstop_reason\tstatus\tpending\n' > "$tsv"
  sh_ dumpsys battery unplug >/dev/null
  sh_ dumpsys deviceidle enable >/dev/null
  local run_id=0 valid=0 failed=0 day=0 timeouts=0
  local phases=("${PHASES_A[@]}"); [ "$mode" = "B" ] && phases=("${PHASES_B[@]}")
  while :; do
    day=$((day + 1))
    if [ "$day" -gt "$days" ] && [ "$valid" -ge "$min_valid" ]; then break; fi
    if [ "$day" -gt $((days + 3)) ]; then note "run: tope de días sin llegar al mínimo"; break; fi
    note "día $day"
    if [ "$mode" = "A" ]; then
      # La persona abre la app una vez al día y la deja.
      screen_on
      sh_ am start -W -n "$PKG/io.github.yesidrobayo.shopledger.MainActivity" >/dev/null
      sleep 4
      sh_ input keyevent KEYCODE_HOME >/dev/null
      sleep 2
    fi
    sh_ am kill "$PKG" >/dev/null
    screen_off
    local slot
    for slot in $(seq 0 $((per_day - 1))); do
      [ "$(date +%s)" -lt "$deadline" ] || { note "run: se acabó el tiempo"; break 2; }
      run_id=$((run_id + 1))
      local phase="${phases[$slot % ${#phases[@]}]}" bucket
      bucket="$phase"; [ "$phase" = "doze" ] && bucket="rare"
      # Doze profundo antes de encolar: así el trabajo nunca corre dentro del proceso de la prueba.
      force_idle
      local t0; t0=$(dev_s)
      if ! instr RealRunTickTest#tick -e runId "$run_id"; then
        note "run $run_id: la prueba no encoló la subida"; leave_idle; continue
      fi
      local tick; tick=$(lines_since "$t0" | grep 'event=tick' | tail -1)
      local due; due=$(field "$tick" dueAtMs)
      set_bucket "$bucket"
      local release_s; release_s=$(dev_s)
      if [ "$phase" = "doze" ]; then open_maintenance >/dev/null; else leave_idle; fi
      local release_ms=$((release_s * 1000))
      local res unblocked=0 timeout_s=150
      [ "$phase" = "rare" ] || [ "$phase" = "restricted" ] || [ "$phase" = "doze" ] && timeout_s=75
      res=$(await_outcome "$release_s" "$timeout_s" "$phase")
      if [ "${res%%|*}" = "TIMEOUT" ]; then
        # Sin turno en ese bucket: no cuenta como fallo del destino. Se sube a frequent y se
        # deja salir de Doze para que la ejecución termine; queda anotado.
        unblocked=1; set_bucket frequent; leave_idle
        res=$(await_outcome "$release_s" 150 active)
      fi
      local bucket_seen; bucket_seen=$(bucket_now)
      await_worker_done "$release_s"
      local outcome detail start_ms end_ms
      IFS='|' read -r outcome detail start_ms end_ms <<< "$res"
      # La prueba de sondeo describe cómo terminó: intentos, stopReason y el estado visible.
      local t1; t1=$(dev_s)
      instr RealRunTickTest#probe -e runId "$run_id" >/dev/null
      local prev; prev=$(lines_since "$t1" | grep 'event=previous' | tail -1)
      local http="-" is_valid=0
      case "$outcome" in
        VERIFIED|UNRESTORABLE) is_valid=1; [ "$KIND" = "WEBDAV" ] && http="2xx" ;;
        STOPPED) is_valid=1; failed=$((failed + 1)) ;;
        *) is_valid=0 ;;
      esac
      [ "$outcome" = "TIMEOUT" ] && timeouts=$((timeouts + 1)) || timeouts=0
      valid=$((valid + is_valid))
      local d_due="-" d_rel="-" dur="-"
      if [ "$start_ms" != "-" ] && [ -n "$start_ms" ]; then
        [ -n "$due" ] && [ "$due" != "-" ] && d_due=$((start_ms - due))
        d_rel=$((start_ms - release_ms))
        [ "$end_ms" != "-" ] && dur=$((end_ms - start_ms))
      fi
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$run_id" "$day" "$phase" "$bucket" "$bucket_seen" "$unblocked" "${due:--}" \
        "$release_ms" "$start_ms" "$end_ms" "$d_due" "$d_rel" "$dur" "$outcome" "$detail" \
        "$http" "$is_valid" "$(field "$prev" attempts)" "$(field "$prev" stopReason)" \
        "$(field "$prev" status)" "$(field "$prev" pending)" >> "$tsv"
      note "run $run_id día $day $phase: $outcome válida=$is_valid (válidas $valid, fallos $failed)"
      if [ "$timeouts" -ge 3 ]; then note "run: tres ejecuciones seguidas sin correr; revisar"; break 2; fi
    done
  done
  sh_ dumpsys deviceidle unforce >/dev/null
  sh_ dumpsys battery reset >/dev/null
  set_bucket active
  # Lo que quedó en el destino: solo lo que deja la retención de la app.
  local t2; t2=$(dev_s)
  instr RealDrillTest#step21DestinationLooksRight >/dev/null
  note "destino: $(lines_since "$t2" | grep 'event=destination' | tail -1 | sed 's/.*event=//')"
  local verdict="PASA"
  [ "$valid" -ge "$min_valid" ] && [ "$failed" -eq 0 ] && [ "$day" -ge "$days" ] || verdict="NO PASA"
  note "run: kind=$KIND días=$day ejecuciones=$run_id válidas=$valid fallos=$failed veredicto=$verdict"
}

slots() {
  preflight -e expect slots || return 1
  local m t ok
  for m in keepsThreeDriveGrantsAndTheWriteMode columnSizeIsStable rereadMatchesTheRecordedSha \
    cannotReadTheProviderRoots describesTheDocumentFlagsAndViewers; do
    t=$(dev_s); ok=PASA; instr "RealSlotsTest#$m" || ok="NO PASA"
    note "slots $m: $ok"
    lines_since "$t" | grep 'event=' | sed 's/.*event=/  /' | tee -a "$LOG"
  done
}

# Un paso del simulacro: corre el método y deja sus líneas en el registro.
step() {
  local m="$1"; shift
  local t; t=$(dev_s); local ok=PASA
  instr "RealDrillTest#$m" "$@" || ok="NO PASA"
  note "drill $m: $ok"
  lines_since "$t" | grep 'event=' | sed 's/.*event=/  /' | tee -a "$LOG"
  [ "$ok" = "PASA" ]
}

drill() {
  local label=base; [ "${1:-}" = "--volume" ] && label=volume
  preflight || return 1
  sh_ dumpsys deviceidle unforce >/dev/null
  set_bucket active
  step step01RecordControl -e label "$label"
  step step02BackupNow -e label "$label" -e waitMinutes 20
  if [ "$label" = "base" ] && [ "$KIND" = "WEBDAV" ]; then
    # Pasos 3 y 4: sin red se acumulan los cambios; con la app cerrada, al volver la red, suben.
    sh_ cmd connectivity airplane-mode enable >/dev/null; sleep 3
    step step03OfflineSales
    sh_ am kill "$PKG" >/dev/null
    local back; back=$(dev_s)
    sh_ cmd connectivity airplane-mode disable >/dev/null
    note "drill: red de vuelta, sin abrir la app: $(await_outcome "$back" 600 active | cut -d'|' -f1)"
    await_worker_done "$back"
    step step04UploadedWhileClosed
  fi
  step step06PrepareRestore -e label "$label"
  step step08VerifyRestore -e label "$label"
  step step21DestinationLooksRight
  step inAppDrill -e label "$label"
}

scenario() {
  preflight || return 1
  local t
  case "${1:-}" in
    airplane)
      # Con ranuras, la escritura no espera a la red: la otra app sube cuando vuelva. Con WebDAV,
      # la subida espera la red, y ese caso lo cubre el simulacro con la app cerrada.
      [ "$KIND" = "DOCUMENT_SLOTS" ] || { note "airplane: con WebDAV va en drill"; return 0; }
      sh_ cmd connectivity airplane-mode enable >/dev/null; sleep 3
      force_idle; t=$(dev_s)
      instr RealRunTickTest#tick -e runId 9001 || note "airplane: no encoló"
      local rel; rel=$(dev_s); leave_idle
      note "airplane: $(await_outcome "$rel" 300 active | cut -d'|' -f1,2)"
      await_worker_done "$rel"
      instr RealRunTickTest#probe -e runId 9001 >/dev/null
      lines_since "$t" | grep 'event=' | sed 's/.*event=/  /' | tee -a "$LOG"
      sh_ cmd connectivity airplane-mode disable >/dev/null
      ;;
    reboot)
      # Un cambio, y el emulador se reinicia sin abrir la app: el trabajo sigue programado.
      force_idle
      instr RealRunTickTest#tick -e runId 9002 || note "reboot: no encoló"
      a reboot; a wait-for-device
      until [ "$(sh_ getprop sys.boot_completed)" = "1" ]; do sleep 3; done
      t=$(dev_s); note "reboot: arrancó"
      note "reboot: $(await_outcome "$((t - 120))" 600 active | cut -d'|' -f1,2)"
      await_worker_done "$((t - 120))"
      t=$(dev_s); instr RealRunTickTest#probe -e runId 9002 >/dev/null
      lines_since "$t" | grep 'event=' | sed 's/.*event=/  /' | tee -a "$LOG"
      ;;
    provider-off)
      # Ranuras sin la app de la nube: se deshabilita Drive para la cuenta del teléfono, como si
      # se hubiera desinstalado, y se vuelve a habilitar al final. Puede dejar el enlace en
      # permiso perdido: entonces la persona vuelve a preparar los archivos desde la app.
      [ "$KIND" = "DOCUMENT_SLOTS" ] || { note "provider-off: solo con ranuras"; return 1; }
      sh_ pm disable-user --user 0 com.google.android.apps.docs >/dev/null
      force_idle; t=$(dev_s)
      instr RealRunTickTest#tick -e runId 9003 || note "provider-off: no encoló"
      local rel2; rel2=$(dev_s); leave_idle
      note "provider-off: $(await_outcome "$rel2" 300 active | cut -d'|' -f1,2)"
      await_worker_done "$rel2"
      instr RealRunTickTest#probe -e runId 9003 >/dev/null
      lines_since "$t" | grep 'event=' | sed 's/.*event=/  /' | tee -a "$LOG"
      sh_ pm enable com.google.android.apps.docs >/dev/null
      ;;
    *) echo "Escenario desconocido" >&2; return 2 ;;
  esac
}

collect() {
  # Las sumas de lo que la app releyó de cada ranura, de datos inventados, para compararlas con
  # lo que baje la persona desde la web de Drive. Ninguna URI ni nombre de archivo.
  a exec-out run-as "$PKG" cat files/real-accounts/slots-sha.txt > "$OUT_DIR/slots-sha.txt" \
    2>/dev/null || true
  note "collect: $(wc -l < "$OUT_DIR/slots-sha.txt" 2>/dev/null || echo 0) ranuras"
}

case "$CMD" in
  preflight) preflight "$@" ;;
  seed) seed "$@" ;;
  slots) slots ;;
  run) run ;;
  drill) drill "$@" ;;
  scenario) scenario "$@" ;;
  collect) collect ;;
  *) sed -n '2,24p' "$0"; exit 2 ;;
esac
