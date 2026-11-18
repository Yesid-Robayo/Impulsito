package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv

/**
 * Un número leído, en milésimas, que termina antes de la palabra [end]. [money] dice que es un
 * monto y no una cantidad: lo siguió un nombre de moneda ("2500 pesos") o se dijo en jerga de
 * dinero ("20 lucas").
 */
internal data class NumberRead(val milli: Long, val end: Int, val money: Boolean)

/**
 * Lee números escritos en cifras o dichos en palabras: "2500", "2.500", "1,5", "1/4", "dos mil
 * quinientos", "treinta y cinco", "20 lucas", "2k", "medio", "un par de", "dos y medio".
 *
 * Todo va en milésimas, así que "medio" es 500 y "dos mil", 2 000 000. Las palabras de un número
 * deben ir de mayor a menor ("doscientos cincuenta", no "cinco tres"), y la "y" solo une lo que se
 * dice unido: decenas con unidades ("treinta y cinco") o un grupo con lo que sigue cuando es de
 * diez o más ("mil y quinientos"). Así "vendí pan a mil y dos gaseosas" no se lee como 1002. El
 * nombre de moneda que sigue al número ("pesos", "reales") se salta.
 */
internal class NumberReader(
    private val lexicon: PhraseLexicon,
    private val style: NumberStyle,
    private val cents: Boolean = true,
) {
    /**
     * El número que empieza en [start]. [amount] dice que ya se sabe que es un monto, porque va
     * después de "a", "por" o "en": entonces "50 000" son cincuenta mil, como si el teclado
     * agrupara los miles con espacio (ver [spacedGroups]), y una cifra pequeña seguida de otra de
     * dos cifras son unidades y centavos, como se dice un precio: "twelve seventy five", "um e
     * vinte" (ver [withCents]). Esto último solo si la moneda tiene centavos ([cents]).
     */
    fun read(tokens: List<Token>, start: Int, amount: Boolean = false): NumberRead? {
        val fixed = lexicon.fixedNumbers.matchAt(tokens, start)
        if (fixed != null) return NumberRead(fixed.value, fixed.end, money = false)
        val spaced = spacedGroups(tokens, start, amount)
        if (spaced != null) return spaced
        val whole = whole(tokens, start) ?: return null
        val pointed = decimals(tokens, whole) ?: whole
        val priced = if (amount && cents) withCents(tokens, pointed) ?: pointed else pointed
        var index = priced.end
        while (index < tokens.size && tokens[index].norm in lexicon.currencies) index++
        val centWord = index < tokens.size && tokens[index].norm in lexicon.cents
        if (centWord) {
            return NumberRead(priced.milli / CENTS_PER_UNIT, index + 1, money = true)
        }
        return NumberRead(priced.milli, index, priced.money || index > priced.end)
    }

    /** El número en palabras o en cifras, sin decimales dichos ni moneda. */
    private fun whole(tokens: List<Token>, start: Int): NumberRead? {
        var total = 0L
        var money = false
        var current = 0L
        var last = Long.MAX_VALUE
        var grouped = false
        var started = false
        var index = start
        // "a hundred", "a thousand": el artículo es uno.
        val articled = start + 1 < tokens.size && tokens[start].norm in lexicon.oneArticles &&
            tokens[start + 1].norm.let { it in lexicon.multipliers || it in lexicon.magnitudes }
        if (articled) {
            current = Scale.MILLI
            started = true
            index++
        }
        while (index < tokens.size) {
            val step = stepAt(tokens, index, started, last, grouped) ?: break
            grouped = false
            when (step) {
                is Step.Add -> {
                    current += step.milli
                    last = step.milli
                }

                is Step.Times -> {
                    current = scaled(maxOf(current, Scale.MILLI), step.factor) ?: return null
                    last = step.factor
                }

                is Step.Group -> {
                    total += scaled(maxOf(current, Scale.MILLI), step.factor) ?: return null
                    current = 0L
                    last = step.factor
                    grouped = true
                    money = money || tokens[index].norm in lexicon.moneyMagnitudes
                }

                is Step.Fraction -> {
                    // "dos y medio" termina el número: después de la fracción no se suma nada más.
                    current += step.milli
                    last = 0L
                }

                is Step.Join -> Unit
            }
            started = true
            index = step.end
        }
        if (!started) return null
        return NumberRead(total + current, index, money)
    }

    /**
     * "uno coma cinco", "one point two five": los decimales dichos en palabras, una cifra por
     * palabra, después de un número entero. `null` si no siguen.
     */
    private fun decimals(tokens: List<Token>, whole: NumberRead): NumberRead? {
        val point = whole.end
        if (tokens.getOrNull(point)?.norm !in lexicon.decimalWords) return null
        val after = tokens.drop(point + 1)
        // "one point 25": cifras escritas; "one point two five": una palabra por cifra.
        val written = after.take(1).map { it.norm }.firstOrNull { it.all(Char::isDigit) }
        val spoken = after.asSequence().map { lexicon.numberWords[it.norm] }
            .takeWhile { it != null && it % Scale.MILLI == 0L && it < TEN }
            .filterNotNull()
            .joinToString("") { (it / Scale.MILLI).toString() }
        val digits = written ?: spoken
        if (digits.isEmpty()) return null
        val used = if (written != null) 1 else spoken.length
        val fraction = (digits + "000").take(DECIMAL_PLACES).toLong()
        return NumberRead(whole.milli + fraction, point + 1 + used, whole.money)
    }

    /**
     * Un precio dicho en dos partes: unidades de una o dos cifras y después los centavos, de diez
     * a noventa y nueve, pegados o unidos por "y": "twelve seventy five" es 12,75, "2 fifty" es
     * 2,50 y "trinta e cinco e noventa" es 35,90. `null` si no sigue así.
     */
    private fun withCents(tokens: List<Token>, units: NumberRead): NumberRead? {
        if (units.milli !in UNITS_RANGE || units.end >= tokens.size) return null
        val joined = tokens[units.end].norm in lexicon.conjunctions
        val start = if (joined) units.end + 1 else units.end
        val centsRead = whole(tokens, start) ?: return null
        val value = centsRead.milli
        if (value !in CENTS_MILLI || value % Scale.MILLI != 0L) return null
        // Lo que sigue cierra el precio: "a 1 y 20 empanadas" es un precio y otra línea.
        val next = tokens.getOrNull(centsRead.end)
        val closes = next == null || next.isSeparator || next.norm in lexicon.afterPrice
        if (!closes) return null
        return NumberRead(units.milli + value / CENTS_PER_UNIT, centsRead.end, money = false)
    }

    /**
     * Cifras agrupadas de a tres con espacios, que el dictado parte en palabras: "50 000" o "1 500
     * 000". Solo se juntan si no hay duda: si el formato regional agrupa con espacio, o si se sabe
     * que es un monto ([amount]). Así "vendí 3 500" con otro formato siguen siendo 3 y 500, que
     * puede ser 3 a 500. El primer grupo lleva de una a tres cifras y los siguientes, tres
     * exactas, sin decimales.
     */
    private fun spacedGroups(tokens: List<Token>, start: Int, amount: Boolean): NumberRead? {
        if (!amount && !style.grouping.isWhitespace()) return null
        if (start >= tokens.size) return null
        val head = tokens[start].norm
        if (head.length > GROUP || !head.all(Char::isDigit)) return null
        var end = start + 1
        while (end < tokens.size && isGroup(tokens[end].norm)) end++
        if (end == start + 1) return null
        val digits = tokens.subList(start, end).joinToString("") { it.norm }
        val whole = wholeDigits(digits) ?: return null
        var index = end
        while (index < tokens.size && tokens[index].norm in lexicon.currencies) index++
        return NumberRead(whole * Scale.MILLI, index, money = index > end)
    }

    private fun isGroup(norm: String): Boolean = norm.length == GROUP && norm.all(Char::isDigit)

    /** Si la palabra en [index] empieza un número que se puede leer. */
    fun startsAt(tokens: List<Token>, index: Int): Boolean = read(tokens, index) != null

    /**
     * El paso del número en [index]. Justo después de "mil", unas cifras de cientos siguen el
     * número: "2 mil 500" son 2500.
     */
    private fun stepAt(
        tokens: List<Token>,
        index: Int,
        started: Boolean,
        last: Long,
        grouped: Boolean,
    ): Step? {
        val norm = tokens[index].norm
        if (!started) return firstStep(norm, index)
        val fraction = lexicon.continuations.matchAt(tokens, index)
        if (fraction != null) return Step.Fraction(fraction.value, fraction.end)
        val group = lexicon.magnitudes[norm]
        if (group != null) return Step.Group(group, index + 1)
        val times = lexicon.multipliers[norm]
        if (times != null) return Step.Times(times, index + 1)
        val joined = norm in lexicon.conjunctions && joins(tokens, index + 1, last)
        if (joined) return Step.Join(index + 1)
        val word = lexicon.numberWords[norm] ?: return digitsAfterGroup(norm, index, last, grouped)
        return if (word < last) Step.Add(word, index + 1) else null
    }

    private fun digitsAfterGroup(norm: String, index: Int, last: Long, grouped: Boolean): Step? {
        if (!grouped || !norm.all(Char::isDigit)) return null
        // Cientos: de tres cifras, por debajo de lo que lleva el grupo.
        val value = parseDigits(norm, style)?.takeIf { it in HUNDREDS until last } ?: return null
        return Step.Add(value, index + 1)
    }

    private fun firstStep(norm: String, index: Int): Step? {
        val value = lexicon.numberWords[norm] ?: parseDigits(norm, style) ?: return null
        return Step.Add(value, index + 1)
    }

    /**
     * La "y" une el número solo si lo que sigue es una palabra menor que la anterior y suena unida
     * a ella.
     */
    private fun joins(tokens: List<Token>, next: Int, last: Long): Boolean {
        if (next >= tokens.size) return false
        val value = lexicon.numberWords[tokens[next].norm] ?: return false
        return value < last && (value >= JOIN_MINIMUM || last in TENS)
    }

    private sealed interface Step {
        val end: Int

        /** Una palabra o unas cifras que se suman a lo que va del grupo. */
        data class Add(val milli: Long, override val end: Int) : Step

        /** Multiplica lo que va del grupo sin cerrarlo: "two hundred". */
        data class Times(val factor: Long, override val end: Int) : Step

        /** Multiplica lo que va del grupo y lo cierra: "dos mil". */
        data class Group(val factor: Long, override val end: Int) : Step

        /** Una fracción que cierra el número: "y medio". */
        data class Fraction(val milli: Long, override val end: Int) : Step

        /** Una "y" entre palabras del número. */
        data class Join(override val end: Int) : Step
    }

    private companion object {
        /** Cifras de un grupo de miles. */
        const val GROUP = 3

        /**
         * Tope de un producto intermedio: 10^18 milésimas al cuadrado no caben, así que nada pasa
         * de aquí.
         */
        const val PRODUCT_LIMIT: Long = 1_000_000_000_000_000_000L

        /**
         * Desde diez, lo que sigue a una "y" puede ir después de cualquier grupo: "mil y
         * quinientos".
         */
        const val JOIN_MINIMUM: Long = 10_000L

        /** Las decenas, que se unen con "y" a una unidad: "treinta y cinco". */
        val TENS: Set<Long> = (2L..9L).map { it * 10_000L }.toSet()

        /** Desde aquí, en milésimas, unas cifras después de "mil" son sus cientos. */
        const val HUNDREDS: Long = 100_000L

        /** Centavos en una unidad de la moneda. */
        const val CENTS_PER_UNIT: Long = 100L

        /** Las unidades de un precio dicho en dos partes, en milésimas: de 1 a 99. */
        val UNITS_RANGE: LongRange = 1_000L..99_000L

        /** Lo que pueden ser los centavos dichos en dos partes, en milésimas: de 10 a 99. */
        val CENTS_MILLI: LongRange = 10_000L..99_000L

        /** Diez, en milésimas: las cifras que se dicen una a una después de la coma son menores. */
        const val TEN: Long = 10_000L

        /** `amount × factor / 1000`, o `null` si el producto no cabe en el tope. */
        fun scaled(amount: Long, factor: Long): Long? =
            if (amount > PRODUCT_LIMIT / factor) null else amount * factor / Scale.MILLI
    }
}

