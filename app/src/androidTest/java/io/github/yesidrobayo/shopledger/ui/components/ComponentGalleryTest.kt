package io.github.yesidrobayo.shopledger.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import java.util.Locale
import kotlinx.datetime.DayOfWeek
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La galería de componentes para la puerta de calidad visual: dibuja cada página con la paleta
 * predeterminada y otra, en claro y en oscuro, y guarda capturas de la pantalla del teléfono, de
 * arriba abajo, para revisarlas a ojo. La escala de la letra es la del sistema: se corre una vez a
 * 100 % y otra a 200 %.
 *
 * Solo corre con el argumento `captureDir`, que nombra la carpeta dentro de la caché de la app.
 */
@RunWith(AndroidJUnit4::class)
class ComponentGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsEveryPageInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    GalleryPage.entries.forEach { page ->
                        val scroll = ScrollState(0)
                        scenario.onActivity { activity ->
                            // Los iconos de la barra del sistema siguen al modo de la página, como en la app.
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(
                                statusBarStyle = bars,
                                navigationBarStyle = bars,
                            )
                            activity.setContent {
                                GalleryFrame(palette, dark, if (page.scrolls) scroll else null) {
                                    page.content()
                                }
                            }
                        }
                        captureScrolling(
                            scenario,
                            scroll,
                            "${palette.name.lowercase()}-${if (dark) "dark" else "light"}-${page.name.lowercase()}",
                            requireNotNull(dir),
                        )
                    }
                }
            }
        }
    }

    private fun captureScrolling(
        scenario: ActivityScenario<ComponentActivity>,
        scroll: ScrollState,
        name: String,
        dir: File,
    ) {
        var shot = 1
        while (true) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$name-$shot.png"))
            var done = false
            scenario.onActivity {
                // Una página sin desplazamiento no conecta su estado: una sola captura basta.
                done =
                    scroll.viewportSize == 0 || scroll.value >= scroll.maxValue || shot >= MAX_SHOTS
                if (!done) scroll.dispatchRawDelta(scroll.viewportSize * SCROLL_FRACTION)
            }
            if (done) return
            shot++
        }
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 700L
        const val MAX_SHOTS = 8
        const val SCROLL_FRACTION = 0.85f
    }
}

/** El marco de cada página: la paleta y el modo pedidos, los formatos de muestra y el papel. */
@Composable
private fun GalleryFrame(
    palette: ColorPalette,
    dark: Boolean,
    scroll: ScrollState?,
    content: @Composable () -> Unit,
) {
    AppTheme(palette = palette, darkTheme = dark) {
        CompositionLocalProvider(
            LocalAppFormats provides previewFormats(),
            LocalDiscreetMode provides false,
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    MaterialTheme.colorScheme.surface,
                ).systemBarsPadding(),
            ) {
                if (scroll == null) {
                    content()
                    return@Box
                }
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(
                        scroll,
                    ).padding(vertical = AppTheme.spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
                ) { content() }
            }
        }
    }
}

/** El título de un grupo de la galería, como encabezado de sección. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        SectionHeader(title)
        content()
    }
}

private val side = Modifier.padding(horizontal = AppTheme.spacing.s16)

/** Las páginas de la galería, cada una con los componentes que se miran juntos. */
private enum class GalleryPage(val content: @Composable () -> Unit, val scrolls: Boolean = true) {
    AMOUNTS({ AmountsPage() }),
    ROWS({ RowsPage() }),
    SALE({ SalePage() }),
    FIELDS({ FieldsPage() }),
    NOTICES({ NoticesPage() }),
    STRUCTURE({ StructurePage() }),
    ONBOARDING({ OnboardingPage() }, scrolls = false),
    FAB({ FabPage(outlined = false) }, scrolls = false),
    FAB_OUTLINED({ FabPage(outlined = true) }, scrolls = false),
}

