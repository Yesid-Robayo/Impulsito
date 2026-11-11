package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.PaymentMethodOption
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.quantityText

/**
 * Cómo se escribe la unidad junto a una cantidad: su símbolo, o su nombre si no tiene. Una unidad
 * que creó la persona no tiene plural conocido. Sin unidad, la de conteo.
 */
@Composable
internal fun unitLabelOf(unit: MeasureUnit?): UnitLabel {
    val resources = LocalResources.current
    if (unit == null) return UnitLabel(stringResource(R.string.seed_units_unit), false)
    val symbol =
        SeedText.unitSymbol(resources, unit.systemKey, unit.symbol)
            ?: SeedText.displayName(resources, SeedTable.UNITS, unit.systemKey, unit.name)
    return UnitLabel(symbol, unit.allowsFraction, custom = unit.systemKey == null)
}

/** Lo que dice la tarjeta del stock, como `23 und`, o `null` si el producto no lleva stock. */
@Composable
internal fun stockText(stock: TileStock, unit: UnitLabel): String? = when (stock) {
    is TileStock.Normal -> quantityText(stock.onHand, unit)

    is TileStock.Low -> stringResource(R.string.stock_left, quantityText(stock.onHand, unit))

    TileStock.Out -> stringResource(R.string.term_out_of_stock)

    is TileStock.Negative ->
        stringResource(R.string.stock_negative_row, quantityText(stock.onHand, unit))

    TileStock.None -> null
}

/** El nombre visible de un método: el suyo, o el de su clave inicial en el idioma de la interfaz. */
@Composable
internal fun methodName(method: SaleMethod): String = SeedText.displayName(
    LocalResources.current,
    SeedTable.PAYMENT_METHODS,
    method.systemKey,
    method.name,
)

/** Los métodos como los muestran los chips, con su nombre ya escrito. */
@Composable
internal fun methodOptions(methods: List<SaleMethod>): List<PaymentMethodOption> =
    methods.map { PaymentMethodOption(it.id, methodName(it), it.hasQr) }

/**
 * Si la letra del sistema está al 150 % o más. Se mide cuánto ocupa de verdad un texto de 12 sp:
 * desde Android 14 la letra grande crece menos que la escala elegida, pero un texto así de chico
 * todavía crece entero. La venta pasa entonces a su modo compacto, con el pago en una fila y la
 * búsqueda arriba.
 */
@Composable
internal fun isLargeText(): Boolean {
    val measured = with(LocalDensity.current) { REFERENCE_TEXT.sp.toDp() }
    return measured >= (REFERENCE_TEXT * LARGE_TEXT_FACTOR).dp
}

private const val REFERENCE_TEXT = 12

// Un poco menos de 1,5 para que el redondeo de la medida no lo deje fuera justo en 150 %.
private const val LARGE_TEXT_FACTOR = 1.45f