/**
 * Cifras más largas que esto no son una cantidad ni un monto, sino un código o un error de dictado.
 */
private const val MAX_DIGITS = 12

/** Milésimas por unidad, en cifras: el largo de la parte decimal que se conserva. */
private const val DECIMAL_PLACES = 3

/**
 * Lee unas cifras con el estilo del teclado: el separador decimal es el último
 * [NumberStyle.decimal], y el de miles agrupa de a tres. Un solo separador de miles que no deja
 * tres cifras detrás se lee como decimal, porque el dictado a veces escribe "1.5" con un teclado de
 * coma decimal. Una fracción como "1/4" también vale. Devuelve `null` si no se puede leer.
 */
internal fun parseDigits(text: String, style: NumberStyle): Long? {
    if (!text[0].isDigit()) return null
    val parts = text.split('/')
    return when (parts.size) {
        1 -> groupedByDecimal(text, style) ?: decimal(text, style)
        2 -> ratio(parts[0], parts[1])
        else -> null
    }
}

private fun ratio(numerator: String, denominator: String): Long? {
    val top = wholeDigits(numerator) ?: return null
    val bottom = wholeDigits(denominator) ?: return null
    if (bottom == 0L) return null
    return roundDiv(top * Scale.MILLI, bottom)
}

private fun wholeDigits(text: String): Long? =
    if (text.length in 1..MAX_DIGITS && text.all(Char::isDigit)) text.toLong() else null

