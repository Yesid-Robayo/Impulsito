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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.domain.rule.backup.NoDestinationNotice
import io.github.yesidrobayo.shopledger.domain.usecase.home.HomeProfit
import io.github.yesidrobayo.shopledger.domain.usecase.report.ComparedPeriod
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.backup.BackupAttentionBanner
import io.github.yesidrobayo.shopledger.ui.backup.BackupBannerAction
import io.github.yesidrobayo.shopledger.ui.backup.BackupWording
import io.github.yesidrobayo.shopledger.ui.backup.backupBannerLook
import io.github.yesidrobayo.shopledger.ui.backup.backupChipLook
import io.github.yesidrobayo.shopledger.ui.backup.needsBanner
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.BackupNoticeLine
import io.github.yesidrobayo.shopledger.ui.components.BackupStatusChip
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetModeButton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LocalDemoMode
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatChange
import io.github.yesidrobayo.shopledger.ui.components.StatTile
import io.github.yesidrobayo.shopledger.ui.components.StatTileVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.bottomBarClearance
import io.github.yesidrobayo.shopledger.ui.components.periodLabel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.stock.inventoryRowData
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Inicio: cómo va el día y qué requiere atención, en un vistazo.
 *
 * - La barra lleva la fecha de hoy en la zona del negocio y el acceso a los ajustes.
 * - El héroe dice lo vendido hoy y en cuántas ventas, la ganancia en ventas en el acento y el
 * margen.   Sin ventas todavía, dice cero y espera la primera. En modo discreto, las cifras de
 * dinero y el   margen se ocultan, y el número de ventas se ve.
 * - Sin productos, una tarjeta lleva a crear el primero.
 * - Los atajos llevan a `Negocio`, la única entrada a sus herramientas en un teléfono.
 * - Las últimas cinco ventas, con `Ver todas` hacia el historial.
 * - En una ventana ancha, el héroe y los atajos a la izquierda, y las ventas a la derecha.
 *
 * - El estado del respaldo: el chip bajo la barra, y el banner y los avisos sobre el héroe. Sin
 *   destino, el aviso lleva `Entendido` y, aceptado, se reduce a una línea, según
 *   [noDestinationNotice].
 *
 * - Bajo el héroe, la ganancia final de la semana y del mes, después de gastos: la misma cifra que
 *   las estadísticas. Es ganancia, así que el modo discreto la oculta.
 *
 * - Bajo los atajos, la meta de ventas del mes, solo si el mes tiene una, según [goal].
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    backup: BackupWording? = null,
    lowStock: HomeLowStock? = null,
    profit: HomeProfit? = null,
    noDestinationNotice: NoDestinationNotice? = NoDestinationNotice.FULL,
    photosPending: Int = 0,
    goal: GoalAdvance? = null,
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
                        ScreenHelpLink(HelpTopic.HOME)
                        DiscreetModeButton()
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
                            TwoPanes(
                                uiState,
                                HomeBackup(backup, noDestinationNotice, photosPending),
                                lowStock,
                                profit,
                                onAction,
                                bottom,
                                Modifier.weight(1f),
                                goal = goal,
                            )
                        } else {
                            OnePane(
                                uiState,
                                HomeBackup(backup, noDestinationNotice, photosPending),
                                lowStock,
                                profit,
                                onAction,
                                bottom,
                                Modifier.weight(1f),
                                goal = goal,
                            )
                        }
                }
            }
        }
    }
}

/**
 * Lo que el contenido deja libre al final: la barra del sistema, la barra inferior y lo que
 * sobresale `Vender`, para que la última venta no quede debajo.
 */
@Composable
private fun bottomSpace(): Dp {
    val system = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return system + bottomBarClearance() + AppTheme.spacing.s16
}

/** Una columna de hasta 600 dp, centrada: el teléfono y la ventana mediana. */
@Composable
private fun OnePane(
    content: HomeUiState.Content,
    backup: HomeBackup,
    lowStock: HomeLowStock?,
    profit: HomeProfit?,
    onAction: (HomeAction) -> Unit,
    bottom: Dp,
    modifier: Modifier,
    goal: GoalAdvance? = null,
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
                    Overview(content, backup, lowStock, profit, onAction, goal = goal)
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
    backup: HomeBackup,
    lowStock: HomeLowStock?,
    profit: HomeProfit?,
    onAction: (HomeAction) -> Unit,
    bottom: Dp,
    modifier: Modifier,
    goal: GoalAdvance? = null,
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
            Overview(content, backup, lowStock, profit, onAction, goal = goal)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = bottom),
        ) {
            recentSales(content, onAction)
        }
    }
}

/**
 * El héroe, la ganancia de la semana y del mes, la tarjeta del primer uso, los atajos y lo que se
 * está acabando, en ese orden.
 */
