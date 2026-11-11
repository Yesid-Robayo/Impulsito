package io.github.yesidrobayo.shopledger.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Inicio: cómo va el día y qué requiere atención, en un vistazo.
 *
 * - La barra lleva la fecha de hoy en la zona del negocio y el acceso a los ajustes.
 * - El héroe dice lo vendido hoy y en cuántas ventas, la ganancia en ventas en el acento y el margen.
 *   Sin ventas todavía, dice cero y espera la primera. En modo discreto, las cifras de dinero y el
 *   margen se ocultan, y el número de ventas se ve.
 * - Sin productos, una tarjeta lleva a crear el primero.
 * - Los atajos llevan a `Negocio`, la única entrada a sus herramientas en un teléfono.
 * - Las últimas cinco ventas, con `Ver todas` hacia el historial.
 * - En una ventana ancha, el héroe y los atajos a la izquierda, y las ventas a la derecha.
 *
 * Es pura: recibe el estado y avisa cada acción. Aquí se suman después el estado del respaldo bajo la
 * barra, `Por agotarse` antes de las ventas, y la ganancia final de la semana y del mes bajo el héroe.
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= EXPANDED_WIDTH
            Column(Modifier.fillMaxSize()) {
                val today = (uiState as? HomeUiState.Content)?.today
                AppTopBar(
                    title = today?.let { LocalAppFormats.current.date.weekdayDayMonth(it) }
                        ?: stringResource(R.string.nav_home),
                    navigation = TopBarNavigation.NONE,
                    onNavigate = {},
                    navigationLabel = null,
                    actions = {
                        TopBarAction(
                            R.drawable.ic_settings,
                            stringResource(R.string.a11y_settings),
                        ) {
                            onAction(HomeAction.OpenSettings)
                        }
                    },
                )
                val bottom = bottomSpace()
                when (uiState) {
                    HomeUiState.Loading ->
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            DelayedSkeleton { HomeSkeleton() }
                        }

                    HomeUiState.ReadError ->
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = bottom),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            EmptyState(
                                icon = R.drawable.ic_error,
                                title = stringResource(R.string.state_error_read),
                            ) {
                                PrimaryActionButton(
                                    text = stringResource(R.string.action_retry),
                                    onClick = { onAction(HomeAction.Retry) },
                                    variant = ActionVariant.TONAL,
                                )
                            }
                        }

                    is HomeUiState.Content ->
                        if (expanded) {
                            TwoPanes(uiState, onAction, bottom, Modifier.weight(1f))
                        } else {
                            OnePane(uiState, onAction, bottom, Modifier.weight(1f))
                        }
                }
            }
        }
    }
}

/**
 * Lo que el contenido deja libre al final: la barra del sistema, la barra inferior y lo que sobresale
 * `Vender`, para que la última venta no quede debajo.
 */
@Composable
private fun bottomSpace(): Dp {
    val sizes = AppTheme.sizes
    val system = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return system + sizes.bottomBar + sizes.sellButtonOverhang + AppTheme.spacing.s16
}

/** Una columna de hasta 600 dp, centrada: el teléfono y la ventana mediana. */
@Composable
private fun OnePane(
    content: HomeUiState.Content,
    onAction: (HomeAction) -> Unit,
    bottom: Dp,
    modifier: Modifier,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottom),
        ) {
            item(key = HERO) {
                Column(
                    Modifier.padding(horizontal = AppTheme.spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
                ) {
                    Overview(content, onAction)
                }
            }
            recentSales(content, onAction)
        }
    }
}

/** En una ventana ancha: el día y los atajos a la izquierda, las ventas a la derecha. */
@Composable
private fun TwoPanes(
    content: HomeUiState.Content,
    onAction: (HomeAction) -> Unit,
    bottom: Dp,
    modifier: Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = AppTheme.spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = AppTheme.spacing.s8, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
        ) {
            Overview(content, onAction)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = bottom),
        ) {
            recentSales(content, onAction)
        }
    }
}

