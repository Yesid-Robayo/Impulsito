package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomValueIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ExtraNameIssue

/** Resultado de [CustomFieldRules.value]. */
sealed interface CustomValueCheck {
    /** En blanco: el producto se queda sin valor en ese campo. */
    data object Blank : CustomValueCheck

    /** El texto que se guarda, ya en su forma neutra. */
    data class Valid(val stored: String) : CustomValueCheck

    /** No sirve para el tipo del campo. */
    data class Invalid(val issue: CustomValueIssue) : CustomValueCheck
}

/** Resultado de [CustomFieldRules.name]. */
sealed interface ExtraNameCheck {
    /** El nombre recortado y su forma normalizada, que decide si dos nombres son el mismo. */
    data class Valid(val name: String, val normalizedName: String) : ExtraNameCheck

    data class Invalid(val issue: ExtraNameIssue) : ExtraNameCheck
}

/**
 * Las reglas de los campos propios y las etiquetas.
 *
 * Un valor se guarda como texto neutro, sin formato regional, y se lee según el tipo de su campo:
 * así un número o una fecha se leen igual con cualquier idioma o formato del teléfono. Los valores
 * solo sirven para buscar y filtrar: nunca entran en un cálculo de dinero ni de stock, y por eso un
 * número no pasa nunca a decimal binario.
 */
object CustomFieldRules {
    /** Largo máximo del nombre de un campo o de una etiqueta. */
    const val NAME_MAX: Int = 40

    /** Largo máximo de un valor, en caracteres. */
    const val VALUE_MAX: Int = 200

    /** Cifras como máximo en cada lado del punto de un número. */
    private const val NUMBER_DIGITS_MAX = 18

    private val NUMBER = Regex("^(-?)(\\d+)(?:\\.(\\d+))?$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** [raw] como nombre de un campo o de una etiqueta, de 1 a [NAME_MAX] caracteres. */
    fun name(raw: String): ExtraNameCheck = when (val name = CatalogText.name(raw, NAME_MAX)) {
        CatalogName.Empty -> ExtraNameCheck.Invalid(ExtraNameIssue.EMPTY)
        CatalogName.TooLong -> ExtraNameCheck.Invalid(ExtraNameIssue.TOO_LONG)
        is CatalogName.Valid -> ExtraNameCheck.Valid(name.name, name.normalizedName)
    }

    /**
     * [raw] como valor de un campo de tipo [type]. El número llega ya en forma neutra, con punto
     * decimal y sin separador de miles: la pantalla lo lee con el formato de la región. Se guarda
     * sin ceros de más, para que `1.50` y `1.5` sean el mismo valor. La fecha llega como
     * `2026-11-14`, y el sí o no como `true` o `false`.
     */
    fun value(type: CustomFieldType?, raw: String): CustomValueCheck {
        val text = raw.trim()
        if (text.isEmpty()) return CustomValueCheck.Blank
        val known = type ?: return CustomValueCheck.Invalid(CustomValueIssue.UNKNOWN_TYPE)
        return when (known) {
            CustomFieldType.TEXT ->
                if (CatalogText.length(text) > VALUE_MAX) {
                    CustomValueCheck.Invalid(CustomValueIssue.TOO_LONG)
                } else {
                    CustomValueCheck.Valid(text)
                }

            CustomFieldType.NUMBER -> number(text)

            CustomFieldType.DATE ->
                if (DATE.matches(text) && BusinessDate.parse(text) != null) {
                    CustomValueCheck.Valid(text)
                } else {
                    CustomValueCheck.Invalid(CustomValueIssue.NOT_A_DATE)
                }

            CustomFieldType.BOOLEAN ->
                if (text == TRUE || text == FALSE) {
                    CustomValueCheck.Valid(text)
                } else {
                    CustomValueCheck.Invalid(CustomValueIssue.NOT_A_BOOLEAN)
                }
        }
    }

    /** El texto guardado de un sí. */
    const val TRUE: String = "true"

    /** El texto guardado de un no. */
    const val FALSE: String = "false"

    private fun number(text: String): CustomValueCheck {
        val parts = NUMBER.matchEntire(text)
            ?: return CustomValueCheck.Invalid(CustomValueIssue.NOT_A_NUMBER)
        val (sign, whole, fraction) = parts.destructured
        val integer = whole.trimStart('0').ifEmpty { "0" }
        val decimals = fraction.trimEnd('0')
        if (integer.length > NUMBER_DIGITS_MAX || fraction.length > NUMBER_DIGITS_MAX) {
            return CustomValueCheck.Invalid(CustomValueIssue.NOT_A_NUMBER)
        }
        val magnitude = if (decimals.isEmpty()) integer else "$integer.$decimals"
        // Un cero no lleva signo: `-0` y `0` son el mismo valor.
        val negative = sign.isNotEmpty() && magnitude != "0"
        return CustomValueCheck.Valid(if (negative) "-$magnitude" else magnitude)
    }
}
