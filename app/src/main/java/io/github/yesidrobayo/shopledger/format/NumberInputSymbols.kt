package io.github.yesidrobayo.shopledger.format

import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import java.math.BigDecimal
import java.text.CharacterIterator

/**
 * Los símbolos con que [format] escribe un número, para que el dominio lea lo que la persona
 * escribe con esos mismos símbolos.
 *
 * No salen de la tabla de símbolos de la región, sino de escribir un número de prueba con una copia
 * del formato y leer cada campo, después de normalizarlo como cualquier cifra en pantalla. Así son
 * exactamente los que la persona ve, también en las regiones donde los montos usan separadores
 * distintos de los demás números.
 */
internal fun numberInputSymbols(format: DecimalFormat): NumberSymbols {
    // La copia lleva un decimal, aunque el formato no tenga, para ver también el separador decimal.
    val probe =
        (format.clone() as DecimalFormat).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    val decimal = StringBuilder()
    val grouping = StringBuilder()
    val sign = StringBuilder()
    val percent = StringBuilder()
    val iterator = probe.formatToCharacterIterator(PROBE_VALUE)
    var char = iterator.first()
    while (char != CharacterIterator.DONE) {
        val attributes = iterator.attributes.keys
        when {
            NumberFormat.Field.DECIMAL_SEPARATOR in attributes -> decimal.append(char)
            NumberFormat.Field.GROUPING_SEPARATOR in attributes -> grouping.append(char)
            NumberFormat.Field.SIGN in attributes -> sign.append(char)
            NumberFormat.Field.PERCENT in attributes -> percent.append(char)
            else -> Unit
        }
        char = iterator.next()
    }
    val groupingSeparators = IcuTextNormalizer.normalizeText(grouping.toString()).toSet()
    val primarySize = format.groupingSize
    // Solo un formato de porcentaje escribe el signo; los demás se quedan con el del teclado.
    val percentSign = IcuTextNormalizer.normalizeText(percent.toString())
    return NumberSymbols(
        decimalSeparator = single(
            IcuTextNormalizer.normalizeText(decimal.toString()),
            "separador decimal",
        ),
        minusSign = single(IcuTextNormalizer.normalizeNumber(sign.toString()), "signo menos"),
        grouping =
            if (groupingSeparators.isEmpty() || primarySize < 1) {
                null
            } else {
                DigitGrouping(
                    separator = single(groupingSeparators.joinToString(""), "separador de grupos"),
                    primarySize = primarySize,
                    // ICU da cero o un negativo si la región no distingue los demás grupos.
                    secondarySize = format.secondaryGroupingSize.takeIf { it >= 1 } ?: primarySize,
                )
            },
        percentSign =
            if (percentSign.isEmpty()) {
                NumberSymbols.PERCENT_SIGN
            } else {
                single(percentSign, "signo de porcentaje")
            },
    )
}

/**
 * Un número negativo con diez cifras enteras: alcanza para que aparezcan los separadores de grupo
 * en cualquier región, también en las que no agrupan un número de cuatro cifras.
 */
private val PROBE_VALUE: BigDecimal = BigDecimal("-1234567890.5")

/** El único carácter de [text], o falla nombrando el símbolo: el lector no sabría leer otro. */
private fun single(text: String, what: String): Char =
    requireNotNull(text.singleOrNull()) { "ICU no dio un solo carácter para el $what" }
