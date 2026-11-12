package io.github.yesidrobayo.shopledger.format

import android.icu.util.ULocale
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La forma corta de los montos, la del eje de la gráfica, en todas las regiones de ICU del teléfono.
 *
 * Algunas versiones de ICU traen datos de forma corta que su propio código rechaza al leerlos, como
 * los de `so` en Android 8. Ninguna región puede romper un formateador de dinero ni la pantalla que
 * lo usa: si ICU no sabe acortar, el eje muestra el número entero.
 */
@RunWith(AndroidJUnit4::class)
class CompactMoneyTest {
    @Test
    fun everyRegionOfThePhoneFormatsCompactAmountsWithoutFailing() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (available in ULocale.getAvailableLocales()) {
            val tag = available.toLanguageTag()
            val formatLocale = FormatLocale.of(tag) ?: continue
            for (minor in AMOUNTS) {
                try {
                    val text = moneyFormatter(formatLocale, 2).compact(Money(minor))
                    if (text.isBlank()) failures += "$tag $minor: vacío"
                    if ('-' in text) failures += "$tag $minor: guion en vez de signo menos: $text"
                } catch (failure: RuntimeException) {
                    failures += "$tag $minor: ${failure.javaClass.simpleName}"
                }
                checked++
            }
        }
        assertTrue("Regiones revisadas: $checked", checked > 1_000)
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun aRegionWhoseShortFormIcuCannotReadStillFormatsMoney() {
        // `so` es la región que rompía en Android 8. En las versiones que sí la leen, la prueba
        // solo confirma que se acorta; en las otras, que el monto sale entero en vez de fallar.
        val formatter = moneyFormatter(requireNotNull(FormatLocale.of("so")), 2)

        assertTrue(formatter.format(Money(123_456)).isNotBlank())
        assertTrue(formatter.compact(Money(30_000_000)).isNotBlank())
    }

    private companion object {
        /** Cero, montos de eje típicos, uno negativo y uno de trece cifras. */
        val AMOUNTS = listOf(0L, 30_000_000L, -4_500_000L, 1_234_567_890_123L)
    }
}
