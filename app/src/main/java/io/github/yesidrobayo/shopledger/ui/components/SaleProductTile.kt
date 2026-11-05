package io.github.yesidrobayo.shopledger.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.ui.theme.AppMotion
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.coroutines.launch

/**
 * La línea de un producto ya agregado a la venta, para su selector de cantidad.
 *
 * @param oversellBy cuánto quedaría en negativo si se vende esta cantidad, o `null`: se avisa bajo
 *   el selector, sin diálogo.
 */
data class SaleTileLine(
    val quantity: QuantityMilli,
    val onIncrement: () -> Unit,
    val onDecrement: () -> Unit,
    val removesLine: Boolean,
    val canIncrement: Boolean = true,
    val onQuantityClick: (() -> Unit)? = null,
    val oversellBy: QuantityMilli? = null,
)

/**
 * La tarjeta de un producto en la venta: nombre, precio y stock. Tocarla agrega uno, con un rebote
 * breve; agregada lleva un borde de 2 dp y el selector de cantidad en lugar del stock.
 *
 * Necesita el ancho de su precio y de su selector: con letra grande, la cuadrícula de la venta pasa a
 * una columna, como pide la guía de accesibilidad.
 *
 * Qué hace el toque lo decide quien la usa: con fracciones o variantes abre una hoja, y sin precio abre
 * la línea con el precio enfocado. Mide 116 dp como mínimo y crece con la letra; la foto, si la hay,
 * va junto al nombre sin cambiar el alto.
 *
 * @param stockText el stock ya escrito, como `23 und`, o `null` en un servicio.
 * @param line la línea en la venta, o `null` si el producto no está agregado.
 */
@Composable
fun SaleProductTile(
    name: String,
    price: Money?,
    stockText: String?,
    unit: UnitLabel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    line: SaleTileLine? = null,
    photo: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val sizes = AppTheme.sizes
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    val tap = {
        onClick()
        if (animate) {
            scope.launch {
                scale.animateTo(AppMotion.TACTILE_SCALE, AppMotion.TactileSpring)
                scale.animateTo(1f, AppMotion.TactileSpring)
            }
        }
    }
    val added = line != null
    val addLabel = stringResource(R.string.action_add)
    val inSale = line?.let {
        stringResource(R.string.a11y_quantity_in_sale, quantityText(it.quantity, unit))
    }
    Surface(
        onClick = { tap() },
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }.heightIn(min = sizes.saleTile)
                .semantics {
                    onClick(label = addLabel) {
                        tap()
                        true
                    }
                    if (inSale != null) {
                        selected = true
                        stateDescription = inSale
                    }
                },
        shape = MaterialTheme.shapes.large,
        color = AppTheme.colors.cardSurface,
        contentColor = scheme.onSurface,
        border =
            if (added) {
                BorderStroke(sizes.focusBorder, scheme.primary)
            } else {
                BorderStroke(sizes.hairline, AppTheme.colors.cardBorder)
            },
    ) {
        Column(
            modifier = Modifier.padding(AppTheme.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                photo?.invoke()
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (price == null) {
                StatusTag(stringResource(R.string.notice_no_price))
            } else {
                MoneyText(price, MoneySize.ROW)
            }
            if (line == null) {
                if (stockText != null) {
                    Text(
                        stockText,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                QuantityStepper(
                    quantity = line.quantity,
                    unit = unit,
                    productName = name,
                    onDecrement = line.onDecrement,
                    onIncrement = line.onIncrement,
                    removesLine = line.removesLine,
                    canIncrement = line.canIncrement,
                    onValueClick = line.onQuantityClick,
                )
                if (line.oversellBy != null) OversellNotice(line.oversellBy, unit)
            }
        }
    }
}

/** `Quedarían −2 und`, en aviso, con su icono: vender sin stock no se impide, solo se avisa. */
@Composable
private fun OversellNotice(oversellBy: QuantityMilli, unit: UnitLabel) {
    val warning = AppTheme.colors.warning
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = warning,
            modifier = Modifier.size(AppTheme.sizes.iconTag),
        )
        Text(
            text = stringResource(R.string.notice_oversell, quantityText(oversellBy, unit)),
            style = MaterialTheme.typography.bodySmall,
            color = warning,
        )
    }
}

@ThemePreviews
@Composable
private fun SaleProductTilePreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
                SaleProductTile(s.SHORT_NAME, s.PRICE, quantityText(s.STOCK, s.UNIT), s.UNIT, {
                }, Modifier.weight(1f))
                SaleProductTile(
                    s.SECOND_NAME,
                    s.SECOND_PRICE,
                    null,
                    s.UNIT,
                    {},
                    Modifier.weight(1f),
                    line = SaleTileLine(s.TWO, {
                    }, {}, removesLine = false, oversellBy = s.NEGATIVE_STOCK),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
                SaleProductTile(s.LONG_NAME, null, quantityText(s.LOW_STOCK, s.UNIT), s.UNIT, {
                }, Modifier.weight(1f))
                SaleProductTile(s.THIRD_NAME, s.LONG_AMOUNT, null, s.UNIT, {
                }, Modifier.width(AppTheme.sizes.saleTile * 2))
            }
            SaleProductTile(
                s.SECOND_NAME,
                s.PRICE,
                null,
                s.KILOGRAM,
                {},
                Modifier.fillMaxWidth(),
                line = SaleTileLine(s.WEIGHT, {}, {}, removesLine = false, onQuantityClick = {}),
            )
        }
    }
}
