package io.github.yesidrobayo.shopledger.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genera el perfil de compilación de la app: las clases y los métodos que usa el camino de todos
 * los días, desde abrir hasta recorrer el historial. Android los compila por adelantado al
 * instalar, y la app no espera a que el sistema la compile de fondo.
 *
 * Solo corre con `-e baselineProfile true`, en Android 13 o más nuevo, o en un teléfono con root.
 * Deja el archivo `BaselineProfileGenerator_generate-baseline-prof.txt` junto a los resultados de
 * las mediciones. Adoptarlo es copiarlo a `app/src/main/baseline-prof.txt` en un commit propio, y
 * medir el arranque antes y después: el APK cambia, así que solo entra si mejora.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Before
    fun requireRequest() {
        BenchmarkTarget.assumeRequested(BenchmarkTarget.BASELINE_PROFILE)
    }

    @Test
    fun generate() =
        rule.collect(packageName = BenchmarkTarget.packageName) {
            pressHome()
            startActivityAndWait()
            waitForHome()
            openSaleHistory()
            scrollSaleHistory(BenchmarkTarget.PROFILE_SCROLL_MS)
        }
}
