package io.github.yesidrobayo.shopledger.data.seed

import io.github.yesidrobayo.shopledger.data.local.InitialRegion
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber

/**
 * La configuración regional del negocio inventado: una fila de la matriz de configuraciones, o
 * cualquier otra que pida una prueba. El generador no tiene moneda, zona, semana ni idioma por
 * defecto, y nunca los toma del teléfono: todo llega aquí.
 *
 * - [currencyCode] y [currencyDecimals] son la moneda del negocio, que el generador confirma y
 *   bloquea antes del primer monto. Sin moneda confirmada no hay negocio que inventar.
 * - [formatLocaleTag], [businessTimeZoneId], [weekStartDay] y [csvSeparator] son los ajustes con
 *   que nace la base. La zona decide la fecha de negocio de cada fila.
 * - [language] elige las palabras de los nombres inventados. No es el idioma de la interfaz ni el
 *   del teléfono: la misma semilla con otro idioma cambia solo los textos.
 */
data class SeedConfig(
    val currencyCode: CurrencyCode,
    val currencyDecimals: Int,
    val formatLocaleTag: String,
    val businessTimeZoneId: String,
    val weekStartDay: DayOfWeek,
    val csvSeparator: CsvSeparator,
    val language: SeedLanguage,
) {
    init {
        require(currencyCode.isConfirmed) { "El negocio inventado necesita una moneda confirmada" }
        require(currencyDecimals in 0..MAX_DECIMALS) { "Los decimales van de 0 a $MAX_DECIMALS" }
        require(formatLocaleTag.isNotEmpty()) { "Falta el formato regional" }
        requireNotNull(BusinessTimeZone.of(businessTimeZoneId)) { "La zona no existe" }
    }

    /** La zona del negocio, ya validada al crear la configuración. */
    internal val zone: BusinessTimeZone
        get() = requireNotNull(BusinessTimeZone.of(businessTimeZoneId)) { "La zona no existe" }

    /** Los ajustes con que nace la base, en vez de los del teléfono. */
    internal val initialRegion: InitialRegion
        get() = InitialRegion(
            formatLocale = formatLocaleTag,
            businessTimeZone = businessTimeZoneId,
            weekStartDay = weekStartDay.isoDayNumber,
            csvSeparator = csvSeparator,
        )

    private companion object {
        const val MAX_DECIMALS = 4
    }
}
