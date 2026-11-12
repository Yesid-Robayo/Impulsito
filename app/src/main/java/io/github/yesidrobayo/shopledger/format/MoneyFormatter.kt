package io.github.yesidrobayo.shopledger.format

import android.icu.text.CompactDecimalFormat
import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import android.icu.util.Currency
import android.icu.util.ULocale
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import java.math.BigDecimal
import java.text.AttributedCharacterIterator
import java.text.CharacterIterator
import java.util.Locale
import java.util.MissingResourceException

/**
 * Formatea montos de la moneda del negocio con el formato regional [formatLocale] y exactamente
 * [currencyDecimals] decimales, y da las partes para dibujarlos y leerlos en voz alta.
 *
 * El monto llega como [Money], un entero en unidad menor, y pasa a ICU como un decimal exacto: un
 * monto nunca toca un `Double`, ni siquiera para mostrarse. Los decimales salen de los ajustes del
 * negocio y no de la moneda que ICU conozca, porque el valor de uso puede no ser el de la norma.
 *
 * Las cifras se leen campo por campo del patrón de moneda de la región, no recortando un texto: así
 * los separadores son los que la región usa para dinero, que en algunas regiones no son los de
 * cualquier número. Se forman con la moneda neutra de ICU, para no aplicar los decimales ni el
 * redondeo de ninguna moneda.
 *
 * El símbolo, su lugar y el lugar del signo salen de ICU con la moneda [currencyCode] en esa
 * región; el símbolo también puede ser el [currencySymbol] que escribió la persona, que va donde
 * iría el de ICU. El espacio entre símbolo y cifras sale del patrón de la región y de la regla de
 * espaciado de CLDR. El nombre de la moneda sale en el idioma de la interfaz [uiLocale], nunca en
 * el del teléfono: el idioma elige las palabras, y el formato regional, los números.
 *
 * Es seguro usarlo desde varios hilos: ICU no lo es, y cada llamada toma su formato en exclusiva.
 */