@Composable
private fun ColumnScope.Overview(
    content: HomeUiState.Content,
    backup: HomeBackup,
    lowStock: HomeLowStock?,
    profit: HomeProfit?,
    onAction: (HomeAction) -> Unit,
    goal: GoalAdvance? = null,
) {
    backup.wording?.let { BackupSummary(it, backup.notice, backup.photosPending, onAction) }
    TodayHeroBlock(content.hero, Modifier.padding(top = AppTheme.spacing.s8))
    profit?.let { ProfitTiles(it) }
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
    goal?.let { HomeGoalCard(it) { onAction(HomeAction.OpenGoals) } }
    lowStock?.let { LowStockCard(it, onAction) }
}

/**
 * El estado del respaldo de Inicio, cómo va el aviso sin destino y cuántas fotos llevan más de un
 * día sin subir.
 */
@Immutable
private data class HomeBackup(
    val wording: BackupWording?,
    val notice: NoDestinationNotice?,
    val photosPending: Int = 0,
)

/**
 * El respaldo en Inicio: el chip, siempre visible, en su propia línea bajo la barra; el banner
 * sobre el héroe solo si el estado pide atención; y los avisos que ningún estado oculta. Todo abre
 * la pantalla del respaldo, salvo sin destino, que lleva a elegir dónde guardar.
 *
 * Con WebDAV y fotos que llevan más de un día sin subir, una línea aparte bajo el chip, que abre
 * la pantalla del respaldo y nunca cambia el chip.
 *
 * Sin destino, el aviso va entero con `Entendido`; aceptado, es una línea pequeña que también
 * lleva a elegir dónde guardar. Mientras no se sabe cuál toca, [notice] es `null` y no va
 * ninguno, para que el aviso no aparezca entero y se reduzca enseguida.
 */
@Composable
private fun BackupSummary(
    backup: BackupWording,
    notice: NoDestinationNotice?,
    photosPending: Int,
    onAction: (HomeAction) -> Unit,
) {
    // Con datos de ejemplo no hay copias: ni el chip ni sus avisos, solo la banda de arriba.
    if (LocalDemoMode.current) return
    val report = backup.overview.report
    val status = report.status
    BackupStatusChip(backupChipLook(backup), onClick = { onAction(HomeAction.OpenBackup) })
    // Las fotos van aparte de la copia: esta línea nunca cambia el chip, ni con `Todo guardado`.
    if (photosPending > 0) {
        BackupNoticeLine(
            text = stringResource(R.string.storage_photos_pending, photosPending),
            actionLabel = stringResource(R.string.backup_action_review),
            onClick = { onAction(HomeAction.OpenBackup) },
        )
    }
    val noDestination = status == BackupStatus.NOT_CONNECTED
    when {
        !noDestination || notice == NoDestinationNotice.FULL ->
            BackupAttentionBanner(backup, home = true, onAction = { action ->
                onAction(
                    when (action) {
                        BackupBannerAction.OPEN_BACKUP -> HomeAction.OpenBackup
                        BackupBannerAction.CHOOSE_DESTINATION -> HomeAction.ChooseDestination
                        BackupBannerAction.REVIEW_POLICY -> HomeAction.ReviewPolicy
                        BackupBannerAction.ACKNOWLEDGE -> HomeAction.AcknowledgeNoDestination
                    },
                )
            })

        notice == NoDestinationNotice.COMPACT ->
            BackupNoticeLine(
                text = stringResource(R.string.backup_not_connected_compact),
                actionLabel = stringResource(R.string.backup_action_choose_destination),
                onClick = { onAction(HomeAction.ChooseDestination) },
            )

        // Todavía no se sabe si se aceptó: ni entero ni reducido.
        else -> Unit
    }
    val provider = backup.providerLabel ?: NO_VALUE_TEXT
    val app = stringResource(R.string.app_name)
    if (BackupNotice.HUMAN_CHECK_DUE in report.notices && status != BackupStatus.CHECK_DUE) {
        BackupNoticeCard(stringResource(R.string.backup_state_check_due, provider), onAction)
    }
    val fullReadSince = backup.overview.lastFullReadAt
    if (BackupNotice.FULL_READ_OVERDUE in report.notices && fullReadSince != null) {
        val days = (backup.overview.computedAt - fullReadSince).inWholeDays
        BackupNoticeCard(
            stringResource(R.string.backup_full_verify_late_notice, days.toString(), app),
            onAction,
        )
    }
    if (BackupNotice.LOW_DEVICE_STORAGE in report.notices) {
        BackupNoticeCard(stringResource(R.string.backup_device_low), onAction)
    }
}

@Composable
private fun BackupNoticeCard(text: String, onAction: (HomeAction) -> Unit) {
    NoticeCard(
        text,
        NoticeVariant.WARNING,
        actionLabel = stringResource(R.string.backup_action_review),
        onAction = { onAction(HomeAction.OpenBackup) },
    )
}