/** El botón flotante sobre una lista, abajo a la derecha, como lo usan las pantallas. */
@Composable
private fun FabPage(outlined: Boolean) {
    val s = PreviewSamples
    Box(Modifier.fillMaxSize()) {
        // Como en las pantallas: la lista a lo sumo de 600 dp y centrada, el botón en la esquina.
        Column(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).align(Alignment.TopCenter)) {
            SectionHeader(stringResource(R.string.nav_products))
            listOf(
                s.SHORT_NAME,
                s.SECOND_NAME,
                s.THIRD_NAME,
                s.LONG_NAME,
                s.CATEGORY_A,
                s.CATEGORY_B,
            )
                .forEach {
                    SettingsRow(it, SettingsRowAction.Navigation {}, summary = s.TOOL_DETAIL)
                }
        }
        TonalFab(
            text = stringResource(R.string.product_new_title),
            onClick = {},
            modifier = Modifier.align(Alignment.BottomEnd),
            outlined = outlined,
        )
    }
}

@Composable
private fun AmountsPage() {
    val s = PreviewSamples
    Group("MoneyText") {
        Column(
            side.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            MoneyText(s.LONG_AMOUNT, MoneySize.HERO)
            MoneyText(s.MONTH_SALES, MoneySize.LARGE)
            MoneyText(s.SECOND_PRICE, MoneySize.TITLE)
            MoneyText(s.NEGATIVE_AMOUNT, MoneySize.TILE, tone = MoneyTone.LOSS)
            MoneyText(s.PRICE, MoneySize.ROW)
            MoneyText(Money.ZERO, MoneySize.SMALL, tone = MoneyTone.MUTED)
            MoneyText(null, MoneySize.ROW)
            MoneyText(s.SECOND_PRICE, MoneySize.ROW, voided = true)
            MoneyText(s.PRICE, MoneySize.ROW, loading = true)
            CompositionLocalProvider(LocalDiscreetMode provides true) {
                MoneyText(s.MONTH_SALES, MoneySize.TILE, sensitive = true)
            }
        }
    }
    Group("StatTile") {
        Column(side, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            StatTile(
                stringResource(R.string.records_tab_sales),
                s.MONTH_SALES,
                StatTileVariant.CARD,
                Modifier.fillMaxWidth(),
                change = StatChange(BasisPoints(800L), true, s.PERIOD_LABEL),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.heroContainer, MaterialTheme.shapes.extraLarge)
                    .padding(AppTheme.spacing.s20),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
            ) {
                Text(
                    stringResource(R.string.records_tab_sales),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.onHeroVariant,
                )
                MoneyText(s.LONG_AMOUNT, MoneySize.HERO, tone = MoneyTone.ON_HERO)
                StatTile(
                    stringResource(R.string.term_sales_profit),
                    s.MONTH_SALES,
                    StatTileVariant.HERO,
                    change = StatChange(BasisPoints(-320L), false, s.PERIOD_LABEL),
                )
                StatTile(
                    stringResource(R.string.term_margin),
                    s.NEGATIVE_AMOUNT,
                    StatTileVariant.HERO,
                )
            }
            StatTile(
                stringResource(R.string.records_tab_expenses),
                s.NEGATIVE_AMOUNT,
                StatTileVariant.PLAIN,
                sensitive = true,
            )
            StatTile(
                stringResource(R.string.records_tab_sales),
                null,
                StatTileVariant.CARD,
                Modifier.fillMaxWidth(),
                loading = true,
            )
        }
    }
}

