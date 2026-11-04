package io.github.yesidrobayo.shopledger.format

import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import android.icu.util.Currency
import io.github.yesidrobayo.shopledger.domain.model.Money
import java.math.BigDecimal
import java.text.AttributedCharacterIterator
import java.text.CharacterIterator

/**
 * Formatea montos de la moneda del negocio con el formato regional [formatLocale] y exactamente
 * [currencyDecimals] decimales.
 *
 * El monto llega como [Money], un entero en unidad menor, y pasa a ICU como un decimal exacto: un
 * monto nunca toca un `Double`, ni siquiera para mostrarse. Los decimales salen de los ajustes del
 * negocio y no de la moneda que ICU conozca, porque el valor de uso puede no ser el de la norma.
 *
 * Las cifras se leen campo por campo del patrón de moneda de la región, no recortando un texto: así
 * los separadores son los que la región usa para dinero, que en algunas regiones no son los de
 * cualquier número. Este formateador todavía no pone el símbolo: ICU lo deja fuera, y el signo
 * queda pegado a las cifras.
 *
 * Es seguro usarlo desde varios hilos: ICU no lo es, y cada llamada toma el formato en exclusiva.
 */
class MoneyFormatter(val formatLocale: FormatLocale, val currencyDecimals: Int) {
    private val format: DecimalFormat

    init {
        require(currencyDecimals in MIN_CURRENCY_DECIMALS..MAX_CURRENCY_DECIMALS) {
            "Los decimales de la moneda van de $MIN_CURRENCY_DECIMALS a $MAX_CURRENCY_DECIMALS"
        }
        format = currencyFormat(formatLocale, currencyDecimals)
    }

    /** El monto en partes: si es negativo, y sus cifras sin signo ni símbolo. */
    fun parts(amount: Money): MoneyParts {
        val fields = readFields(amount)
        return MoneyParts(negative = amount.minor < 0L, number = fields.number)
    }

    /**
     * El monto sin símbolo, con el signo menos tipográfico donde lo pone la región: `−1.234,56`.
     *
     * Sirve donde la moneda ya se dijo, como una columna con la moneda en el título.
     */
    fun format(amount: Money): String = readFields(amount).signedNumber

    private fun readFields(amount: Money): Fields {
        val value = BigDecimal.valueOf(amount.minor, currencyDecimals)
        val number = StringBuilder()
        val signedNumber = StringBuilder()
        var signSeen = false
        synchronized(format) {
            val iterator = format.formatToCharacterIterator(value)
            var char = iterator.first()
            while (char != CharacterIterator.DONE) {
                val attributes = iterator.attributes.keys
                when {
                    attributes.any { it in NUMBER_FIELDS } -> {
                        number.append(char)
                        signedNumber.append(char)
                    }

                    NumberFormat.Field.SIGN in attributes -> {
                        signSeen = true
                        signedNumber.append(char)
                    }

                    // El símbolo, sus espacios y las marcas de dirección quedan fuera.
                    else -> Unit
                }
                char = iterator.next()
            }
        }
        // Un negativo sin signo sería un dato falso en pantalla: mejor fallar aquí, en las pruebas.
        check(signSeen == amount.minor < 0L) { "ICU no marcó el signo del monto" }
        return Fields(
            number = IcuTextNormalizer.normalizeNumber(number.toString()),
            signedNumber = IcuTextNormalizer.normalizeNumber(signedNumber.toString()),
        )
    }

    private class Fields(val number: String, val signedNumber: String)

    companion object {
        /** Los decimales que admiten los ajustes del negocio. */
        const val MIN_CURRENCY_DECIMALS: Int = 0
        const val MAX_CURRENCY_DECIMALS: Int = 4

        /**
         * El código ISO de "sin moneda". Se fija para que ICU no aplique los decimales ni el
         * redondeo de la moneda de la región, que sería suponer una moneda.
         */
        private const val NO_CURRENCY_CODE = "XXX"

        private val NUMBER_FIELDS: Set<AttributedCharacterIterator.Attribute> =
            setOf(
                NumberFormat.Field.INTEGER,
                NumberFormat.Field.GROUPING_SEPARATOR,
                NumberFormat.Field.DECIMAL_SEPARATOR,
                NumberFormat.Field.FRACTION,
            )

        private fun currencyFormat(formatLocale: FormatLocale, decimals: Int): DecimalFormat =
            fixedDecimalFormat(formatLocale.numberLocale, NumberFormat.CURRENCYSTYLE, decimals)
                .apply {
                    currency = Currency.getInstance(NO_CURRENCY_CODE)
                    // Fijar la moneda vuelve a sus decimales: se fijan otra vez los del negocio.
                    minimumFractionDigits = decimals
                    maximumFractionDigits = decimals
                }
    }
}