/** El héroe, la tarjeta del primer uso y los atajos, en ese orden. */
@Composable
private fun ColumnScope.Overview(content: HomeUiState.Content, onAction: (HomeAction) -> Unit) {
    TodayHeroBlock(content.hero, Modifier.padding(top = AppTheme.spacing.s8))
    if (content.firstUse) {
        AppCard {
            EmptyStateInline(
                title = stringResource(R.string.empty_home_title),
                body = stringResource(R.string.empty_home_body),
                action = {
                    PrimaryActionButton(
                        text = stringResource(R.string.empty_home_action),
                        onClick = { onAction(HomeAction.CreateFirstProduct) },
                        variant = ActionVariant.TONAL,
                        modifier = Modifier.padding(top = AppTheme.spacing.s8),
                    )
                },
            )
        }
    }
    Shortcuts(onAction)
}

/**
 * Los atajos tonales, que reparten el ancho y bajan a otra línea si la letra no cabe. Hoy solo
 * `Negocio`: `Compra` y `Gasto` se suman aquí cuando existan sus pantallas.
 */
@Composable
private fun Shortcuts(onAction: (HomeAction) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        PrimaryActionButton(
            text = stringResource(R.string.hub_entry_action),
            onClick = { onAction(HomeAction.OpenBusiness) },
            variant = ActionVariant.TONAL,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * El héroe del día: lo vendido hoy en grande, cuántas ventas, y debajo la ganancia en ventas en el
 * acento con el margen a la derecha. Sin ventas, cero y la frase que espera la primera.
 *
 * Una ganancia negativa lleva su signo y la palabra `Pérdida`, en el acento: sobre el héroe no se
 * usan los colores de ganancia ni de pérdida.
 */
@Composable
private fun TodayHeroBlock(hero: TodayHero, modifier: Modifier = Modifier) {
    val app = AppTheme.colors
    val spacing = AppTheme.spacing
    val summary = (hero as? TodayHero.Sales)?.summary
    Column(
        modifier
            .fillMaxWidth()
            .background(app.heroContainer, MaterialTheme.shapes.extraLarge)
            .padding(spacing.s20),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        Column(
            Modifier.semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_sales_today),
                    style = MaterialTheme.typography.labelLarge,
                    color = app.onHeroVariant,
                    modifier = Modifier.weight(1f),
                )
                if (summary != null) {
                    val count = summary.salesCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    Text(
                        pluralStringResource(R.plurals.stats_sales_count, count, count),
                        style = MaterialTheme.typography.labelLarge,
                        color = app.onHeroVariant,
                    )
                }
            }
            MoneyText(
                amount = when (hero) {
                    TodayHero.Unavailable -> null
                    TodayHero.NoSalesYet -> Money.ZERO
                    is TodayHero.Sales -> hero.summary.netSales
                },
                size = MoneySize.HERO,
                tone = MoneyTone.ON_HERO,
                sensitive = true,
            )
        }
        when (hero) {
            TodayHero.NoSalesYet ->
                Text(
                    stringResource(R.string.empty_home_day_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = app.onHeroVariant,
                )

            is TodayHero.Sales -> ProfitLine(hero.summary)

            TodayHero.Unavailable -> Unit
        }
    }
}