@Composable
private fun RowsPage() {
    val s = PreviewSamples
    val unit = s.UNIT
    Group("ProductRow") {
        Column {
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
            ).forEach { ProductRow(it, onClick = {}) }
            CompositionLocalProvider(LocalDiscreetMode provides true) {
                ProductRow(
                    ProductRowData(
                        s.SHORT_NAME,
                        ProductStock.Normal(s.STOCK, unit),
                        ProductRowTrailing.Catalog(
                            s.PRICE,
                            Margin.Defined(BasisPoints(2_400L)),
                            true,
                        ),
                    ),
                )
            }
            ProductRow(
                ProductRowData(
                    s.SHORT_NAME,
                    ProductStock.Service,
                    ProductRowTrailing.Catalog(s.PRICE, Margin.NoData, true),
                ),
                loading = true,
            )
        }
    }
    Group("ProductRow con etiqueta del sistema") {
        // La misma fila con un símbolo de las fuentes propias y con una etiqueta que solo tiene el sistema.
        val ownFormats = previewFormats()
        val systemFormats = withSymbol(ownFormats, SYSTEM_LABEL)
        Column {
            CompositionLocalProvider(LocalAppFormats provides ownFormats) {
                ProductRow(
                    ProductRowData(
                        s.SHORT_NAME,
                        ProductStock.Normal(s.STOCK, unit),
                        ProductRowTrailing.Catalog(
                            s.SECOND_PRICE,
                            Margin.Defined(BasisPoints(2_400L)),
                            true,
                        ),
                    ),
                )
            }
            CompositionLocalProvider(LocalAppFormats provides systemFormats) {
                ProductRow(
                    ProductRowData(
                        s.SHORT_NAME,
                        ProductStock.Normal(s.STOCK, unit),
                        ProductRowTrailing.Catalog(
                            s.SECOND_PRICE,
                            Margin.Defined(BasisPoints(2_400L)),
                            true,
                        ),
                    ),
                )
            }
        }
    }
    Group("RankingRow") {
        Column {
            RankingRow(s.SHORT_NAME, s.MONTH_SALES, s.MONTH_SALES)
            RankingRow(s.LONG_NAME, Money(1_250_000L), s.MONTH_SALES)
            RankingRow(s.THIRD_NAME, s.PRICE, s.MONTH_SALES)
        }
    }
    Group("ToolRow · SettingsRow") {
        Column {
            ToolRow(s.TOOL_NAME, s.TOOL_DETAIL, {})
            ToolRow(s.TOOL_NAME, s.TOOL_DETAIL, {}, attention = s.TOOL_ATTENTION)
            SettingsRow(s.SETTING_TITLE, SettingsRowAction.Toggle(true) {})
            SettingsRow(
                s.SETTING_TITLE,
                SettingsRowAction.Toggle(false) {
                },
                summary = s.NOTICE_TEXT,
            )
            SettingsRow(s.TOOL_NAME, SettingsRowAction.Navigation {}, summary = s.TOOL_DETAIL)
            SettingsRow(
                s.SHORT_NAME,
                SettingsRowAction.Value {
                    MoneyText(s.LONG_AMOUNT, MoneySize.ROW)
                },
            )
        }
    }
}

@Composable
private fun SalePage() {
    val s = PreviewSamples
    Group("SearchField") {
        Column(side, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            SearchField("", {}, stringResource(R.string.sale_search_hint), onScan = {})
            SearchField(s.SEARCH_QUERY, {}, stringResource(R.string.sale_search_hint))
        }
    }
    Group("SaleProductTile") {
        // Como en la venta: dos columnas, y una sola con letra grande.
        val columns = if (LocalDensity.current.fontScale < LARGE_FONT) 2 else 1
        val tiles: List<@Composable (Modifier) -> Unit> =
            listOf(
                {
                    SaleProductTile(s.SHORT_NAME, s.PRICE, quantityText(s.STOCK, s.UNIT), s.UNIT, {
                    }, it)
                },
                {
                    SaleProductTile(
                        s.SECOND_NAME,
                        s.SECOND_PRICE,
                        null,
                        s.UNIT,
                        {},
                        it,
                        line = SaleTileLine(s.TWO, {
                        }, {}, removesLine = false, oversellBy = s.NEGATIVE_STOCK),
                    )
                },
                {
                    SaleProductTile(s.LONG_NAME, null, quantityText(s.LOW_STOCK, s.UNIT), s.UNIT, {
                    }, it)
                },
                {
                    SaleProductTile(
                        s.THIRD_NAME,
                        s.LONG_AMOUNT,
                        null,
                        s.UNIT,
                        {},
                        it,
                        line = SaleTileLine(s.ONE, {}, {}, removesLine = true),
                    )
                },
            )
        Column(side, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            tiles.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
                    row.forEach { tile -> tile(Modifier.weight(1f)) }
                }
            }
        }
    }
    Group("QuantityStepper") {
        Column(side, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            QuantityStepper(s.ONE, s.UNIT, s.SHORT_NAME, {}, {}, removesLine = true)
            QuantityStepper(s.TWO, s.BOX, s.SHORT_NAME, {}, {}, canIncrement = false)
            QuantityStepper(s.WEIGHT, s.KILOGRAM, s.SECOND_NAME, {}, {}, onValueClick = {})
        }
    }
    Group("PaymentMethodChips") {
        PaymentMethodChips(
            listOf(
                PaymentMethodOption(1, s.METHOD_CASH, false),
                PaymentMethodOption(2, s.METHOD_CARD, false),
                PaymentMethodOption(3, s.METHOD_TRANSFER, true),
                PaymentMethodOption(4, s.METHOD_WALLET, true),
            ),
            selectedId = 1,
            onSelect = {},
            modifier = side,
        )
    }
    Group("PrimaryActionButton") {
        Column(side, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            val amount = io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay(
                s.LONG_AMOUNT,
                LocalAppFormats.current.money,
            )
            PrimaryActionButton(
                formatAnnotated(
                    stringResource(R.string.sale_register_amount),
                    amount.text,
                ),
                {
                },
                ActionVariant.PRIMARY,
            )
            PrimaryActionButton(stringResource(R.string.sale_register), {
            }, ActionVariant.PRIMARY, state = ActionState.WORKING)
            PrimaryActionButton(stringResource(R.string.empty_home_action), {
            }, ActionVariant.TONAL, state = ActionState.DONE)
            PrimaryActionButton(stringResource(R.string.empty_home_action), {
            }, ActionVariant.OUTLINED)
            PrimaryActionButton(stringResource(R.string.empty_home_action), {}, ActionVariant.TEXT)
            PrimaryActionButton(s.DIALOG_ACTION, {}, ActionVariant.DESTRUCTIVE)
            PrimaryActionButton(stringResource(R.string.sale_register), {
            }, ActionVariant.PRIMARY, enabled = false)
        }
    }
    Group("AppSnackbar") {
        Column {
            AppSnackbarContent(
                stringResource(R.string.sale_register),
                SnackbarVariant.SUCCESS,
                actionLabel = stringResource(R.string.action_cancel),
            )
            AppSnackbarContent(s.NOTICE_TEXT, SnackbarVariant.INFO)
            AppSnackbarContent(
                stringResource(R.string.state_error_read),
                SnackbarVariant.ERROR,
                actionLabel = stringResource(R.string.action_retry),
            )
        }
    }
}

