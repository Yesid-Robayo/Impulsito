package io.github.yesidrobayo.shopledger.ui.format

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.yesidrobayo.shopledger.format.DateFormatter
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.format.PercentFormatter
import io.github.yesidrobayo.shopledger.format.QuantityFormatter
import java.util.Locale

/**
 * Los formateadores del negocio: el dinero en su moneda, las cantidades, los porcentajes y las
 * fechas, todos con el formato regional guardado y las palabras del idioma de la interfaz.
 *
 * Los componentes los leen de [LocalAppFormats] y reciben solo `Money`, `QuantityMilli` o
 * `BusinessDate`: así ninguno decide un separador, un símbolo ni el orden de una fecha, y todos
 * cambian a la vez cuando la persona cambia la región.
 */
@Immutable
class AppFormats(
    val money: MoneyFormatter,
    val quantity: QuantityFormatter,
    val percent: PercentFormatter,
    val date: DateFormatter,
) {
    /** El idioma de la interfaz: elige las palabras, nunca los números. */
    val uiLocale: Locale get() = date.uiLocale
}

/**
 * Los formateadores vigentes. No tiene valor por defecto: un monto dibujado sin los ajustes del
 * negocio saldría con los del teléfono, y eso es justo lo que la app nunca hace.
 */
val LocalAppFormats =
    staticCompositionLocalOf<AppFormats> {
        error("Faltan los formatos del negocio: se leen de los ajustes antes de dibujar cifras")
    }

/**
 * Si el modo discreto está encendido: las cifras sensibles se dibujan como `••••`.
 *
 * Tampoco tiene valor por defecto. Una pantalla que olvide darlo fallaría en desarrollo, en vez de
 * mostrar costos y ganancias a quien mira por encima del hombro.
 */
val LocalDiscreetMode =
    compositionLocalOf<Boolean> {
        error("Falta el estado del modo discreto: se lee de los ajustes")
    }