/** La ganancia en ventas a la izquierda, en el acento, y el margen a la derecha, tras un filete. */
@Composable
private fun ProfitLine(summary: SalesSummary) {
    val app = AppTheme.colors
    val spacing = AppTheme.spacing
    HorizontalDivider(thickness = AppTheme.sizes.hairline, color = app.heroDivider)
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s16)) {
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            Text(
                stringResource(R.string.term_sales_profit),
                style = MaterialTheme.typography.labelMedium,
                color = app.onHeroVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.s8),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                MoneyText(
                    summary.grossProfit,
                    MoneySize.TILE,
                    tone = MoneyTone.HERO_ACCENT,
                    sensitive = true,
                )
                if (summary.grossProfit < Money.ZERO) {
                    Text(
                        stringResource(R.string.status_loss),
                        style = MaterialTheme.typography.labelMedium,
                        color = app.heroAccent,
                    )
                }
            }
        }
        Column(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            Text(
                stringResource(R.string.term_margin),
                style = MaterialTheme.typography.labelMedium,
                color = app.onHeroVariant,
            )
            val margin = summary.grossMargin
            DiscreetValue(style = AppTheme.amounts.tile, color = app.onHeroVariant) {
                Text(
                    text = when (margin) {
                        is Margin.Defined -> LocalAppFormats.current.percent.format(margin.value)
                        Margin.NoData, Margin.OutOfRange -> NO_VALUE_TEXT
                    },
                    style = AppTheme.amounts.tile,
                    color = app.onHero,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** `Últimas ventas` con su acceso al historial, y sus filas. Sin ventas nunca, no aparece. */
private fun LazyListScope.recentSales(
    content: HomeUiState.Content,
    onAction: (HomeAction) -> Unit,
) {
    if (content.recentSales.isEmpty()) return
    item(key = RECENT_HEADER) {
        SectionHeader(
            title = stringResource(R.string.home_recent_sales),
            actionLabel = stringResource(R.string.action_view_all_feminine),
            onAction = { onAction(HomeAction.OpenAllSales) },
        )
    }
    items(content.recentSales, key = { it.id }) { row ->
        RecentSaleRow(row, content.today, content.zone) { onAction(HomeAction.OpenSale(row.id)) }
    }
}

/**
 * Una venta reciente: la hora si es de hoy, o la fecha si no, el primer producto y cuántos más, y el
 * total a la derecha. Anulada, va apagada, tachada y con su etiqueta, como en el historial.
 */
@Composable
private fun RecentSaleRow(
    row: SaleHistoryRow,
    today: BusinessDate?,
    zone: BusinessTimeZone?,
    onClick: () -> Unit,
) {
    val spacing = AppTheme.spacing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val formats = LocalAppFormats.current
    val whenText =
        when {
            zone != null && row.businessDate == today -> formats.date.time(row.occurredAt, zone)
            today != null -> formats.date.shortDate(row.businessDate, today)
            else -> formats.date.dayMonth(row.businessDate)
        }
    val name: @Composable RowScope.() -> Unit = {
        Text(
            row.firstProductName.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            color = if (row.voided) muted else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (row.lineCount > 1) {
            Text(
                stringResource(R.string.sale_more_items, row.lineCount - 1),
                style = MaterialTheme.typography.titleSmall,
                color = muted,
            )
        }
    }
    val total: @Composable () -> Unit = {
        MoneyText(
            row.total,
            MoneySize.ROW,
            tone = if (row.voided) MoneyTone.MUTED else MoneyTone.DEFAULT,
            voided = row.voided,
        )
    }
    // Con letra grande, un total largo dejaría al nombre sin ancho: el total baja a su propia línea,
    // a la derecha, y el nombre conserva la fila.
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    Column(Modifier.clickable(onClick = onClick)) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRowSingle)
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                Text(whenText, style = MaterialTheme.typography.bodyMedium, color = muted)
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(spacing.s4),
                    content = name,
                )
                if (!stacked) total()
            }
            if (stacked) total()
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** El esqueleto con la forma de Inicio: el héroe, los atajos y tres filas. */
@Composable
private fun HomeSkeleton() {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = spacing.s64 * 2))
        SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.primaryButton))
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle))
        }
    }
}

// Desde 840 dp, el día a la izquierda y las ventas a la derecha.
private val EXPANDED_WIDTH = 840.dp

private const val SKELETON_ROWS = 3

// Desde esta escala de letra, el total de cada venta va en su propia línea.
private const val STACKED_FONT_SCALE = 1.5f
private const val HERO = "hero"
private const val RECENT_HEADER = "recent"