@Composable
private fun FieldsPage() {
    val s = PreviewSamples
    val today = s.TODAY
    val calendar =
        BusinessCalendar(requireNotNull(BusinessTimeZone.of("Etc/UTC")), DayOfWeek.MONDAY)
    Group("MoneyField · QuantityField · PercentField · WholeNumberField") {
        Column(side, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            MoneyField("1234567", {}, stringResource(R.string.form_amount))
            MoneyField(
                "",
                {
                },
                stringResource(
                    R.string.form_amount,
                ),
                error = stringResource(R.string.state_error_read),
            )
            QuantityField("1,25", {}, stringResource(R.string.form_amount), s.KILOGRAM)
            PercentField("2,75", {}, stringResource(R.string.term_margin))
            PercentField(
                "-150",
                {},
                stringResource(R.string.term_margin),
                allowNegative = true,
                error = stringResource(R.string.state_error_read),
            )
            WholeNumberField("3650", {}, stringResource(R.string.form_amount))
        }
    }
    Group("AdaptiveFieldRow") {
        val percent = stringResource(R.string.term_margin)
        val amount = stringResource(R.string.form_amount)
        AdaptiveFieldRow(
            firstLabel = percent,
            secondLabel = amount,
            modifier = side,
            first = { PercentField("2,75", {}, percent, it) },
            second = { MoneyField("100", {}, amount, it) },
        )
    }
    Group("AppTextField") {
        Column(side, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            AppTextField(s.LONG_NAME, {}, stringResource(R.string.form_name))
            AppTextField("", {}, stringResource(R.string.form_name))
            AppTextField(
                s.SHORT_NAME,
                {},
                stringResource(R.string.form_name),
                error = stringResource(R.string.state_error_read),
            )
        }
    }
    Group("DateField") {
        Column(side, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            DateField(stringResource(R.string.form_date), today, {}, today, DayOfWeek.MONDAY)
            DateField(stringResource(R.string.form_date), today.plusDays(-400), {
            }, today, DayOfWeek.SUNDAY)
        }
    }
    Group("MonthCalendar") {
        MonthCalendar(today.plusDays(-3), {
        }, today, DayOfWeek.MONDAY, Modifier.padding(horizontal = AppTheme.spacing.s4))
    }
    Group("PeriodSelector") {
        Column {
            PeriodSelector(calendar.day(today), {}, {}, showNext = false, today = today)
            PeriodSelector(calendar.previous(calendar.week(today)), {
            }, {}, showNext = true, today = today)
            PeriodSelector(calendar.month(today), {}, {}, showNext = false, today = today)
        }
    }
    Group("CompactChipRow · SegmentedTabs") {
        Column(side, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            CompactChipRow(listOf(s.CATEGORY_A, s.CATEGORY_B, s.CATEGORY_C), {
                it
            }, setOf(s.CATEGORY_B), {}, multiple = true)
            SegmentedTabs(
                listOf(
                    stringResource(R.string.records_tab_sales),
                    stringResource(R.string.records_tab_expenses),
                    stringResource(R.string.records_tab_purchases),
                ),
                0,
                {},
            )
            SegmentedTabs(
                listOf(
                    stringResource(R.string.records_tab_sales),
                    stringResource(R.string.records_tab_expenses),
                    stringResource(R.string.records_tab_purchases),
                    stringResource(R.string.term_inventory),
                    stringResource(R.string.nav_products),
                ),
                1,
                {},
            )
        }
    }
}

