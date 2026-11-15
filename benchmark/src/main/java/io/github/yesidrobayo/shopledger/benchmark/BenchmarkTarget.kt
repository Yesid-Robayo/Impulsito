package io.github.yesidrobayo.shopledger.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue

/**
 * La app que miden las pruebas de este módulo, las marcas con que la encuentran y los pasos que
 * comparten.
 *
 * Se mide la compilación `benchmark` de la app: release, con R8 y sin depuración, firmada con la
 * clave de debug y con el identificador de debug. Se instala encima de una debug que ya tiene un
 * negocio grande inventado, así que la base sobrevive, y nunca toca la app real, que tiene otro
 * identificador y otra firma. Un emulador sirve para ver que las pruebas corren, nunca para medir.
 *
 * Cómo se corre, desde la carpeta del código, con el teléfono conectado por cable:
 *
 * 1. Llenar la base: instalar debug y sus pruebas (`.\gradlew.bat :app:installDebug
 *    :app:installDebugAndroidTest`) y correr `VolumeSeedTest` con `adb shell am instrument -w -e
 *    class io.github.yesidrobayo.shopledger.perf.VolumeSeedTest -e volumeSeed true` más el volumen
 *    de referencia, `-e volumeProducts 5000 -e volumeSales 100000 -e volumePurchases 10000 -e
 *    volumeExpenses 20000 -e volumeDays 1826`, y el runner
 *    `io.github.yesidrobayo.shopledger.debug.test/androidx.test.runner.AndroidJUnitRunner`. No se
 *    corre con Gradle: al terminar desinstala la app y se lleva la base. Tarda: cada venta pasa
 *    por su caso de uso, como en la app.
 * 2. Instalar encima la compilación medida y estas pruebas: `.\gradlew.bat :app:installBenchmark
 *    :benchmark:installBenchmark`.
 * 3. Medir: `adb shell am instrument -w -e measurePerformance true
 *    io.github.yesidrobayo.shopledger.benchmark/androidx.test.runner.AndroidJUnitRunner`. Con `-e
 *    class` se corre una sola clase. En un emulador hace falta además `-e
 *    androidx.benchmark.suppressErrors EMULATOR`, y lo medido no cuenta.
 * 4. Leer los resultados: cada clase deja un JSON en
 *    `/sdcard/Android/media/io.github.yesidrobayo.shopledger.benchmark/`, que se trae con `adb
 *    pull`. Trae cada corrida, y de ahí sale el percentil 95.
 *
 * El perfil de compilación se genera aparte, con `-e baselineProfile true` y la clase
 * [BaselineProfileGenerator], en Android 13 o más nuevo.
 */
internal object BenchmarkTarget {
    /** El identificador de la compilación medida, que es el de debug. */
    private const val DEFAULT_PACKAGE = "io.github.yesidrobayo.shopledger.debug"

    /** El argumento que pide las mediciones: `-e measurePerformance true`. */
    const val MEASURE = "measurePerformance"

    /** El argumento que pide generar el perfil de compilación: `-e baselineProfile true`. */
    const val BASELINE_PROFILE = "baselineProfile"

    /** Las marcas fijas de la app, las mismas de `PerformanceTags` en su código. */
    const val HOME_READY = "home_ready"
    const val RECORDS_TAB = "tab_records"
    const val SALE_HISTORY_LIST = "sale_history_list"

    /** Cuánto se espera a que aparezca una pantalla. Con una base grande, abrir puede tardar. */
    const val WAIT_MS = 30_000L

    /** Cuántas veces se mide el arranque: con veinte, el percentil 95 sale de las corridas. */
    const val STARTUP_ITERATIONS = 20

    /** Cuántas veces se desplaza el historial, cada vez durante [SCROLL_MS]. */
    const val SCROLL_ITERATIONS = 5

    /** El desplazamiento que pide la fluidez de las listas: 30 segundos seguidos. */
    const val SCROLL_MS = 30_000L

    /** Lo que se desplaza al generar el perfil de compilación: basta con recorrer el camino. */
    const val PROFILE_SCROLL_MS = 5_000L

    /** El borde que deja libre el gesto, en partes del ancho: así no abre el gesto de volver. */
    private const val GESTURE_MARGIN_PARTS = 5

    private val arguments get() = InstrumentationRegistry.getArguments()

    /** La app medida: la de debug, salvo que se pida otra con `-e targetPackage`. */
    val packageName: String get() = arguments.getString("targetPackage") ?: DEFAULT_PACKAGE

    /**
     * Las mediciones necesitan un teléfono y una base llena, así que solo corren si se piden con
     * el argumento [name] en `true`. Sin él, una corrida de todas las pruebas no las dispara.
     */
    fun assumeRequested(name: String) {
        assumeTrue("Se corre solo con -e $name true", arguments.getString(name) == "true")
    }
}

/** Espera a que Inicio muestre las cifras leídas de la base. */
internal fun MacrobenchmarkScope.waitForHome() {
    val shown =
        device.wait(Until.hasObject(By.res(BenchmarkTarget.HOME_READY)), BenchmarkTarget.WAIT_MS)
    check(shown) { "Inicio no mostró sus cifras a tiempo" }
}

/** Abre Registros desde la barra inferior y espera la lista del historial de ventas. */
internal fun MacrobenchmarkScope.openSaleHistory() {
    val tab =
        device.wait(Until.findObject(By.res(BenchmarkTarget.RECORDS_TAB)), BenchmarkTarget.WAIT_MS)
            ?: error("No apareció la pestaña de Registros")
    tab.click()
    val shown =
        device.wait(
            Until.hasObject(By.res(BenchmarkTarget.SALE_HISTORY_LIST)),
            BenchmarkTarget.WAIT_MS,
        )
    check(shown) { "El historial de ventas no apareció a tiempo: la base no tiene ventas" }
}

/**
 * Desplaza el historial de ventas con gestos rápidos durante [durationMs]. Si llega a un extremo,
 * sigue en el sentido contrario, para que todo el tiempo haya cuadros que dibujar.
 */
internal fun MacrobenchmarkScope.scrollSaleHistory(durationMs: Long) {
    val list =
        device.wait(
            Until.findObject(By.res(BenchmarkTarget.SALE_HISTORY_LIST)),
            BenchmarkTarget.WAIT_MS,
        ) ?: error("No apareció el historial de ventas")
    list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_PARTS)
    var direction = Direction.DOWN
    val end = SystemClock.uptimeMillis() + durationMs
    while (SystemClock.uptimeMillis() < end) {
        if (!list.fling(direction)) {
            direction = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
        }
    }
}
