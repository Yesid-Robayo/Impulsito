package io.github.yesidrobayo.shopledger.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La fluidez del historial de ventas: 30 segundos de desplazamiento con gestos rápidos, con la
 * meta de menos del 5 % de cuadros lentos.
 *
 * Antes de medir, cada corrida abre la app en frío, espera Inicio y entra a Registros, con el
 * historial arriba: se mide solo el desplazamiento, que además va pidiendo los tramos siguientes
 * de la base. Un cuadro es lento si no llegó a tiempo: en Android 12 o más nuevo,
 * `frameOverrunMs` mayor que cero. Menos del 5 % de cuadros lentos es un `frameOverrunMs` de cero
 * o menos en el percentil 95. Antes de Android 12 no hay ese dato, y cuenta `frameDurationCpuMs`
 * en el percentil 95 frente a lo que dura un cuadro en la pantalla del teléfono.
 */
@RunWith(AndroidJUnit4::class)
class SaleHistoryScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun requireRequest() {
        BenchmarkTarget.assumeRequested(BenchmarkTarget.MEASURE)
    }

    @Test
    fun scrollSaleHistory() =
        rule.measureRepeated(
            packageName = BenchmarkTarget.packageName,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
            startupMode = StartupMode.COLD,
            iterations = BenchmarkTarget.SCROLL_ITERATIONS,
            setupBlock = {
                startActivityAndWait()
                waitForHome()
                openSaleHistory()
            },
        ) {
            scrollSaleHistory(BenchmarkTarget.SCROLL_MS)
        }
}