/**
 * La ganancia final de esta semana y de este mes, lado a lado, cada una con `Después de gastos`
 * debajo. Bajan una debajo de la otra si la letra no cabe. Una cifra negativa va en pérdida, con su
 * signo.
 */
@Composable
private fun ProfitTiles(profit: HomeProfit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        val after = stringResource(R.string.home_after_expenses)
        // La variación compara con los mismos días del período anterior, como en las estadísticas.
        // Sin con qué comparar, queda `Después de gastos`.
        StatTile(
            label = stringResource(R.string.home_this_week),
            amount = profit.weekProfit,
            variant = StatTileVariant.CARD,
            modifier = Modifier.weight(1f).widthIn(min = PROFIT_TILE_MIN),
            change = profitChange(profit.weekChange, profit.month.start),
            sensitive = true,
            caption = after,
        )
        StatTile(
            label = stringResource(R.string.home_this_month),
            amount = profit.monthProfit,
            variant = StatTileVariant.CARD,
            modifier = Modifier.weight(1f).widthIn(min = PROFIT_TILE_MIN),
            change = profitChange(profit.monthChange, profit.month.start),
            sensitive = true,
            caption = after,
        )
    }
}

/**
 * La variación de la ganancia final frente a [compared], o `null` si no se puede medir. Que suba
 * es buena noticia. [reference] es un día del mes en curso: el año se nombra solo si cambia.
 */
@Composable
private fun profitChange(compared: ComparedPeriod?, reference: BusinessDate): StatChange? {
    val change = compared?.variation?.netProfit ?: return null
    return StatChange(
        change,
        favorable = change >= BasisPoints.ZERO,
        periodLabel(compared.comparedTo, reference),
    )
}

/**
 * Los atajos tonales, que reparten el ancho y bajan a otra línea si la letra no cabe: `Compra`,
 * `Gasto` y `Negocio`.
 */
@Composable
private fun Shortcuts(onAction: (HomeAction) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        PrimaryActionButton(
            text = stringResource(R.string.home_shortcut_purchase),
            onClick = { onAction(HomeAction.OpenPurchase) },
            variant = ActionVariant.TONAL,
            modifier = Modifier.weight(1f),
        )
        PrimaryActionButton(
            text = stringResource(R.string.home_shortcut_expense),
            onClick = { onAction(HomeAction.OpenExpense) },
            variant = ActionVariant.TONAL,
            modifier = Modifier.weight(1f),
        )
        PrimaryActionButton(
            text = stringResource(R.string.hub_entry_action),
            onClick = { onAction(HomeAction.OpenBusiness) },
            variant = ActionVariant.TONAL,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * `Por agotarse`: cuántos se están acabando, con `Ver todos` hacia el inventario, la línea del
 * stock negativo si lo hay, que abre el inventario con ese filtro, y los primeros productos, en el
 * mismo orden que el inventario. Cada producto abre su detalle. El stock se ve en modo discreto.
 */
@Composable
private fun LowStockCard(lowStock: HomeLowStock, onAction: (HomeAction) -> Unit) {
    val spacing = AppTheme.spacing
    AppCard(contentPadding = PaddingValues(vertical = spacing.s8)) {
        Row(
            Modifier.fillMaxWidth().padding(start = spacing.s16, end = spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_low_stock_count, lowStock.count),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            InlineTextButton(
                stringResource(R.string.action_view_all_masculine),
                { onAction(HomeAction.OpenLowStock(negativeOnly = false)) },
            )
        }
        if (lowStock.negativeCount > 0) {
            NoticeCard(
                text = pluralStringResource(
                    R.plurals.notice_negative_stock,
                    lowStock.negativeCount,
                    lowStock.negativeCount,
                ),
                variant = NoticeVariant.WARNING,
                modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s4),
                icon = R.drawable.ic_error,
                actionLabel = stringResource(R.string.action_view),
                onAction = { onAction(HomeAction.OpenLowStock(negativeOnly = true)) },
            )
        }
        lowStock.items.forEachIndexed { index, item ->
            ProductRow(
                data = inventoryRowData(item),
                onClick = { onAction(HomeAction.OpenProduct(item.id)) },
                divider = index < lowStock.items.lastIndex,
            )
        }
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
 * Una venta reciente: la hora si es de hoy, o la fecha si no, el primer producto y cuántos más, y
 * el total a la derecha. Anulada, va apagada, tachada y con su etiqueta, como en el historial.
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
    // Con letra grande, un total largo dejaría al nombre sin ancho: el total baja a su propia
    // línea, a la derecha, y el nombre conserva la fila.
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

/** El ancho mínimo de cada tarjeta de ganancia: más angostas, bajan una debajo de la otra. */
private val PROFIT_TILE_MIN = 140.dp
