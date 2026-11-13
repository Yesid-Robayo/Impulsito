package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.currency.MoneyDisplay
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** La segunda línea de una fila de producto: su stock, con icono y palabra cuando pide atención. */
sealed interface ProductStock {
    /** Stock normal: `23 und`. */
    data class Normal(val onHand: QuantityMilli, val unit: UnitLabel) : ProductStock

    /** Por agotarse: `Quedan 3 und`, con el icono de aviso. */
    data class Low(val onHand: QuantityMilli, val unit: UnitLabel) : ProductStock

    /** Agotado, con el icono de bloqueo. */
    data object Out : ProductStock

    /** Stock negativo: `Stock negativo: −2 und`, con el icono de error. */
    data class Negative(val onHand: QuantityMilli, val unit: UnitLabel) : ProductStock

    /** Un servicio no lleva stock. */
    data object Service : ProductStock

    /** Un producto con variantes: cuántas y el stock de todas. */
    data class Variants(val count: Int, val onHand: QuantityMilli, val unit: UnitLabel) :
        ProductStock
}

/** Lo que va a la derecha de la fila, según la pantalla. */
sealed interface ProductRowTrailing {
    /**
     * El catálogo: precio y margen. Sin precio, el margen es una raya; sin costo, el margen va
     * apagado; con margen negativo, la fila dice que se pierde en cada venta.
     */
    data class Catalog(val price: Money?, val margin: Margin, val hasCost: Boolean) :
        ProductRowTrailing

    /** El inventario: el stock y el mínimo. */
    data class Stock(val onHand: QuantityMilli, val minimum: QuantityMilli?, val unit: UnitLabel) :
        ProductRowTrailing

    /** Una línea de la venta: el total y `cantidad × precio`. */
    data class SaleLine(
        val total: Money,
        val quantity: QuantityMilli,
        val unitPrice: Money,
        val unit: UnitLabel,
    ) : ProductRowTrailing

    /** La vista previa de un cambio de precio: el nuevo y el anterior tachado. */
    data class PricePreview(val newPrice: Money, val oldPrice: Money) : ProductRowTrailing
}

/**
 * Los datos de una fila de producto, ya leídos: la fila no busca nada.
 *
 * [stock] nulo no muestra la línea del stock, como en una lista de precios, donde lo que importa es
 * de dónde sale el precio: eso va en [tag], una etiqueta propia de la pantalla.
 */
@Immutable
data class ProductRowData(
    val name: String,
    val stock: ProductStock?,
    val trailing: ProductRowTrailing,
    val archived: Boolean = false,
    val tag: String? = null,
)

/**
 * Una fila de libreta para un producto: el nombre y su stock a la izquierda, las cifras a la derecha
 * y un filete fino debajo. Sin avatares: una foto solo si el producto la tiene.
 *
 * Es un solo foco para TalkBack. Con letra grande, si las cifras no caben al lado, pasan debajo del
 * nombre. En modo discreto se oculta el margen; el precio y el stock siguen a la vista, porque son lo
 * que se atiende.
 *
 * @param photo la miniatura del producto, o `null` si no tiene foto: nunca hay imagen de relleno.
 * @param loading si los datos aún no llegan: esqueletos con la forma de la fila.
 */
@Composable
fun ProductRow(
    data: ProductRowData,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    photo: (@Composable () -> Unit)? = null,
    divider: Boolean = true,
    loading: Boolean = false,
) {
    val spacing = AppTheme.spacing
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRow)
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            if (loading) {
                ProductRowSkeleton()
                return@Row
            }
            photo?.invoke()
            LedgerLineLayout(
                gap = spacing.s12,
                modifier = Modifier.weight(1f),
                leading = { ProductRowLeading(data) },
                trailing = { alignment -> ProductRowTrailingColumn(data.trailing, alignment) },
            )
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = spacing.s16),
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun ProductRowLeading(data: ProductRowData) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2)) {
        Text(
            text = data.name,
            style = MaterialTheme.typography.titleSmall,
            color = if (data.archived) muted else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val tags =
            buildList {
                val trailing = data.trailing
                if (trailing is ProductRowTrailing.Catalog) {
                    if (trailing.price == null) add(R.string.notice_no_price)
                    if (!trailing.hasCost) add(R.string.status_no_cost)
                }
                if (data.archived) add(R.string.status_archived)
            }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            data.stock?.let { StockLine(it) }
            data.tag?.let { StatusTag(it) }
            tags.forEach { StatusTag(stringResource(it)) }
        }
    }
}

