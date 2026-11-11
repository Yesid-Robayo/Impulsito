package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.localization.SeedText

/**
 * El nombre de una unidad: el que escribió la persona o, en una inicial sin nombre propio, el
 * traducido al idioma de la interfaz.
 */
@Composable
fun unitName(unit: MeasureUnit): String =
    SeedText.displayName(LocalResources.current, SeedTable.UNITS, unit.systemKey, unit.name)

/** El símbolo de una unidad, propio o traducido, o `null` si no tiene. */
@Composable
fun unitSymbol(unit: MeasureUnit): String? =
    SeedText.unitSymbol(LocalResources.current, unit.systemKey, unit.symbol)

/**
 * Cómo acompaña una unidad a una cantidad: su símbolo o, sin símbolo, su nombre. Una unidad de la
 * persona no tiene plural conocido, así que su cantidad va como `3 × caja`.
 */
@Composable
fun unitLabel(unit: MeasureUnit): UnitLabel = UnitLabel(
    symbol = unitSymbol(unit) ?: unitName(unit),
    allowsFraction = unit.allowsFraction,
    custom = unit.systemKey == null,
)

/**
 * La frase de una conversión, `1 caja = 24 und`: una unidad entera de [unit] trae [factor] de
 * [base], la unidad base del producto. Un factor con decimales los muestra aunque la base no los
 * admita: redondearlo escondería un dato.
 */
@Composable
fun conversionPhrase(unit: MeasureUnit, base: MeasureUnit, factor: QuantityMilli): String {
    val baseLabel = unitLabel(base)
    val shown = baseLabel.copy(allowsFraction = baseLabel.allowsFraction || !factor.isWholeUnits)
    return stringResource(
        R.string.unit_conversion_row,
        unitLabel(unit).symbol,
        quantityText(factor, shown),
    )
}

/** El nombre de un tipo de unidad, como encabezado de su grupo. */
@StringRes
fun unitKindLabel(kind: UnitKind): Int = when (kind) {
    UnitKind.COUNT -> R.string.unit_kind_count
    UnitKind.MASS -> R.string.unit_kind_mass
    UnitKind.VOLUME -> R.string.unit_kind_volume
    UnitKind.LENGTH -> R.string.unit_kind_length
    UnitKind.OTHER -> R.string.unit_kind_other
}
