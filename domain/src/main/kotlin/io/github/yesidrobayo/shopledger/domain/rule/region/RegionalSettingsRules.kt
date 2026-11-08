package io.github.yesidrobayo.shopledger.domain.rule.region

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput

/** Por qué no se puede guardar una configuración regional. Cada motivo es de un campo. */
enum class RegionalSettingsProblem {
    /** La moneda sigue sin confirmar: sin ella no se puede registrar dinero. */
    CURRENCY_UNCONFIRMED,

    /**
     * La moneda ya tiene registros con dinero y se intentó cambiar su código o sus decimales:
     * cambiaría el valor de cada monto guardado.
     */
    CURRENCY_LOCKED,

    /** Los decimales no van de 0 a 4. */
    DECIMALS_OUT_OF_RANGE,

    /** El símbolo propio tiene más de 8 caracteres. */
    SYMBOL_TOO_LONG,

    /** El paso del redondeo de efectivo es negativo o pasa el tope de un precio por unidad. */
    CASH_ROUNDING_OUT_OF_RANGE,

    /** El formato regional no tiene la forma de una etiqueta BCP 47, o no dice un idioma. */
    FORMAT_LOCALE_INVALID,

    /** Este teléfono no conoce la zona horaria elegida. */
    TIME_ZONE_UNKNOWN,
}

/** Resultado de [RegionalSettingsRules.check]. */
sealed interface RegionalSettingsCheck {
    /** Se puede guardar [input], ya normalizado: el símbolo sin espacios, o nulo si quedó vacío. */
    data class Valid(val input: RegionalSettingsInput) : RegionalSettingsCheck

    /** No se puede guardar, por todos estos [problems] a la vez: la pantalla marca cada campo. */
    data class Invalid(val problems: Set<RegionalSettingsProblem>) : RegionalSettingsCheck
}

/**
 * Lo que revisa una configuración regional antes de guardarla.
 *
 * Solo revisa la forma de cada dato y el bloqueo de la moneda. Nunca compara la moneda, el formato
 * ni la zona con una lista de países o monedas: una copia de otro negocio puede traer cualquiera, y
 * la pantalla ya ofrece solo lo que el teléfono conoce. La zona sí se busca en la base de zonas del
 * teléfono, porque una zona desconocida no puede fechar ningún registro.
 */
object RegionalSettingsRules {
    /** Decimales que admite la moneda del negocio. */
    const val MAX_CURRENCY_DECIMALS: Int = 4

    /** Caracteres que admite un símbolo propio. */
    const val MAX_SYMBOL_LENGTH: Int = 8

    // Lo que admite la base para el formato: una etiqueta BCP 47 de hasta 35 caracteres.
    private const val MAX_FORMAT_TAG_LENGTH = 35

    // Un idioma de letras y, después, partes de letras o cifras separadas por guiones.
    private val TAG_SHAPE = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")

    // El idioma sin determinar de BCP 47: un formato sin idioma no sabe escribir números.
    private const val UNDETERMINED = "und"

    /**
     * Revisa [input] contra la moneda guardada [current]. Con la moneda bloqueada, el código y los
     * decimales tienen que ser los mismos; el símbolo, el redondeo y los formatos siguen libres.
     */
    fun check(input: RegionalSettingsInput, current: BusinessCurrency): RegionalSettingsCheck {
        val symbol = input.currencySymbol.orEmpty().trim().ifEmpty { null }
        val problems = buildSet {
            if (!input.currencyCode.isConfirmed) add(RegionalSettingsProblem.CURRENCY_UNCONFIRMED)
            if (current.isLocked &&
                (input.currencyCode != current.code || input.currencyDecimals != current.decimals)
            ) {
                add(RegionalSettingsProblem.CURRENCY_LOCKED)
            }
            if (input.currencyDecimals !in 0..MAX_CURRENCY_DECIMALS) {
                add(RegionalSettingsProblem.DECIMALS_OUT_OF_RANGE)
            }
            if (symbol != null && characterCount(symbol) > MAX_SYMBOL_LENGTH) {
                add(RegionalSettingsProblem.SYMBOL_TOO_LONG)
            }
            if (input.cashRounding < Money.ZERO ||
                !Limit.UNIT_MONEY.allows(input.cashRounding.minor)
            ) {
                add(RegionalSettingsProblem.CASH_ROUNDING_OUT_OF_RANGE)
            }
            if (!isFormatTag(input.formatLocaleTag)) {
                add(RegionalSettingsProblem.FORMAT_LOCALE_INVALID)
            }
            if (BusinessTimeZone.of(input.businessTimeZoneId) == null) {
                add(RegionalSettingsProblem.TIME_ZONE_UNKNOWN)
            }
        }
        return if (problems.isEmpty()) {
            RegionalSettingsCheck.Valid(input.copy(currencySymbol = symbol))
        } else {
            RegionalSettingsCheck.Invalid(problems)
        }
    }

    /** Si [tag] tiene la forma de una etiqueta BCP 47 con un idioma determinado. */
    private fun isFormatTag(tag: String): Boolean = tag.length <= MAX_FORMAT_TAG_LENGTH &&
        TAG_SHAPE.matches(tag) &&
        !tag.substringBefore('-').equals(UNDETERMINED, ignoreCase = true)

    /**
     * Los caracteres de [text] como los cuenta la base: un símbolo fuera del plano básico, que en
     * Kotlin son dos `Char`, cuenta como uno.
     */
    private fun characterCount(text: String): Int = text.count { !it.isLowSurrogate() }
}