class MoneyFormatter(
    val formatLocale: FormatLocale,
    val currencyCode: CurrencyCode,
    val currencyDecimals: Int,
    currencySymbol: String?,
    val uiLocale: Locale,
) {
    private val format: DecimalFormat
    private val nameFormat: DecimalFormat
    private val layout: CurrencyLayout

    /**
     * El símbolo que escribió la persona, sin espacios en los bordes ni marcas invisibles, o `null`
     * si no escribió ninguno y vale el de ICU.
     */
    val currencySymbol: String? = currencySymbol?.let(::cleanSymbol)?.takeIf { it.isNotEmpty() }

    /**
     * Los separadores y el signo con que este formateador escribe las cifras de un monto. El
     * dominio los usa para leer lo que la persona escribe en un campo de dinero, y así lee
     * exactamente lo que este formateador muestra.
     */
    val inputSymbols: NumberSymbols

    init {
        require(currencyDecimals in MIN_CURRENCY_DECIMALS..MAX_CURRENCY_DECIMALS) {
            "Los decimales de la moneda van de $MIN_CURRENCY_DECIMALS a $MAX_CURRENCY_DECIMALS"
        }
        format = currencyFormat(formatLocale, currencyDecimals)
        inputSymbols = numberInputSymbols(format)
        val currency = Currency.getInstance(currencyCode.code)
        layout = CurrencyLayout.read(formatLocale.numberLocale, currency)
        nameFormat = nameFormat(uiLocale, currency, currencyDecimals)
    }

    /** El símbolo que se dibuja: el de la persona, o el de ICU si no escribió ninguno. */
    private val shownSymbol: String = this.currencySymbol ?: layout.icuSymbol

    private val symbolSpace: SignedSpace = layout.spaceFor(shownSymbol)

    /** El monto en partes: signo, símbolo, cifras, su orden y el nombre de la moneda. */
    fun parts(amount: Money): MoneyParts {
        val fields = readFields(amount)
        return MoneyParts(
            negative = amount.minor < 0L,
            number = fields.number,
            symbol = shownSymbol,
            symbolFirst = layout.symbolFirst,
            symbolSpace = symbolSpace.of(negative = amount.minor < 0L),
            signPosition = layout.signPosition,
            currencyCode = currencyCode.code,
            currencyName = currencyName(amount),
        )
    }

    /**
     * El monto sin símbolo, con el signo menos tipográfico donde lo pone la región: `−1.234,56`.
     *
     * Sirve donde la moneda ya se dijo, como una columna con la moneda en el título.
     */
    fun format(amount: Money): String = readFields(amount).signedNumber

    /**
     * El monto en forma corta y sin símbolo, con las palabras y los separadores de la región, como
     * `300 mil` o `1,2 M`: lo que cabe en el eje de una gráfica, con la moneda dicha en su título.
     *
     * Solo sirve para dibujar marcas aproximadas: nunca para una cifra que se lee como dato. Si la
     * ICU del teléfono no sabe acortar en esta región, devuelve el monto entero de [format]: un eje
     * más ancho, nunca una pantalla que se cierra.
     */
    fun compact(amount: Money): String {
        val compact = compactFormat ?: return format(amount)
        val value = BigDecimal.valueOf(amount.minor, currencyDecimals)
        val text =
            try {
                synchronized(compact) { compact.format(value) }
            } catch (refused: IllegalArgumentException) {
                return format(amount)
            }
        return IcuTextNormalizer.normalizeNumber(text)
    }

    /**
     * El formato corto de la región, o `null` si esta ICU no lo puede armar.
     *
     * Se arma la primera vez que se pide, y no al crear el formateador: algunas ICU traen datos de
     * forma corta que su propio código rechaza al leerlos, como los de `so` en Android 8, y ese
     * rechazo no debe impedir formatear ningún otro monto. ICU lanza [IllegalArgumentException] o
     * [MissingResourceException] con datos que no puede leer; cualquier otra falla es un error y
     * sale.
     */
    private val compactFormat: CompactDecimalFormat? by lazy {
        try {
            CompactDecimalFormat.getInstance(
                formatLocale.numberLocale,
                CompactDecimalFormat.CompactStyle.SHORT,
            )
        } catch (refused: IllegalArgumentException) {
            null
        } catch (missing: MissingResourceException) {
            null
        }
    }

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

    /**
     * El nombre de la moneda que ICU pone al escribir este monto con palabras en el idioma de la
     * interfaz, como `1.00 US dollars`. Así el singular o el plural lo elige ICU con las cifras que
     * se ven, sin pasar el monto por un `Double`.
     */
    private fun currencyName(amount: Money): String {
        val value = BigDecimal.valueOf(amount.minor, currencyDecimals).abs()
        val name = StringBuilder()
        synchronized(nameFormat) {
            val iterator = nameFormat.formatToCharacterIterator(value)
            var char = iterator.first()
            while (char != CharacterIterator.DONE) {
                if (NumberFormat.Field.CURRENCY in iterator.attributes.keys) name.append(char)
                char = iterator.next()
            }
        }
        return cleanSymbol(name.toString()).ifEmpty { currencyCode.code }
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

        // Solo se lee el nombre de la moneda: las cifras de este formato no se muestran.
        private fun nameFormat(uiLocale: Locale, currency: Currency, decimals: Int): DecimalFormat =
            fixedDecimalFormat(
                ULocale.forLocale(uiLocale),
                NumberFormat.PLURALCURRENCYSTYLE,
                decimals,
            ).apply {
                this.currency = currency
                minimumFractionDigits = decimals
                maximumFractionDigits = decimals
            }
    }
}

/**
 * Dónde pone la región el símbolo y el signo de un monto en una moneda, leído de cómo ICU escribe un
 * positivo y un negativo de prueba, campo por campo, y si su patrón separa el símbolo de las cifras.
 */
