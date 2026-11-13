package io.github.yesidrobayo.shopledger.domain.rule.region

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols

/**
 * Lee lo que la persona escribe en un campo de monto, cantidad o porcentaje y lo pasa al entero
 * exacto que se guarda, con los símbolos del formato regional del negocio.
 *
 * Nunca pasa por un número con decimales binarios: cada cifra se suma a un `Long` en la escala del
 * campo, así que `0,1` son exactamente 10 unidades menores con dos decimales. Tampoco redondea: un
 * decimal de más o un valor sobre el tope se rechazan con su motivo, porque casi siempre son un
 * error de digitación, y guardarlos recortados sería guardar un dato que nadie escribió.
 *
 * Lo que acepta, además de las cifras y los separadores de la región:
 * - espacios al principio y al final;
 * - un solo signo, delante o detrás del número: el menos de la región, el guion del teclado,
 *   U+2212 o el signo más;
 * - un separador decimal sin cifras detrás, como `12,` mientras se escribe;
 * - los grupos de cifras solo si están donde el formato los pondría: con grupos de tres, `1.234`
 *   vale y `1.5` no. Se pueden omitir del todo;
 * - cifras de cualquier sistema de escritura y los signos de ancho completo de los teclados de Asia
 *   oriental, que valen lo mismo que los del teclado latino.
 *
 * El tope acota el valor absoluto: un negativo enorme también es un error de digitación. Que el
 * campo admita negativos, o cero, lo decide la regla del propio campo.
 *
 * No lee el idioma ni la región del teléfono: todo llega por parámetro.
 */
object NumberInputParser {
    /** Decimales que admiten los ajustes del negocio. */
    private const val MAX_CURRENCY_DECIMALS = 4

    /** Decimales de una cantidad en milésimas. */
    private const val QUANTITY_DECIMALS = 3

    /** Decimales de un porcentaje en puntos básicos: 12,25 % son 1225. */
    private const val PERCENT_DECIMALS = 2

    /** Decimales de una cotización en millonésimas. */
    private const val RATE_DECIMALS = 6

    private const val RADIX = 10L

    /** Del primer al último signo de ancho completo, que equivalen a los de U+0021 a U+007E. */
    private val FULLWIDTH_FORMS = '\uFF01'..'\uFF5E'
    private const val FULLWIDTH_OFFSET = 0xFEE0

    /** Marcas de dirección que llegan al pegar texto de otra app: no se ven ni son del número. */
    private val DIRECTION_MARKS: Set<Char> = setOf('\u200E', '\u200F', '\u061C')

    /**
     * Un monto de la moneda del negocio, con hasta [currencyDecimals] decimales y en unidad menor:
     * con dos decimales, `1.234,5` son 123450. El tope es [limit], por ejemplo el de un precio por
     * unidad o el de un documento.
     */
    fun money(
        text: String,
        symbols: NumberSymbols,
        currencyDecimals: Int,
        limit: Limit,
    ): NumberInput<Money> {
        require(currencyDecimals in 0..MAX_CURRENCY_DECIMALS) {
            "Los decimales de la moneda van de 0 a $MAX_CURRENCY_DECIMALS"
        }
        return parse(text, symbols, currencyDecimals, limit.max, percentAllowed = false)
            .map(::Money)
    }

    /**
     * Una cantidad en milésimas, con hasta tres decimales: `0,25` son 250. Si la unidad admite
     * fracciones lo decide después la regla de la unidad, con el valor ya leído.
     */
    fun quantity(text: String, symbols: NumberSymbols, limit: Limit): NumberInput<QuantityMilli> =
        parse(text, symbols, QUANTITY_DECIMALS, limit.max, percentAllowed = false)
            .map(::QuantityMilli)

    /**
     * Un porcentaje en puntos básicos, con hasta dos decimales: `12,5` son 1250. Admite el signo
     * de porcentaje de la región o el del teclado, delante o detrás, como lo escribe el
     * formateador. Los porcentajes no tienen un tope común, así que el campo da el suyo en
     * [maximum], por ejemplo el del margen objetivo.
     */
    fun percent(
        text: String,
        symbols: NumberSymbols,
        maximum: BasisPoints,
    ): NumberInput<BasisPoints> {
        require(maximum.bp >= 0L) {
            "El tope de un porcentaje acota el valor absoluto: no puede ser negativo"
        }
        return parse(text, symbols, PERCENT_DECIMALS, maximum.bp, percentAllowed = true)
            .map(::BasisPoints)
    }

    /**
     * Un conteo entero, como unos puntos, sin decimales y con los separadores de grupo de la
     * región, igual que se muestra: `1.250` son 1250. El tope es [limit].
     */
    fun count(text: String, symbols: NumberSymbols, limit: Limit): NumberInput<Long> =
        parse(text, symbols, decimals = 0, maximum = limit.max, percentAllowed = false)

    /**
     * Una cotización en millonésimas, con hasta seis decimales y los separadores de cualquier número
     * de la región: `4.150,5` son 4150500000. El tope es [limit].
     */
    fun rateMicro(text: String, symbols: NumberSymbols, limit: Limit): NumberInput<Long> =
        parse(text, symbols, RATE_DECIMALS, limit.max, percentAllowed = false)

    private fun <T> NumberInput<Long>.map(wrap: (Long) -> T): NumberInput<T> = when (this) {
        is NumberInput.Value -> NumberInput.Value(wrap(value))
        NumberInput.Blank -> NumberInput.Blank
        NumberInput.Malformed -> NumberInput.Malformed
        is NumberInput.TooManyDecimals -> this
        is NumberInput.TooLarge -> NumberInput.TooLarge(wrap(maximum))
    }