/** La línea del stock: texto apagado si está bien, y con icono y color si pide atención. */
@Composable
private fun StockLine(stock: ProductStock) {
    val app = AppTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (stock) {
        is ProductStock.Normal -> StockText(
            quantityText(stock.onHand, stock.unit),
            muted,
            icon = null,
        )

        is ProductStock.Low ->
            StockText(
                stringResource(R.string.stock_left, quantityText(stock.onHand, stock.unit)),
                app.warning,
                R.drawable.ic_warning,
                stringResource(R.string.term_low_stock),
            )

        ProductStock.Out ->
            StockText(stringResource(R.string.term_out_of_stock), app.loss, R.drawable.ic_block)

        is ProductStock.Negative ->
            StockText(
                stringResource(R.string.stock_negative_row, quantityText(stock.onHand, stock.unit)),
                app.loss,
                R.drawable.ic_error,
            )

        ProductStock.Service -> Unit

        is ProductStock.Variants ->
            StockText(
                pluralStringResource(
                    R.plurals.product_variants_stock,
                    stock.count,
                    stock.count,
                    quantityText(stock.onHand, stock.unit),
                ),
                muted,
                icon = null,
            )
    }
}

/**
 * Un texto de stock con su icono. Si el texto no nombra el estado, como `Quedan 3 und`, el icono lo
 * nombra para TalkBack: «por agotarse».
 */
@Composable
private fun StockText(
    text: String,
    color: Color,
    @DrawableRes icon: Int?,
    iconLabel: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = iconLabel,
                tint = color,
                modifier = Modifier.size(AppTheme.sizes.iconTag),
            )
        }
        Text(text = text, style = AppTheme.amounts.small, color = color)
    }
}