private class CurrencyLayout(
    /** El símbolo que ICU da a la moneda en la región, sin marcas, o su código si no da ninguno. */
    val icuSymbol: String,
    val symbolFirst: Boolean,
    /** Si el patrón de la región separa cualquier símbolo de las cifras, en un positivo y un negativo. */
    private val patternSpace: SignedSpace,
    val signPosition: SignPosition,
) {
    /**
     * El espacio entre [symbol] y las cifras: el del patrón de la región, o el que agrega la regla de
     * espaciado de CLDR si el carácter del símbolo que toca las cifras no es un signo, como la `F` de
     * `CHF`. En un negativo con el signo entre los dos, lo que toca el símbolo es el signo, y la regla
     * no se aplica.
     *
     * La regla se aplica aquí y no se deja a ICU porque ICU de API 26 no la aplica: escribe
     * `XTS1,234.50`, y el de API 37, `XTS 1,234.50`. Así el mismo negocio se ve igual en los dos.
     */
    fun spaceFor(symbol: String): SignedSpace {
        val touches = needsCurrencySpacing(symbol, symbolFirst)
        val signBetween =
            signPosition == SignPosition.NEXT_TO_NUMBER ||
                signPosition == SignPosition.NEXT_TO_SYMBOL
        return SignedSpace(
            positive = patternSpace.positive || touches,
            negative = patternSpace.negative || (touches && !signBetween),
        )
    }

    companion object {
        /** Un monto con grupos y decimales, para que aparezcan todos los campos. */
        private val PROBE: BigDecimal = BigDecimal("1234.5")

        fun read(locale: ULocale, currency: Currency): CurrencyLayout {
            val format =
                requireNotNull(
                    NumberFormat.getInstance(locale, NumberFormat.CURRENCYSTYLE) as? DecimalFormat,
                ) {
                    "ICU no dio un formato decimal para la región"
                }.apply { this.currency = currency }
            val positive = segments(format, PROBE)
            val negative = segments(format, PROBE.negate())
            val symbolAt = positive.indexOfFirst { it.kind == Kind.SYMBOL }
            val numberAt = positive.indexOfFirst { it.kind == Kind.NUMBER }
            check(numberAt >= 0) { "ICU no escribió las cifras del monto" }
            val pattern = format.toPattern()
            val icuSymbol = if (symbolAt < 0) "" else cleanSymbol(positive[symbolAt].text)
            // Sin símbolo, ICU lo pondría donde va en el patrón: ese es el lugar del código.
            val symbolFirst = if (symbolAt < 0) patternSymbolFirst(pattern) else symbolAt < numberAt
            return CurrencyLayout(
                // Sin nada visible, como en las regiones que marcan la moneda con el separador
                // decimal, va el código: un monto nunca queda sin moneda.
                icuSymbol = icuSymbol.ifEmpty { currency.currencyCode },
                symbolFirst = symbolFirst,
                patternSpace =
                    SignedSpace(
                        positive = patternHasSpace(pattern.substringBefore(';')),
                        // Sin patrón negativo propio, el negativo es el positivo con el signo.
                        negative = patternHasSpace(pattern.substringAfter(';', pattern)),
                    ),
                signPosition = signPosition(negative),
            )
        }

        private fun signPosition(negative: List<Segment>): SignPosition {
            val sign = negative.indexOfFirst { it.kind == Kind.SIGN }
            val symbol = negative.indexOfFirst { it.kind == Kind.SYMBOL }
            val number = negative.indexOfFirst { it.kind == Kind.NUMBER }
            return when {
                // Sin signo marcado, el formato de las cifras ya falla: aquí basta un lugar seguro.
                sign < 0 || number < 0 -> SignPosition.LEADING

                symbol < 0 -> if (sign < number) SignPosition.LEADING else SignPosition.TRAILING

                sign < minOf(symbol, number) -> SignPosition.LEADING

                sign > maxOf(symbol, number) -> SignPosition.TRAILING

                // Entre los dos: va pegado al lado que no tiene el espacio.
                spaced(negative, sign, number) -> SignPosition.NEXT_TO_SYMBOL

                else -> SignPosition.NEXT_TO_NUMBER
            }
        }

        /** Si entre los segmentos [from] y [to], sin contarlos, hay un espacio. */
        private fun spaced(segments: List<Segment>, from: Int, to: Int): Boolean =
            segments.subList(minOf(from, to) + 1, maxOf(from, to)).any { it.hasSpace }

        private fun segments(format: DecimalFormat, value: BigDecimal): List<Segment> {
            val segments = mutableListOf<Segment>()
            val iterator = format.formatToCharacterIterator(value)
            var char = iterator.first()
            while (char != CharacterIterator.DONE) {
                val attributes = iterator.attributes.keys
                val kind =
                    when {
                        NumberFormat.Field.CURRENCY in attributes -> Kind.SYMBOL
                        NumberFormat.Field.SIGN in attributes -> Kind.SIGN
                        attributes.any { it in NUMBER_PARTS } -> Kind.NUMBER
                        else -> Kind.OTHER
                    }
                val last = segments.lastOrNull()
                if (last != null && last.kind == kind) {
                    segments[segments.lastIndex] = Segment(kind, last.text + char)
                } else {
                    segments += Segment(kind, char.toString())
                }
                char = iterator.next()
            }
            return segments
        }

        private val NUMBER_PARTS: Set<AttributedCharacterIterator.Attribute> =
            setOf(
                NumberFormat.Field.INTEGER,
                NumberFormat.Field.GROUPING_SEPARATOR,
                NumberFormat.Field.DECIMAL_SEPARATOR,
                NumberFormat.Field.FRACTION,
            )
    }

    private enum class Kind { SIGN, SYMBOL, NUMBER, OTHER }

    private class Segment(val kind: Kind, val text: String) {
        val hasSpace: Boolean get() = text.any(Character::isSpaceChar)
    }
}

