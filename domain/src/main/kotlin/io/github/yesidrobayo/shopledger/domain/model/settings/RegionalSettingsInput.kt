package io.github.yesidrobayo.shopledger.domain.model.settings

import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlinx.datetime.DayOfWeek

/**
 * Lo que la persona confirma en la pantalla de configuración regional: moneda, decimales, símbolo,
 * redondeo de efectivo, formato, zona, semana y separador de CSV.
 *
 * Llega tal como quedó en el formulario, sin revisar: la regla que lo guarda decide si vale. No
 * trae la moneda de referencia, que se elige en su propia pantalla, ni la fecha del bloqueo, que
 * solo pone el primer registro con dinero.
 *
 * - [currencySymbol] es lo que escribió la persona. Vacío o solo espacios es sin símbolo propio:
 *   se usa el de la moneda en el formato regional.
 * - [cashRounding] es el paso del redondeo de efectivo, en unidad menor. Cero es sin redondeo.
 * - [formatLocaleTag] es la etiqueta BCP 47 del formato regional, no el idioma de la interfaz.
 * - [businessTimeZoneId] es el identificador IANA de la zona del negocio.
 */
data class RegionalSettingsInput(
    val currencyCode: CurrencyCode,
    val currencyDecimals: Int,
    val currencySymbol: String?,
    val cashRounding: Money,
    val formatLocaleTag: String,
    val businessTimeZoneId: String,
    val weekStartDay: DayOfWeek,
    val csvSeparator: CsvSeparator,
)
