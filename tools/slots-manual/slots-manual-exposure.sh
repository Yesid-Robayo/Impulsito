#!/usr/bin/env bash
# La medición comprimida de la nube del teléfono sin copias automáticas, conducida desde el PC
# por adb: corre SlotsManualExposureTest en un emulador y da sus tres medidas.
#
# La prueba simula al menos 50 cierres de la app con cambios pendientes, con el reloj de la
# prueba, sobre un proveedor de documentos local del APK de pruebas de :data. No usa ninguna
# cuenta, ninguna nube real ni la app instalada: nunca toque con esto un emulador con cuentas
# reales.
#
# Uso:  slots-manual-exposure.sh -s <serial> [cierres]
#
#   cierres   cuántos cierres simula, 56 por defecto (8 días de 7 sesiones). Nunca menos de 50.
#
# Variables: ADB (por defecto adb), APK (el APK de pruebas de :data, ya compilado con
# `gradlew :data:assembleDebugAndroidTest`), OUT (por defecto ./slots-manual-out).
#
# Termina con 0 solo si la prueba pasa y las tres medidas están dentro de su límite. Todo lo que
# escribe son conteos, minutos y resultados: ningún dato del negocio, de una cuenta ni una URI.
set -u

ADB="${ADB:-adb}"
SERIAL=""
if [ "${1:-}" = "-s" ]; then SERIAL="$2"; shift 2; fi
[ -n "$SERIAL" ] || { echo "Falta -s <serial>" >&2; exit 2; }
CLOSINGS="${1:-56}"
case "$CLOSINGS" in
  ''|*[!0-9]*) echo "Los cierres deben ser un número" >&2; exit 2 ;;
esac
[ "$CLOSINGS" -ge 50 ] || { echo "Al menos 50 cierres" >&2; exit 2; }
APK="${APK:-data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk}"
TEST_PKG="io.github.yesidrobayo.shopledger.data.test"
RUNNER="$TEST_PKG/androidx.test.runner.AndroidJUnitRunner"
CLASS="io.github.yesidrobayo.shopledger.data.work.SlotsManualExposureTest"
OUT_DIR="${OUT:-./slots-manual-out}/$SERIAL"
mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/exposure.log"

a() { "$ADB" -s "$SERIAL" "$@"; }
note() { echo "$(date -u +%FT%TZ) $*" | tee -a "$LOG"; }
# El valor de una clave en una línea `clave=valor`.
field() { echo "$1" | grep -o " $2=[^ ]*" | head -1 | cut -d= -f2; }

if [ ! -f "$APK" ]; then
  echo "No está $APK: compílelo antes con gradlew :data:assembleDebugAndroidTest" >&2
  exit 2
fi
a get-state >/dev/null 2>&1 || { echo "No responde el emulador $SERIAL" >&2; exit 2; }

note "instalar: APK de pruebas de :data"
a install -r -t "$APK" >>"$LOG" 2>&1 || { note "instalar: falla"; exit 1; }

started=$(a shell date +%s | tr -d '\r')
note "correr: $CLOSINGS cierres"
out=$(a shell am instrument -w -e class "$CLASS" -e closings "$CLOSINGS" "$RUNNER" | tr -d '\r')
echo "$out" >>"$OUT_DIR/instrument.log"
passed=0
if echo "$out" | grep -q '^OK (' && ! echo "$out" | grep -q 'FAILURES'; then passed=1; fi

lines=$(a logcat -d -v epoch -T "$started.000" -s SlotsExposure:I | tr -d '\r')
echo "$lines" | grep 'event=' >"$OUT_DIR/lines.txt"
summary=$(echo "$lines" | grep 'event=summary' | tail -1)
if [ -z "$summary" ]; then
  note "sin resumen: la prueba no llegó al final"
  tail -20 "$OUT_DIR/instrument.log"
  exit 1
fi

closings=$(field "$summary" closings)
within=$(field "$summary" withinADay)
percent=$(field "$summary" withinADayPercent)
worst=$(field "$summary" maxExposureMin)
missing=$(field "$summary" missing)
closed=$(field "$summary" closedWrites)
verdict=$(field "$summary" verdict)
instrument=falla
[ "$passed" = 1 ] && instrument=pasa

note "medida 1: $within de $closings cierres entregados antes de 24 horas" \
  "($percent %, límite 95 %)"
note "medida 2: mayor exposición $worst min (límite 1.440)"
note "medida 3: copias entregadas que no siguen en su ranura: $missing (límite 0)"
note "escrituras con la app cerrada: $closed (límite 0)"
note "veredicto de la prueba: $verdict; instrumentación: $instrument"
{
  printf 'closings\twithin24h\tpercent\tmaxExposureMin\tmissing\tclosedWrites\tverdict\n'
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$closings" "$within" "$percent" "$worst" "$missing" "$closed" "$verdict"
} >"$OUT_DIR/summary.tsv"

[ "$passed" = 1 ] && [ "$verdict" = "PASS" ]
