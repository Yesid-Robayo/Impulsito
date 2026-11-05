package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols

/**
 * Limpia lo que la persona escribe o pega en un campo numérico: deja las cifras y un solo separador
 * decimal, el de la región, con a lo sumo [maxDecimals] cifras detrás.
 *
 * Acepta la coma y el punto como separador decimal, porque el teclado numérico depende del fabricante
 * y no siempre trae el de la región. Si llegan los dos, como al pegar `1.234,56`, el último es el
 * decimal y los otros eran de grupos; si uno solo se repite, como en `1.234.567`, eran todos de
 * grupos. Con [maxDecimals] en cero no queda ninguno. Los separadores de grupos no se guardan: los
 * dibuja [DigitGroupingTransformation].
 */
fun cleanNumberInput(text: String, symbols: NumberSymbols, maxDecimals: Int): String {
    fun isSeparator(char: Char) = char == ',' || char == '.' || char == symbols.decimalSeparator
    val separators = text.indices.filter { isSeparator(text[it]) }
    val decimalIndex =
        when {
            maxDecimals == 0 || separators.isEmpty() -> -1
            separators.map { text[it] }.distinct().size > 1 -> separators.last()
            separators.size > 1 -> -1
            else -> separators.single()
        }
    return buildString {
        var decimals = -1
        text.forEachIndexed { index, char ->
            when {
                index == decimalIndex -> {
                    append(symbols.decimalSeparator)
                    decimals = 0
                }

                char !in '0'..'9' -> Unit

                decimals < 0 -> append(char)

                decimals < maxDecimals -> {
                    append(char)
                    decimals++
                }
            }
        }
    }
}

/**
 * Dibuja los separadores de grupos de la región mientras se escribe, sin cambiar el texto guardado:
 * `1234567` se ve `1.234.567`, o `12,34,567` en una región con grupos de tres y dos.
 *
 * El texto que recibe ya pasó por [cleanNumberInput]: cifras y, a lo sumo, un separador decimal.
 */
class DigitGroupingTransformation(private val symbols: NumberSymbols) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val grouping = symbols.grouping ?: return TransformedText(text, OffsetMapping.Identity)
        val raw = text.text
        val integerEnd = raw.indexOf(symbols.decimalSeparator).let {
            if (it <
                0
            ) {
                raw.length
            } else {
                it
            }
        }
        val breaks = groupBreaks(integerEnd, grouping)
        if (breaks.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val shown =
            buildString {
                raw.forEachIndexed { index, char ->
                    if (index in breaks) append(grouping.separator)
                    append(char)
                }
            }
        // Dónde queda cada separador en el texto dibujado: el de la posición b lleva antes los anteriores.
        val sorted = breaks.sorted()
        val shownBreaks = sorted.mapIndexed { index, position -> position + index }
        val mapping =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    offset + sorted.count { it < offset }

                override fun transformedToOriginal(offset: Int): Int =
                    offset - shownBreaks.count { it < offset }
            }
        return TransformedText(AnnotatedString(shown), mapping)
    }

    override fun equals(other: Any?): Boolean =
        other is DigitGroupingTransformation && other.symbols == symbols

    override fun hashCode(): Int = symbols.hashCode()

    internal companion object {
        /**
         * Las posiciones del texto original antes de las cuales va un separador, para una parte entera
         * de [length] cifras: el primer grupo desde la derecha mide `primarySize`, y los demás
         * `secondarySize`.
         */
        fun groupBreaks(length: Int, grouping: DigitGrouping): Set<Int> {
            val breaks = mutableSetOf<Int>()
            var position = length - grouping.primarySize
            while (position > 0) {
                breaks += position
                position -= grouping.secondarySize
            }
            return breaks
        }
    }
}