/** El signo de moneda de los patrones de ICU. */
private const val PATTERN_CURRENCY_SIGN = '\u00A4'

/** Si el espacio entre símbolo y cifras va en un positivo y en un negativo, que pueden diferir. */
private class SignedSpace(val positive: Boolean, val negative: Boolean) {
    fun of(negative: Boolean): Boolean = if (negative) this.negative else positive
}

/** Si el patrón positivo pone el signo de moneda antes de las cifras. */
private fun patternSymbolFirst(pattern: String): Boolean {
    val positive = pattern.substringBefore(';')
    val sign = positive.indexOf(PATTERN_CURRENCY_SIGN)
    val digit = positive.indexOfFirst { it == '#' || it == '0' }
    return sign in 0 until digit
}

/**
 * Si un patrón, positivo o negativo, deja un espacio entre el signo de moneda y las cifras, como
 * `¤ #,##0.00` o `#,##0.00 ¤`. Es el espacio que va con cualquier símbolo, también uno escrito por
 * la persona.
 */
private fun patternHasSpace(subpattern: String): Boolean {
    val first = subpattern.indexOf(PATTERN_CURRENCY_SIGN)
    if (first < 0) return false
    val last = subpattern.lastIndexOf(PATTERN_CURRENCY_SIGN)
    val digitStart = subpattern.indexOfFirst { it == '#' || it == '0' }
    val digitEnd = subpattern.indexOfLast { it == '#' || it == '0' }
    val between =
        if (first < digitStart) {
            subpattern.substring(last + 1, digitStart)
        } else {
            subpattern.substring(digitEnd + 1, first)
        }
    return between.any(Character::isSpaceChar)
}

/**
 * La regla de ICU para el espacio entre un símbolo y las cifras cuando el patrón no lo pone: si el
 * carácter del símbolo que toca las cifras no es un signo ni un espacio, como la `F` de `CHF`, va un
 * espacio, para que no se lea `CHF1.00`. Con un signo, como `$`, no va.
 *
 * Un carácter que el teléfono no conoce cuenta como signo: Android 8 trae datos de Unicode viejos, y
 * no sabe que `U+20C0`, del bloque de monedas, es un signo de moneda.
 */
private fun needsCurrencySpacing(symbol: String, symbolFirst: Boolean): Boolean {
    if (symbol.isEmpty()) return false
    val touching = if (symbolFirst) symbol.codePointBefore(symbol.length) else symbol.codePointAt(0)
    return when (Character.getType(touching).toByte()) {
        Character.CURRENCY_SYMBOL,
        Character.MATH_SYMBOL,
        Character.MODIFIER_SYMBOL,
        Character.OTHER_SYMBOL,
        Character.SPACE_SEPARATOR,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        Character.UNASSIGNED,
        -> false

        else -> true
    }
}

/**
 * Un símbolo listo para dibujarse: sin marcas invisibles, como las de dirección, que la fuente no
 * tiene y que el aislamiento de la corrida ya reemplaza, sin espacios en los bordes, y con los
 * espacios finos cambiados por espacios duros.
 */
private fun cleanSymbol(text: String): String = IcuTextNormalizer.normalizeText(
    text.filterNot { Character.getType(it) == Character.FORMAT.toInt() },
).trim { Character.isWhitespace(it) || Character.isSpaceChar(it) }