@Composable
private fun ProductRowTrailingColumn(
    trailing: ProductRowTrailing,
    alignment: Alignment.Horizontal,
) {
    Column(
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2),
    ) {
        when (trailing) {
            is ProductRowTrailing.Catalog -> {
                MoneyText(trailing.price, MoneySize.ROW)
                MarginLine(trailing)
            }

            is ProductRowTrailing.Stock -> {
                Text(
                    text = quantityText(trailing.onHand, trailing.unit),
                    style = AppTheme.amounts.row,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
                if (trailing.minimum != null) {
                    Text(
                        text = stringResource(
                            R.string.stock_minimum,
                            quantityText(trailing.minimum, trailing.unit),
                        ),
                        style = AppTheme.amounts.small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            is ProductRowTrailing.SaleLine -> {
                MoneyText(trailing.total, MoneySize.ROW)
                val unitPrice =
                    rememberMoneyDisplay(trailing.unitPrice, LocalAppFormats.current.money)
                Text(
                    text =
                        formatAnnotated(
                            stringResource(R.string.format_line_quantity_price),
                            quantityText(trailing.quantity, trailing.unit),
                            unitPrice.text,
                        ),
                    style = AppTheme.amounts.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            is ProductRowTrailing.PricePreview -> {
                MoneyText(trailing.newPrice, MoneySize.ROW)
                val old = rememberMoneyDisplay(trailing.oldPrice, LocalAppFormats.current.money)
                MoneyAmountText(
                    display = MoneyDisplay(old.text, old.description),
                    style = AppTheme.amounts.small.copy(
                        textDecoration = TextDecoration.LineThrough,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** El margen del catálogo, en sus cuatro casos. Es sensible: en modo discreto se oculta. */
@Composable
private fun MarginLine(catalog: ProductRowTrailing.Catalog) {
    val app = AppTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val margin = catalog.margin
    val negative = margin is Margin.Defined && margin.value < BasisPoints.ZERO
    if (negative && catalog.price != null && catalog.hasCost) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_trending_down),
                contentDescription = null,
                tint = app.loss,
                modifier = Modifier.size(AppTheme.sizes.iconTag),
            )
            Text(
                text = stringResource(R.string.notice_negative_margin),
                style = MaterialTheme.typography.bodySmall,
                color = app.loss,
            )
        }
        return
    }
    val value =
        when {
            catalog.price == null -> NO_VALUE_TEXT
            margin is Margin.Defined -> LocalAppFormats.current.percent.format(margin.value)
            else -> NO_VALUE_TEXT
        }
    val discreet = LocalDiscreetMode.current
    val marginLabel =
        stringResource(R.string.product_margin_value, stringResource(R.string.a11y_discreet_hidden))
    Text(
        text = stringResource(
            R.string.product_margin_value,
            if (discreet) DISCREET_TEXT else value,
        ),
        style = AppTheme.amounts.small,
        // Sin costo, el margen se calcula contra cero y sale inflado: va apagado.
        color = if (catalog.hasCost && !discreet) MaterialTheme.colorScheme.onSurface else muted,
        maxLines = 1,
        softWrap = false,
        modifier =
            if (discreet) {
                Modifier.semantics {
                    // El rótulo se conserva: «Margen Oculto», y nunca los puntos.
                    contentDescription = marginLabel
                    isSensitiveData = true
                }
            } else {
                Modifier
            },
    )
}

@Composable
private fun ProductRowSkeleton() {
    val spacing = AppTheme.spacing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
            SkeletonBlock(Modifier.width(SKELETON_NAME).height(spacing.s16))
            SkeletonBlock(Modifier.width(SKELETON_DETAIL).height(spacing.s12))
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            SkeletonBlock(Modifier.width(SKELETON_DETAIL).height(spacing.s16))
            SkeletonBlock(Modifier.width(SKELETON_SMALL).height(spacing.s12))
        }
    }
}

private val SKELETON_NAME = AppTheme.spacing.s64 * 2
private val SKELETON_DETAIL = AppTheme.spacing.s64
private val SKELETON_SMALL = AppTheme.spacing.s40

@ThemePreviews
@Composable
private fun ProductRowPreview() {
    val s = PreviewSamples
    val unit = s.UNIT
    val rows =
        listOf(
            ProductRowData(
                s.SHORT_NAME,
                ProductStock.Normal(s.STOCK, unit),
                ProductRowTrailing.Catalog(s.PRICE, Margin.Defined(BasisPoints(2_400L)), true),
            ),
            ProductRowData(
                s.LONG_NAME,
                ProductStock.Low(s.LOW_STOCK, unit),
                ProductRowTrailing.Catalog(
                    s.LONG_AMOUNT,
                    Margin.Defined(BasisPoints(3_210L)),
                    true,
                ),
            ),
            ProductRowData(
                s.SECOND_NAME,
                ProductStock.Out,
                ProductRowTrailing.Catalog(null, Margin.NoData, true),
            ),
            ProductRowData(
                s.THIRD_NAME,
                ProductStock.Negative(s.NEGATIVE_STOCK, unit),
                ProductRowTrailing.Catalog(s.PRICE, Margin.Defined(BasisPoints(-500L)), true),
            ),
            ProductRowData(
                s.SHORT_NAME,
                ProductStock.Variants(3, s.STOCK, unit),
                ProductRowTrailing.Catalog(s.PRICE, Margin.Defined(BasisPoints(9_900L)), false),
            ),
            ProductRowData(
                s.SECOND_NAME,
                ProductStock.Service,
                ProductRowTrailing.Catalog(s.SECOND_PRICE, Margin.NoData, true),
                archived = true,
            ),
            ProductRowData(
                s.SHORT_NAME,
                ProductStock.Low(s.LOW_STOCK, unit),
                ProductRowTrailing.Stock(s.LOW_STOCK, s.MINIMUM, unit),
            ),
            ProductRowData(
                s.SECOND_NAME,
                ProductStock.Normal(s.WEIGHT, s.KILOGRAM),
                ProductRowTrailing.SaleLine(s.SECOND_PRICE, s.WEIGHT, s.PRICE, s.KILOGRAM),
            ),
            ProductRowData(
                s.THIRD_NAME,
                ProductStock.Normal(s.TWO, s.BOX),
                ProductRowTrailing.PricePreview(s.PRICE, s.OLD_PRICE),
            ),
        )
    PreviewTheme {
        Column {
            rows.forEach { ProductRow(it, onClick = {}) }
            ProductRow(rows.first(), loading = true)
        }
    }
}

@ThemePreviews
@Composable
private fun ProductRowDiscreetPreview() {
    val s = PreviewSamples
    PreviewTheme(discreet = true) {
        ProductRow(
            ProductRowData(
                s.SHORT_NAME,
                ProductStock.Normal(s.STOCK, s.UNIT),
                ProductRowTrailing.Catalog(s.PRICE, Margin.Defined(BasisPoints(2_400L)), true),
            ),
        )
    }
}
