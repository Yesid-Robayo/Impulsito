package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateFinding
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind

/**
 * Cómo leyó el validador un valor guardado en una base candidata, y qué hallazgo deja.
 */
sealed interface StoredReading<out T> {
    /** El valor leído, si la base sigue siendo restaurable con él. */
    val valueOrNull: T?

    /** El hallazgo que deja la lectura, si deja alguno. */
    val findingOrNull: CandidateFinding?

    /** Un valor de su lista, sin hallazgo. */
    data class Recognized<out T>(val value: T) : StoredReading<T> {
        override val valueOrNull: T get() = value
        override val findingOrNull: CandidateFinding? get() = null
    }

    /**
     * Un valor desconocido de un dato abierto: se lee como [value] y deja un aviso. La copia no se
     * rechaza ni se marca como dudosa por esto.
     */
    data class Substituted<out T>(val value: T, val finding: CandidateFinding) : StoredReading<T> {
        override val valueOrNull: T get() = value
        override val findingOrNull: CandidateFinding get() = finding
    }

    /** Un valor desconocido donde la lista es cerrada: es daño, y deja un hallazgo fatal. */
    data class Damaged(val finding: CandidateFinding) : StoredReading<Nothing> {
        override val valueOrNull: Nothing? get() = null
        override val findingOrNull: CandidateFinding get() = finding
    }
}

/**
 * Las reglas del validador para leer valores guardados de lista cerrada o abierta, y para revisar la
 * forma de los datos regionales.
 *
 * Separa dos casos que parecen iguales y no lo son. Un tipo del libro o de un documento desconocido,
 * con la misma versión de esquema, es daño: esa base no se puede leer con confianza. Un tipo de
 * método de pago desconocido no lo es, porque los métodos de pago son datos de la persona: se lee
 * como "otro" y solo se cuenta. Y ninguna regla compara un dato regional con una lista de países,
 * monedas o zonas: solo revisa su forma.
 */
object StoredValueRules {
    /**
     * Lee [stored] como uno de los valores de [entries], una lista cerrada del libro o de un
     * documento. Un valor desconocido es fatal.
     */
    fun <E : Enum<E>> readLedgerValue(stored: String, entries: List<E>): StoredReading<E> {
        val value = entries.firstOrNull { it.name == stored }
            ?: return StoredReading.Damaged(oneCase(CandidateCheck.LEDGER_ENUMS))
        return StoredReading.Recognized(value)
    }

    /**
     * Lee [stored] como la clase de un método de pago, de un método o de la copia que guarda cada
     * pago. Un valor desconocido se trata como [PaymentMethodKind.OTHER] y deja un aviso: nunca
     * rechaza la copia.
     */
    fun readPaymentMethodKind(stored: String): StoredReading<PaymentMethodKind> {
        val kind = PaymentMethodKind.fromStoredOrNull(stored)
            ?: return StoredReading.Substituted(
                PaymentMethodKind.OTHER,
                oneCase(CandidateCheck.OPEN_DATA),
            )
        return StoredReading.Recognized(kind)
    }

    /**
     * El aviso que deja un dato regional de forma incorrecta, o `null` si su forma es buena.
     *
     * Una forma buena no garantiza que el valor exista: una zona escrita en un teléfono con la base
     * de zonas más nueva puede no existir en este. Eso no es un hallazgo, y lo resuelve quien usa el
     * valor.
     */
    fun checkRegionalShape(shape: RegionalShape, text: String): CandidateFinding? =
        if (shape.matches(text)) null else oneCase(CandidateCheck.OPEN_DATA)

    private fun oneCase(check: CandidateCheck) = CandidateFinding(check, cases = 1)
}

/**
 * La forma de un dato regional del negocio, la única revisión que el validador hace sobre él.
 *
 * Son formas de los estándares, no listas de valores: cualquier código de tres letras pasa como
 * moneda, exista o no, y cualquier identificador bien escrito pasa como zona. Así una copia nunca se
 * rechaza ni se marca porque su moneda, su región o su zona sean nuevas o desconocidas para este
 * teléfono.
 */
enum class RegionalShape {
    /** Un código de moneda: exactamente tres letras ASCII mayúsculas. */
    CURRENCY_CODE,

    /**
     * Una etiqueta de idioma o de formato regional: subetiquetas de 1 a 8 letras o cifras ASCII,
     * separadas por guiones, y la primera, de 2 a 8 letras. Así pasan tanto `es` como
     * `th-TH-u-nu-thai`.
     */
    LANGUAGE_TAG,

    /**
     * Un identificador de zona horaria: partes separadas por `/`, con letras y cifras ASCII y los
     * signos `_`, `-`, `+`, `.` y `:`. Ninguna parte vacía, `.` o `..`, ni que empiece con `-`.
     */
    TIME_ZONE_ID,
    ;

    /** Si [text] tiene esta forma. */
    fun matches(text: String): Boolean = when (this) {
        CURRENCY_CODE -> text.length == CURRENCY_CODE_LENGTH && text.all { it in 'A'..'Z' }
        LANGUAGE_TAG -> isLanguageTag(text)
        TIME_ZONE_ID -> isTimeZoneId(text)
    }

    private companion object {
        const val CURRENCY_CODE_LENGTH = 3
        const val MIN_LANGUAGE_LENGTH = 2
        const val MAX_SUBTAG_LENGTH = 8
        val ZONE_SYMBOLS = setOf('_', '-', '+', '.', ':')

        fun isLanguageTag(text: String): Boolean {
            val subtags = text.split('-')
            val language = subtags.first()
            return language.length in MIN_LANGUAGE_LENGTH..MAX_SUBTAG_LENGTH &&
                language.all(::isAsciiLetter) &&
                subtags.all { it.length in 1..MAX_SUBTAG_LENGTH && it.all(::isAsciiLetterOrDigit) }
        }

        fun isTimeZoneId(text: String): Boolean = text.split('/').all { part ->
            part.isNotEmpty() && part != "." && part != ".." && !part.startsWith('-') &&
                part.all { isAsciiLetterOrDigit(it) || it in ZONE_SYMBOLS }
        }

        fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'

        fun isAsciiLetterOrDigit(char: Char): Boolean = isAsciiLetter(char) || char in '0'..'9'
    }
}