/**
 * El dictado del teléfono escribe los miles con el separador decimal del teclado: "50,000" o
 * "1,500,000" con coma decimal son cincuenta mil y un millón y medio. Si el separador decimal parte
 * las cifras en grupos de tres y lo de adelante no es cero, son miles; "0,500" sigue siendo medio, y
 * "1,5" o "2,25" siguen siendo decimales.
 */
private fun groupedByDecimal(text: String, style: NumberStyle): Long? {
    if (style.grouping in text) return null
    val groups = text.split(style.decimal)
    val grouped =
        groups.size > 1 && groups[0] != "0" && groups[0].length <= 3 &&
            groups.drop(1).all { it.length == 3 }
    return if (grouped) wholeDigits(groups.joinToString(""))?.times(Scale.MILLI) else null
}

private fun decimal(text: String, style: NumberStyle): Long? {
    val point = decimalPoint(text, style)
    val whole = groupedDigits(text.substring(0, point), style.grouping) ?: return null
    val fraction = text.substring(minOf(point + 1, text.length))
    if (!fraction.all(Char::isDigit)) return null
    return whole * Scale.MILLI + (fraction + "000").take(DECIMAL_PLACES).toLong()
}

private fun decimalPoint(text: String, style: NumberStyle): Int {
    val decimal = text.lastIndexOf(style.decimal)
    if (decimal >= 0) return decimal
    val grouping = text.indexOf(style.grouping)
    val lone =
        grouping >= 0 && grouping == text.lastIndexOf(style.grouping) &&
            text.length - grouping - 1 != 3
    return if (lone) grouping else text.length
}

private fun groupedDigits(text: String, grouping: Char): Long? {
    val groups = text.split(grouping)
    val wellGrouped =
        groups.size == 1 || (groups[0].length <= 3 && groups.drop(1).all { it.length == 3 })
    return if (wellGrouped) wholeDigits(groups.joinToString("")) else null
}