@Composable
private fun NoticesPage() {
    val s = PreviewSamples
    Group("PhotoThumb") {
        Row(side, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            PhotoThumb(PhotoState.Pending, s.SHORT_NAME)
            PhotoThumb(PhotoState.Unavailable, s.SHORT_NAME, size = PhotoThumbSize.TABLET)
            PhotoThumb(PhotoState.Unavailable, s.SHORT_NAME, size = PhotoThumbSize.DETAIL)
        }
    }
    Group("NoticeCard") {
        Column(side, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            NoticeCard(s.NOTICE_TEXT, NoticeVariant.INFO)
            NoticeCard(
                stringResource(R.string.notice_negative_margin),
                NoticeVariant.WARNING,
                icon = R.drawable.ic_trending_down,
            )
            NoticeCard(
                stringResource(R.string.state_error_read),
                NoticeVariant.ERROR,
                actionLabel = stringResource(R.string.action_retry),
            )
        }
    }
    Group("StatusTag") {
        FlowRow(side, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            StatusTag(stringResource(R.string.status_no_cost))
            StatusTag(
                stringResource(R.string.term_low_stock),
                icon = R.drawable.ic_warning,
                tone = StatusTone.WARNING,
            )
            StatusTag(
                stringResource(R.string.term_out_of_stock),
                icon = R.drawable.ic_block,
                tone = StatusTone.LOSS,
            )
        }
    }
    Group("EmptyState") {
        Column {
            EmptyState(
                R.drawable.ic_inventory_2,
                stringResource(R.string.empty_catalog_title),
                body = stringResource(R.string.empty_catalog_body),
            ) {
                PrimaryActionButton(stringResource(R.string.empty_catalog_action), {
                }, ActionVariant.PRIMARY)
            }
            AppCard(side.fillMaxWidth()) {
                EmptyStateInline(
                    stringResource(R.string.empty_search_title, s.SEARCH_QUERY),
                    body = stringResource(R.string.empty_sales_body),
                ) {
                    InlineTextButton(stringResource(R.string.empty_sales_action), {})
                }
            }
        }
    }
    Group("ConfirmDialog") {
        Column(side, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            ConfirmDialogContent(s.DIALOG_TITLE, s.DIALOG_BODY, s.DIALOG_ACTION, {}, {})
            ConfirmDialogContent(s.DIALOG_TITLE, s.DIALOG_BODY, s.DIALOG_ACTION, {
            }, {}, variant = ConfirmVariant.DESTRUCTIVE, acknowledgement = s.DIALOG_ACK)
        }
    }
}

