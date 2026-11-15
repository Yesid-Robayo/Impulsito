package io.github.yesidrobayo.shopledger.screenshot

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import kotlinx.datetime.LocalDate

/**
 * Datos inventados de las capturas: nombres de prueba sin marcas, y montos que ejercitan lo
 * difícil, como un nombre de 80 caracteres, un total de trece cifras y un negativo. Los nombres
 * son datos del negocio, así que no cambian con el idioma; los textos de la app, sí.
 */
object CaptureSamples {
    const val SHORT_NAME = "Arroz 1 kg"
    const val SECOND_NAME = "Aceite 900 ml"
    const val THIRD_NAME = "Jabón de avena"

    /** El nombre más largo que admite un producto: 80 caracteres. */
    const val LONG_NAME =
        "Café molido de altura en bolsa resellable con válvula, tostión media, 500 gramos"

    const val LEDGER_PAYMENT = "Abono"
    const val LEDGER_DETAIL = "2026-10-20"
    const val METHOD_WALLET = "Billetera"
    const val SEARCH_QUERY = "acite"

    /** El período con que se compara una cifra, escrito como dato. */
    const val COMPARED_PERIOD = "2026-10"

    val PRICE = Money(4_200L)
    val SECOND_PRICE = Money(12_500L)
    val OLD_PRICE = Money(3_900L)

    /** Trece cifras: el tope de un total de documento. */
    val LONG_AMOUNT = Money(1_234_567_890_123L)
    val NEGATIVE_AMOUNT = Money(-1_200L)
    val MONTH_SALES = Money(3_120_800L)

    val STOCK = QuantityMilli(23_000L)
    val LOW_STOCK = QuantityMilli(3_000L)
    val NEGATIVE_STOCK = QuantityMilli(-2_000L)
    val MINIMUM = QuantityMilli(5_000L)
    val WEIGHT = QuantityMilli(1_250L)
    val TWO = QuantityMilli(2_000L)
    val ONE = QuantityMilli(1_000L)

    val UNIT_LABEL = UnitLabel(symbol = "und", allowsFraction = false)
    val KILOGRAM_LABEL = UnitLabel(symbol = "kg", allowsFraction = true)

    /** La unidad que se cuenta, como la guarda la base: su símbolo sale de los recursos. */
    val UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)

    val TODAY = BusinessDate(LocalDate(2026, 11, 2))
}
