package io.github.yesidrobayo.shopledger.ui.components

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlinx.datetime.LocalDate

/**
 * Datos inventados para las vistas previas y las capturas de los componentes. Nunca se muestran a la
 * persona: son nombres de prueba, sin marcas, y montos que ejercitan lo difícil, como un nombre de 80
 * caracteres o un total de 13 cifras.
 */
internal object PreviewSamples {
    const val SHORT_NAME = "Arroz 1 kg"
    const val SECOND_NAME = "Aceite 900 ml"
    const val THIRD_NAME = "Jabón de avena"

    /** El nombre más largo que admite un producto: 80 caracteres. */
    const val LONG_NAME =
        "Café molido de altura en bolsa resellable con válvula, tostión media, 500 gramos"

    const val LEDGER_PAYMENT = "Abono"
    const val LEDGER_CREDIT_SALE = "Venta a crédito"
    const val LEDGER_DETAIL = "20 oct, Venta 1.180"
    const val METHOD_CASH = "Efectivo"
    const val METHOD_CARD = "Tarjeta"
    const val METHOD_TRANSFER = "Transferencia"
    const val METHOD_WALLET = "Billetera"
    const val CATEGORY_A = "Granos"
    const val CATEGORY_B = "Lácteos"
    const val CATEGORY_C = "Aseo"
    const val LOCATION_MAIN = "Local centro"
    const val LOCATION_SECOND = "Puesto de feria"
    const val PERIOD_LABEL = "octubre"
    const val TOOL_NAME = "Clientes"
    const val TOOL_DETAIL = "Datos, ventas a crédito y abonos"
    const val TOOL_ATTENTION = "2 vencidas"
    const val SETTING_TITLE = "Avisar cuando un producto se esté acabando"
    const val SETTING_VALUE = "Activado"
    const val NOTICE_TEXT = "Sin costo: el margen puede estar inflado"
    const val DIALOG_TITLE = "¿Anular esta venta?"
    const val DIALOG_BODY = "El stock vuelve y la venta queda en el historial como anulada."
    const val DIALOG_ACTION = "Anular venta"
    const val DIALOG_ACK = "Entiendo que este teléfono tiene menos registros"
    const val SEARCH_QUERY = "acite"

    /** Un código inventado: el prefijo 29 es de uso interno y no apunta a ningún país. */
    const val BARCODE = "2900000123458"

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

    val UNIT = UnitLabel(symbol = "und", allowsFraction = false)
    val KILOGRAM = UnitLabel(symbol = "kg", allowsFraction = true)
    val BOX = UnitLabel(symbol = "caja", allowsFraction = false, custom = true)

    val TODAY = BusinessDate(LocalDate(2026, 11, 5))
}
