package io.github.yesidrobayo.shopledger.domain.model.export

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * El valor de una celda de la exportación, con la clase que decide cómo se escribe.
 *
 * La capa de datos lee cada fila con estas clases y nunca arma el texto de la celda: así las reglas
 * de la hoja de cálculo, como las comillas, la neutralización de fórmulas y los decimales, viven en
 * un solo lugar.
 */
sealed interface CsvCell {
    /**
     * Un texto: lo escrito por la persona, un nombre o una clave guardada. Siempre va entre comillas
     * y con la neutralización de fórmulas, también si salió de una copia restaurada o importada.
     */
    data class Text(val value: String) : CsvCell

    /** Un monto, en la unidad menor de la moneda del negocio. */
    data class Amount(val value: Money) : CsvCell

    /** Una cantidad en milésimas. */
    data class Quantity(val value: QuantityMilli) : CsvCell

    /** Un entero sin decimales, como el número de un registro o unos días. */
    data class Count(val value: Long) : CsvCell

    /** Un porcentaje, como la tasa de un impuesto. */
    data class Percent(val value: BasisPoints) : CsvCell

    /** Una fecha guardada como `AAAA-MM-DD`, como la fecha de negocio o el vencimiento. */
    data class Day(val value: String) : CsvCell

    /** La fecha y la hora de un instante, en la zona del negocio. */
    data class Moment(val epochMillis: Long) : CsvCell

    /** Solo la hora de un instante, en la zona del negocio. */
    data class TimeOfDay(val epochMillis: Long) : CsvCell

    /** Sin valor: la celda queda vacía. */
    data object Empty : CsvCell
}
