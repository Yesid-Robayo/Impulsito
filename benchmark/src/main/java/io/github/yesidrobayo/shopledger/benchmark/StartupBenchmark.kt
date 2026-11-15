package io.github.yesidrobayo.shopledger.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El arranque en frío hasta que Inicio responde con cifras reales, con la meta de 2,0 s en el
 * percentil 95.
 *
 * Cada corrida cierra la app, vuelve al escritorio y la abre. La cifra que cuenta es
 * `timeToFullDisplayMs`: la app avisa que terminó de dibujar cuando Inicio ya muestra las cifras
 * leídas de la base, no con su primer cuadro, que es `timeToInitialDisplayMs`. Se mide de dos
 * formas: sin compilar nada, como la app recién instalada, y con el perfil de compilación que trae
 * el APK, como queda tras la primera apertura.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun requireRequest() {
        BenchmarkTarget.assumeRequested(BenchmarkTarget.MEASURE)
    }

    @Test
    fun coldStartWithoutCompilation() = coldStart(CompilationMode.None())

    @Test
    fun coldStartWithProfile() =
        coldStart(CompilationMode.Partial(BaselineProfileMode.UseIfAvailable))

    private fun coldStart(mode: CompilationMode) = rule.measureRepeated(
        packageName = BenchmarkTarget.packageName,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = BenchmarkTarget.STARTUP_ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitForHome()
    }
}
