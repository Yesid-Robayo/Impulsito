package io.github.yesidrobayo.shopledger.domain.model.settings

import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlinx.datetime.DayOfWeek

/**
 * La configuración regional del negocio: moneda, formato, zona y semana. Son datos del negocio, no
 * del teléfono: viajan en la copia, y la persona los confirma en el primer uso. Nada de esto entra
 * en la cabecera ni en los metadatos de una copia.
 *
 * - [cashRounding] es el paso al que se redondea un pago en efectivo, en unidad menor. Cero es sin
 *   redondeo.
 * - [formatLocaleTag] es la etiqueta BCP 47 del formato regional, que decide separadores y fechas.
 *   No es el idioma de la interfaz.
 * - [businessTimeZoneId] es el identificador IANA de la zona del negocio. Se guarda tal cual: una
 *   copia de un teléfono más nuevo puede traer una zona que este no conoce, y quien la usa la lee
 *   con `BusinessTimeZone.of`, que lo dice.
 * - [weekStartDay] es el primer día de las semanas de los reportes.
 * - [referenceCurrency] es la moneda de referencia, si la persona eligió una.
 */
data class RegionalSettings(
    val currency: BusinessCurrency,
    val cashRounding: Money,
    val formatLocaleTag: String,
    val businessTimeZoneId: String,
    val weekStartDay: DayOfWeek,
    val csvSeparator: CsvSeparator,
    val referenceCurrency: ReferenceCurrency?,
)
