package io.github.yesidrobayo.shopledger.format

import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import java.util.Locale

/** El código de ISO 4217 reservado para pruebas: no es la moneda de ningún lugar. */
val TEST_CURRENCY: CurrencyCode = requireNotNull(CurrencyCode.of("XTS"))

/**
 * Un formateador de dinero para las pruebas de cifras: la moneda de pruebas, sin símbolo propio y
 * con la interfaz en inglés. Las cifras no dependen de ninguna de las tres cosas.
 */
fun moneyFormatter(formatLocale: FormatLocale, currencyDecimals: Int): MoneyFormatter =
    MoneyFormatter(
        formatLocale = formatLocale,
        currencyCode = TEST_CURRENCY,
        currencyDecimals = currencyDecimals,
        currencySymbol = null,
        uiLocale = Locale.ENGLISH,
    )
