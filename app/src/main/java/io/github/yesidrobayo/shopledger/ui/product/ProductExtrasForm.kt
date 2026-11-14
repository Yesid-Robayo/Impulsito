package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomValueIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import kotlinx.datetime.DayOfWeek

/** Hoy y el primer día de la semana del negocio, para elegir la fecha de un campo propio. */
@Immutable
data class FieldCalendar(val today: BusinessDate, val weekStartDay: DayOfWeek)

/**
 * Los campos propios y las etiquetas del producto en su formulario.
 *
 * [fields] son los campos vigentes, en su orden, y [tags] todas las etiquetas. [values] es lo
 * escrito en cada campo, por id, tal como se ve: un número con los separadores de la región. Con
 * [errors], un valor no sirve para el tipo de su campo; lo escrito se conserva. [calendar] es
 * `null` mientras no se lee, o con una zona horaria que no se conoce: entonces una fecha no se
 * puede elegir.
 */
@Immutable
data class ProductExtrasForm(
    val fields: List<CustomField> = emptyList(),
    val tags: List<ProductTag> = emptyList(),
    val values: Map<Long, String> = emptyMap(),
    val tagIds: Set<Long> = emptySet(),
    val errors: Map<Long, CustomValueIssue> = emptyMap(),
    val calendar: FieldCalendar? = null,
) {
    /** Lo que no se usa no estorba: sin campos ni etiquetas, la sección no aparece. */
    val visible: Boolean get() = fields.isNotEmpty() || tags.isNotEmpty()

    /** Si el producto ya tiene algún valor o alguna etiqueta: `Más detalles` se abre solo. */
    val hasData: Boolean get() = values.values.any { it.isNotBlank() } || tagIds.isNotEmpty()
}

/**
 * Cómo se ve y cómo se guarda el valor de un campo propio. Se guarda en forma neutra, la misma con
 * cualquier región: un número con punto decimal, sin separador de miles y con el guion como signo.
 * En pantalla, el número lleva el separador decimal y el signo de la región, y se escribe con
 * ellos. Los demás tipos se ven tal cual se guardan: la fecha la dibuja su campo, y el sí o no, su
 * interruptor.
 */
object CustomValueText {
    private const val NEUTRAL_DECIMAL = '.'
    private const val NEUTRAL_MINUS = '-'
    private const val TYPOGRAPHIC_MINUS = '−'

    /** [stored], guardado para un campo de tipo [type], como se ve al escribir con [symbols]. */
    fun shown(type: CustomFieldType?, stored: String, symbols: NumberSymbols): String =
        when (type) {
            CustomFieldType.NUMBER ->
                stored
                    .replace(NEUTRAL_MINUS, symbols.minusSign)
                    .replace(NEUTRAL_DECIMAL, symbols.decimalSeparator)

            CustomFieldType.TEXT, CustomFieldType.DATE, CustomFieldType.BOOLEAN, null -> stored
        }

    /**
     * [written], tal como lo dejó el campo de un tipo [type], en la forma en que se guarda. El
     * campo numérico ya dejó solo cifras, a lo sumo un separador decimal y quizá un signo al
     * principio, sin separadores de grupos: basta con pasar el separador y el signo a su forma
     * neutra.
     */
    fun stored(type: CustomFieldType?, written: String, symbols: NumberSymbols): String =
        when (type) {
            CustomFieldType.NUMBER ->
                buildString {
                    for (char in written.trim()) {
                        when (char) {
                            symbols.decimalSeparator -> append(NEUTRAL_DECIMAL)
                            symbols.minusSign, TYPOGRAPHIC_MINUS -> append(NEUTRAL_MINUS)
                            else -> append(char)
                        }
                    }
                }

            CustomFieldType.TEXT, CustomFieldType.DATE, CustomFieldType.BOOLEAN, null -> written
        }
}