    /**
     * El número de [text] multiplicado por 10 elevado a [decimals], o el motivo del rechazo. Los
     * motivos se revisan en orden: primero la forma, después los decimales y al final el tope, para
     * que el mensaje hable de lo primero que hay que corregir.
     */
    private fun parse(
        text: String,
        symbols: NumberSymbols,
        decimals: Int,
        maximum: Long,
        percentAllowed: Boolean,
    ): NumberInput<Long> {
        val chars = canonical(text)
        if (chars.isBlank()) return NumberInput.Blank
        val affixes = Affixes.strip(chars, symbols, percentAllowed) ?: return NumberInput.Malformed
        val body = Body.scan(chars, affixes, symbols) ?: return NumberInput.Malformed
        return if (body.fractionDigits > decimals) {
            NumberInput.TooManyDecimals(decimals)
        } else {
            scaled(body, decimals, maximum, affixes.negative)
        }
    }

    /** Pasa los signos de ancho completo a los del teclado latino y quita las de dirección. */
    private fun canonical(text: String): String = buildString(text.length) {
        for (char in text) {
            when (char) {
                in DIRECTION_MARKS -> Unit
                in FULLWIDTH_FORMS -> append(char - FULLWIDTH_OFFSET)
                else -> append(char)
            }
        }
    }

    /** Suma cifra a cifra sin pasar nunca del tope, así que la cuenta no puede desbordar. */
    private fun scaled(
        body: Body,
        decimals: Int,
        maximum: Long,
        negative: Boolean,
    ): NumberInput<Long> {
        var magnitude = 0L
        // Los decimales que no se escribieron son ceros: con dos decimales, `12` son 1200.
        val padding = decimals - body.fractionDigits
        for (index in 0 until body.values.size + padding) {
            val digit = if (index < body.values.size) body.values[index] else 0
            // Si al multiplicar por diez ya pasaría del tope, no se multiplica: así nunca desborda.
            if (magnitude > maximum / RADIX) return NumberInput.TooLarge(maximum)
            val shifted = magnitude * RADIX
            if (digit > maximum - shifted) return NumberInput.TooLarge(maximum)
            magnitude = shifted + digit
        }
        return NumberInput.Value(if (negative) -magnitude else magnitude)
    }

    /**
     * Lo que rodea al número: espacios, el signo y, en un porcentaje, el signo de porcentaje. Se
     * quitan de los dos extremos en cualquier orden, porque cada región los pone en otro: `−20 %`,
     * `−%20` o `20−`.
     */
    private class Affixes(val start: Int, val end: Int, val negative: Boolean) {
        companion object {
            /** Los límites del número dentro de [chars], o `null` si queda vacío. */
            fun strip(chars: String, symbols: NumberSymbols, percentAllowed: Boolean): Affixes? {
                var start = 0
                var end = chars.length
                var sign: Char? = null
                var percentSeen = !percentAllowed
                while (true) {
                    while (start < end && chars[start].isWhitespace()) start++
                    while (end > start && chars[end - 1].isWhitespace()) end--
                    if (start == end) return null
                    val first = chars[start]
                    val last = chars[end - 1]
                    when {
                        sign == null && isSign(first, symbols) -> {
                            sign = first
                            start++
                        }

                        sign == null && isSign(last, symbols) -> {
                            sign = last
                            end--
                        }

                        !percentSeen && symbols.isPercent(first) -> {
                            percentSeen = true
                            start++
                        }

                        !percentSeen && symbols.isPercent(last) -> {
                            percentSeen = true
                            end--
                        }

                        else -> {
                            val negative = sign != null && symbols.isMinus(sign)
                            return Affixes(start, end, negative)
                        }
                    }
                }
            }

            private fun isSign(char: Char, symbols: NumberSymbols): Boolean =
                char == NumberSymbols.PLUS_SIGN || symbols.isMinus(char)
        }
    }

    /** Las cifras del número, en orden, y cuántas van después del separador decimal. */
    private class Body(val values: IntArray, val fractionDigits: Int) {
        companion object {
            /**
             * Recorre el número entre los límites de [affixes]. Devuelve `null` si tiene algo que
             * no es una cifra ni un separador, un separador fuera de lugar o ninguna cifra.
             *
             * Cada grupo se revisa al cerrarse: el primero puede ser más corto, los del medio
             * tienen el tamaño secundario, y el último, el que toca los decimales, el primario.
             */
            fun scan(chars: String, affixes: Affixes, symbols: NumberSymbols): Body? {
                val grouping = symbols.grouping
                val values = IntArray(affixes.end - affixes.start)
                var count = 0
                var fractionDigits = 0
                var inFraction = false
                var groupLength = 0
                var groupsClosed = 0
                var lastGroupSize: Int? = null
                for (index in affixes.start until affixes.end) {
                    val char = chars[index]
                    val digit = char.digitToIntOrNull()
                    when {
                        digit != null -> {
                            values[count++] = digit
                            if (inFraction) fractionDigits++ else groupLength++
                        }

                        inFraction -> return null

                        char == symbols.decimalSeparator -> inFraction = true

                        grouping != null && grouping.reads(char) -> {
                            val fits =
                                if (groupsClosed == 0) {
                                    groupLength in 1..grouping.secondarySize
                                } else {
                                    groupLength == grouping.secondarySize
                                }
                            if (!fits) return null
                            groupsClosed++
                            groupLength = 0
                            lastGroupSize = grouping.primarySize
                        }

                        else -> return null
                    }
                }
                val lastGroupFits = lastGroupSize == null || groupLength == lastGroupSize
                return if (count > 0 && lastGroupFits) {
                    Body(values.copyOf(count), fractionDigits)
                } else {
                    null
                }
            }
        }
    }
}
