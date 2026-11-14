package io.github.yesidrobayo.shopledger.domain.rule.export

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat

/**
 * Las celdas de una hoja de cálculo exportada, a partir de las mismas celdas del CSV.
 *
 * - **Números.** Montos, cantidades, porcentajes y enteros van como celdas numéricas, escritas desde
 *   el entero guardado, nunca desde un `Double`, y siempre con punto decimal: es el formato del
 *   archivo, y la hoja los muestra con el separador de la región de quien la abre.
 * - **Texto.** Siempre como celda de texto, nunca como fórmula, y con la misma neutralización del
 *   CSV: un nombre que empieza con `=` sigue siendo texto también si alguien lo copia a otra hoja.
 *   Un código hecho de cifras no necesita el apóstrofo: una celda de texto conserva el cero inicial.
 * - **Fechas y horas.** Como texto ISO, `AAAA-MM-DD` y `HH:mm:ss` en la zona del negocio, iguales en
 *   cualquier región.
 */
class SheetCellWriter(currencyDecimals: Int, zone: BusinessTimeZone) {
    // El separador de columnas no importa en una hoja: solo se usan los números y las fechas.
    private val numbers =
        CsvFormat(CsvSeparator.SEMICOLON, DecimalSeparator.POINT, currencyDecimals)
    private val dates = CsvRowWriter(numbers, zone)

    /** La celda de la hoja para [cell]. */
    fun cell(cell: CsvCell): SheetValue = when (cell) {
        is CsvCell.Text -> SheetValue.Text(CsvText.neutralized(cell.value))
        is CsvCell.Code -> SheetValue.Text(cell.value)
        is CsvCell.Amount -> SheetValue.Number(numbers.moneyCell(cell.value))
        is CsvCell.Quantity -> SheetValue.Number(numbers.quantityCell(cell.value))
        is CsvCell.Count -> SheetValue.Number(cell.value.toString())
        is CsvCell.Percent -> SheetValue.Number(numbers.percentCell(cell.value))
        is CsvCell.Day -> SheetValue.Text(cell.value)
        is CsvCell.Moment -> SheetValue.Text(dates.cell(cell))
        is CsvCell.TimeOfDay -> SheetValue.Text(dates.cell(cell))
        CsvCell.Empty -> SheetValue.Empty
    }
}

/** El valor de una celda de una hoja de cálculo exportada. */
sealed interface SheetValue {
    /** Un texto, que la hoja muestra tal cual. */
    data class Text(val value: String) : SheetValue

    /** Un número decimal con punto, como `-12.50`, ya escrito desde un entero. */
    data class Number(val value: String) : SheetValue

    /** Sin valor: la celda no se escribe. */
    data object Empty : SheetValue
}