@Composable
private fun StructurePage() {
    val s = PreviewSamples
    Group("AppTopBar · OverflowMenu") {
        AppTopBar(
            title = stringResource(R.string.nav_products),
            navigation = TopBarNavigation.NONE,
            onNavigate = {},
            navigationLabel = null,
            actions = {
                OverflowMenu(listOf(OverflowAction(stringResource(R.string.action_edit), {})))
            },
        )
    }
    Group("AppCard") {
        AppCard(side.fillMaxWidth(), onClick = {}) {
            Text(s.LONG_NAME, style = MaterialTheme.typography.titleSmall)
            MoneyText(s.PRICE, MoneySize.ROW)
        }
    }
    Group("AppBottomSheet · SelectionSheet") {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    MaterialTheme.shapes.extraLarge,
                )
                .padding(top = AppTheme.spacing.s16),
        ) {
            AppBottomSheetContent(stringResource(R.string.categories_title), scrollable = false) {
                SelectionSheetContent(
                    items = listOf(s.CATEGORY_A, s.CATEGORY_B),
                    key = { it },
                    query = s.CATEGORY_A.take(3),
                    onQueryChange = {},
                    searchHint = stringResource(R.string.sale_search_hint),
                    itemContent = { SettingsRow(it, SettingsRowAction.Navigation {}) },
                    onCreate = {},
                )
            }
        }
    }
    Group("AppBottomBar") {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s24)) {
            BottomBarSample(LocalResources.current)
            // La seudolocalización alarga cada etiqueta: pasan a dos líneas antes de cortarse.
            val pseudo =
                LocalContext.current.createConfigurationContext(
                    Configuration(LocalConfiguration.current).apply {
                        setLocale(Locale.forLanguageTag("en-XA"))
                    },
                ).resources
            BottomBarSample(pseudo)
        }
    }
}

@Composable
private fun BottomBarSample(resources: android.content.res.Resources) {
    AppBottomBar(
        items =
            listOf(
                BottomBarItem(
                    resources.getString(R.string.nav_home),
                    R.drawable.ic_home,
                    R.drawable.ic_home_filled,
                ),
                BottomBarItem(
                    resources.getString(R.string.nav_records),
                    R.drawable.ic_receipt_long,
                    R.drawable.ic_receipt_long_filled,
                ),
                BottomBarItem(
                    resources.getString(R.string.nav_products),
                    R.drawable.ic_inventory_2,
                    R.drawable.ic_inventory_2_filled,
                ),
                BottomBarItem(
                    resources.getString(R.string.nav_numbers),
                    R.drawable.ic_bar_chart,
                    R.drawable.ic_bar_chart_filled,
                ),
            ),
        selected = 2,
        onSelect = {},
        sellLabel = resources.getString(R.string.nav_sell),
        onSell = {},
        modifier = Modifier.padding(top = AppTheme.sizes.sellButtonOverhang),
    )
}

/** El primer uso ocupa la pantalla entera, con su acción fija abajo: no va dentro del desplazamiento. */
@Composable
private fun OnboardingPage() {
    OnboardingStep(
        step = 2,
        totalSteps = 4,
        title = stringResource(R.string.empty_home_title),
        action = {
            PrimaryActionButton(stringResource(R.string.empty_home_action), {
            }, ActionVariant.PRIMARY)
        },
    ) {
        Text(stringResource(R.string.empty_home_body), style = MaterialTheme.typography.bodyLarge)
        NoticeCard(PreviewSamples.NOTICE_TEXT, NoticeVariant.INFO)
        DateField(stringResource(R.string.form_date), PreviewSamples.TODAY, {
        }, PreviewSamples.TODAY, DayOfWeek.MONDAY)
    }
}

/** Desde esta escala de letra la venta usa una sola columna de tarjetas. */
private const val LARGE_FONT = 1.5f

/** Una etiqueta árabe de moneda escrita a mano: solo la fuente del sistema la tiene. */
private const val SYSTEM_LABEL = "د.إ"

/** Los mismos formatos con otro símbolo escrito por la persona. */
private fun withSymbol(formats: AppFormats, symbol: String): AppFormats {
    val money = formats.money
    return AppFormats(
        money =
            MoneyFormatter(
                formatLocale = requireNotNull(FormatLocale.of(money.formatLocale.toString())),
                currencyCode = requireNotNull(CurrencyCode.of(money.currencyCode.code)),
                currencyDecimals = money.currencyDecimals,
                currencySymbol = symbol,
                uiLocale = money.uiLocale,
            ),
        quantity = formats.quantity,
        percent = formats.percent,
        date = formats.date,
    )
}
