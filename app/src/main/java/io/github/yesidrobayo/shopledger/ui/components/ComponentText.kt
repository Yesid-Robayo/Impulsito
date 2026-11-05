package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.format.IcuTextNormalizer
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats

/**
 * Lo que se dibuja en lugar de una cifra sensible en modo discreto. Siempre cuatro puntos: un largo
 * que variara con la cifra revelaría su magnitud.
 */
const val DISCREET_TEXT: String = "••••"

/**
 * Una unidad tal como se muestra junto a una cantidad.
 *
 * @param symbol el símbolo ya traducido, como `und` o `kg`, o el nombre que escribió la persona.
 * @param allowsFraction si la unidad admite fracciones: decide si la cantidad lleva tres decimales.
 * @param custom si la creó la persona. Su nombre no tiene plural conocido, así que va como `3 × caja`
 *   y no como `3 cajas`.
 */
@Immutable
data class UnitLabel(val symbol: String, val allowsFraction: Boolean, val custom: Boolean = false)

/**
 * Una cantidad con su unidad, con los números del formato regional: `12 und`, `0,250 kg` o
 * `3 × caja`. El espacio es duro, para que la cifra nunca quede en otra línea que su unidad.
 */
@Composable
fun quantityText(quantity: QuantityMilli, unit: UnitLabel): String {
    val number = LocalAppFormats.current.quantity.format(quantity, unit.allowsFraction)
    return if (unit.custom) {
        stringResource(R.string.format_custom_unit_quantity, number, unit.symbol)
    } else {
        number + IcuTextNormalizer.NO_BREAK_SPACE + unit.symbol
    }
}

/**
 * Inserta [args] en los marcadores `%1$s`, `%2$s`… de [pattern], sin perder sus estilos. Así un monto,
 * con el símbolo en su propia fuente, entra en una frase traducida sin concatenar textos.
 *
 * Un marcador sin argumento queda tal cual: el error se ve en pantalla en vez de esconderse.
 */
fun formatAnnotated(pattern: String, vararg args: CharSequence): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < pattern.length) {
            val match = PLACEHOLDER.find(pattern, index)
            if (match == null) {
                append(pattern.substring(index))
                break
            }
            append(pattern.substring(index, match.range.first))
            val position = match.groupValues[1].toInt() - 1
            val arg = args.getOrNull(position)
            when (arg) {
                null -> append(match.value)
                is AnnotatedString -> append(arg)
                else -> append(arg.toString())
            }
            index = match.range.last + 1
        }
    }

/** Un marcador posicional de texto de los recursos: `%1$s`. */
private val PLACEHOLDER = Regex("%(\\d+)\\\$s")
