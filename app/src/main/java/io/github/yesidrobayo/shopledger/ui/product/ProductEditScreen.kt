package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomValueIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceOrigin
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceResult
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.BarcodeField
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PhotoThumb
import io.github.yesidrobayo.shopledger.ui.components.PhotoThumbSize
import io.github.yesidrobayo.shopledger.ui.components.PickerField
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.UnitPicker
import io.github.yesidrobayo.shopledger.ui.components.UnitPickerSheet
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.media.LocalPhotoSource
import io.github.yesidrobayo.shopledger.ui.media.ProductPhoto
import io.github.yesidrobayo.shopledger.ui.media.photoDescription
import io.github.yesidrobayo.shopledger.ui.media.rememberPhoto
import io.github.yesidrobayo.shopledger.ui.money.ProvideAccountCurrency
import io.github.yesidrobayo.shopledger.ui.payment.ImageProblem
import io.github.yesidrobayo.shopledger.ui.pricing.ListPriceEditor
import io.github.yesidrobayo.shopledger.ui.pricing.ListPriceEditorAction
import io.github.yesidrobayo.shopledger.ui.pricing.ListPriceEditorSheet
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import io.github.yesidrobayo.shopledger.ui.unit.ConversionEditor
import io.github.yesidrobayo.shopledger.ui.unit.ConversionEditorAction
import io.github.yesidrobayo.shopledger.ui.unit.ConversionEditorSheet
import io.github.yesidrobayo.shopledger.ui.unit.ConversionRow
import io.github.yesidrobayo.shopledger.ui.unit.EditorContainer
import io.github.yesidrobayo.shopledger.ui.unit.UnitEditor
import io.github.yesidrobayo.shopledger.ui.unit.UnitEditorAction
import io.github.yesidrobayo.shopledger.ui.unit.UnitEditorSheet
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek

/**
 * Crear o editar un producto: nombre, categoría, unidad, precio y costo con la ganancia en vivo, y el
 * stock. Lo opcional va plegado en `Más detalles`.
 *
 * Es pura: recibe el estado y avisa cada acción. El botón `Guardar producto` queda fijo abajo, sobre
 * el teclado, y un error de guardado se ve arriba de él sin borrar nada de lo escrito. Con letra
 * grande, precio y costo van uno bajo el otro. En una ventana ancha, el formulario va centrado a su
 * ancho de lectura.
 */
@Composable
fun ProductEditScreen(
    uiState: ProductEditUiState,
    onAction: (ProductEditAction) -> Unit,
    modifier: Modifier = Modifier,
    unitEditor: UnitEditor? = null,
    onUnitEditorAction: (UnitEditorAction) -> Unit = {},
    conversionEditor: ConversionEditor? = null,
    onConversionEditorAction: (ConversionEditorAction) -> Unit = {},
    listPriceEditor: ListPriceEditor? = null,
    onListPriceEditorAction: (ListPriceEditorAction) -> Unit = {},
) {
    val spacing = AppTheme.spacing
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding(),
    ) {
        AppTopBar(
            title =
                if (uiState.isNew) {
                    stringResource(R.string.product_new_title)
                } else {
                    uiState.form.name.ifBlank { stringResource(R.string.product_new_title) }
                },
            navigation = TopBarNavigation.CLOSE,
            onNavigate = { onAction(ProductEditAction.Close) },
            navigationLabel = stringResource(R.string.a11y_close),
            titleStyle = MaterialTheme.typography.titleLarge,
            actions = { ScreenHelpLink(HelpTopic.PRODUCT_EDIT) },
        )
        when {
            uiState.loading -> DelayedSkeleton { FormSkeleton() }

            uiState.notFound ->
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                )

            uiState.readFailed ->
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                ) {
                    InlineTextButton(
                        stringResource(R.string.action_retry),
                        { onAction(ProductEditAction.Retry) },
                    )
                }

            else -> {
                Column(
                    Modifier
                        .weight(1f)
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.s16, vertical = spacing.s8),
                    verticalArrangement = Arrangement.spacedBy(spacing.s12),
                ) { ProductFormFields(uiState, onAction) }
                SaveBar(uiState, onAction, Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
    uiState.suggestion?.let { SuggestionSheet(it, onAction) }
    unitEditor?.let { UnitEditorSheet(it, onUnitEditorAction) }
    conversionEditor?.let { editor ->
        ConversionEditorSheet(
            editor = editor,
            products = listOfNotNull(uiState.product),
            units = uiState.units,
            onAction = onConversionEditorAction,
        )
    }
    listPriceEditor?.let { ListPriceEditorSheet(it, onListPriceEditorAction) }
    if (uiState.confirmingDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_discard_title),
            body = stringResource(R.string.dialog_discard_body),
            confirmLabel = stringResource(R.string.dialog_discard_action),
            onConfirm = { onAction(ProductEditAction.Discard) },
            onDismiss = { onAction(ProductEditAction.KeepEditing) },
        )
    }
}

@Composable
private fun ProductFormFields(uiState: ProductEditUiState, onAction: (ProductEditAction) -> Unit) {
    val form = uiState.form
    val unit =
        uiState.unit?.let { unitLabel(it) }
            ?: UnitLabel(stringResource(R.string.seed_units_unit), false)
    var choosingCategory by rememberSaveable { mutableStateOf(false) }
    var choosingUnit by rememberSaveable { mutableStateOf(false) }
    // Se abre sola si el producto ya tiene alguno de los datos de adentro.
    var moreDetails by rememberSaveable {
        mutableStateOf(
            form.barcode.isNotEmpty() || form.replacementCost.isNotEmpty() ||
                form.referencePrice.isNotEmpty() || !uiState.tracksStock ||
                form.photo != EditPhoto.None || form.tracksLots ||
                form.usage != ProductUsage.SALE,
        )
    }
    LaunchedEffect(uiState.conversions.isNotEmpty()) {
        if (uiState.conversions.isNotEmpty()) moreDetails = true
    }
    // Los valores propios llegan después de abrir: con alguno, también se abre sola.
    LaunchedEffect(uiState.extras.hasData) {
        if (uiState.extras.hasData) moreDetails = true
    }

    AppTextField(
        value = form.name,
        onValueChange = { onAction(ProductEditAction.NameChange(it)) },
        label = stringResource(R.string.form_name),
        error = fieldError(uiState, ProductEditField.NAME),
        onLeave = { onAction(ProductEditAction.NameLeft) },
    )
    PickerField(
        label = stringResource(R.string.form_category),
        value = uiState.category?.details?.name ?: stringResource(R.string.term_no_category),
        onClick = { choosingCategory = true },
    )
    UnitPicker(
        label = stringResource(R.string.term_unit),
        unit = uiState.unit,
        onClick = { choosingUnit = true },
        lockedReason = if (uiState.unitLocked) stringResource(R.string.unit_base_locked) else null,
    )
    PriceAndCost(uiState, onAction)
    ProfitCard(uiState.profit, uiState.suggestTarget, onAction)
    if (uiState.isNew && uiState.tracksStock) {
        QuantityField(
            value = form.initialStock,
            onValueChange = { onAction(ProductEditAction.InitialStockChange(it)) },
            label = stringResource(R.string.product_initial_stock),
            unit = unit,
            error = fieldError(uiState, ProductEditField.INITIAL_STOCK),
        )
    }
    if (uiState.tracksStock) MinStockField(uiState, onAction, unit)
    InlineTextButton(
        text = stringResource(R.string.field_more_details),
        onClick = { moreDetails = !moreDetails },
    )
    if (moreDetails) MoreDetails(uiState, onAction)

    if (choosingCategory) {
        CategorySheet(uiState.categories, onAction) { choosingCategory = false }
    }
    if (choosingUnit) {
        UnitPickerSheet(
            units = uiState.units,
            onPick = { onAction(ProductEditAction.UnitChange(it.id)) },
            onDismiss = { choosingUnit = false },
            onNewUnit = { onAction(ProductEditAction.NewUnit) },
            onManage = { onAction(ProductEditAction.ManageUnits) },
        )
    }
}

/** El mínimo de la alerta, con su ayuda debajo mientras no tenga un error. */
@Composable
private fun MinStockField(
    uiState: ProductEditUiState,
    onAction: (ProductEditAction) -> Unit,
    unit: UnitLabel,
) {
    val form = uiState.form
    Column {
        QuantityField(
            value = form.minStock,
            onValueChange = { onAction(ProductEditAction.MinStockChange(it)) },
            label = stringResource(R.string.product_min_stock),
            unit = unit,
            error = fieldError(uiState, ProductEditField.MIN_STOCK),
        )
        if (uiState.errors[ProductEditField.MIN_STOCK] == null) {
            Text(
                stringResource(R.string.product_min_stock_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = AppTheme.spacing.s16,
                    top = AppTheme.spacing.s4,
                ),
            )
        }
    }
}

/**
 * Lo opcional: el código, el modo de stock y, en un producto guardado, sus unidades de compra y
 * venta. `Por componentes` solo aparece en un producto que ya lo usa, hasta que exista la pantalla de
 * combos y recetas; con historia, el modo ya no cambia y se muestra para leer.
 */
@Composable
private fun MoreDetails(uiState: ProductEditUiState, onAction: (ProductEditAction) -> Unit) {
    val form = uiState.form
    ProductPhotoField(uiState, onAction)
    BarcodeField(
        value = form.barcode,
        onValueChange = { onAction(ProductEditAction.BarcodeChange(it)) },
        onScan = { onAction(ProductEditAction.ScanBarcode) },
        label = stringResource(R.string.product_barcode),
        error = fieldError(uiState, ProductEditField.BARCODE),
    )
    // Un código propio necesita el id: solo en un producto ya guardado y sin código.
    if (!uiState.isNew && form.barcode.isBlank()) {
        InlineTextButton(
            text = stringResource(R.string.product_generate_barcode),
            onClick = { onAction(ProductEditAction.GenerateBarcode) },
        )
    }
    Text(
        stringResource(R.string.combo_stock_mode_label),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = AppTheme.spacing.s8),
    )
    if (uiState.stockModeLocked) {
        Text(
            stringResource(stockModeLabel(form.stockMode)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    } else {
        CompactChipRow(
            options = ProductStockMode.entries,
            label = { stringResource(stockModeLabel(it)) },
            selected = setOf(form.stockMode),
            onToggle = { onAction(ProductEditAction.StockModeChange(it)) },
            multiple = false,
        )
    }
    // Un combo guardado lleva a su receta: su costo y lo que alcanza salen de ella.
    if (!uiState.isNew && uiState.product?.details?.stockMode == ProductStockMode.COMPONENTS) {
        InlineTextButton(
            text = stringResource(R.string.combo_components_title),
            onClick = { onAction(ProductEditAction.OpenRecipe) },
        )
    }
    ProductUsageField(uiState, onAction)
    if (uiState.tracksStock) ReplacementCostField(uiState, onAction)
    if (uiState.tracksStock) ProductLotsRow(uiState, onAction)
    uiState.reference?.let { ReferencePriceField(uiState, it, onAction) }
    if (!uiState.isNew) ProductConversions(uiState, onAction)
    // Un producto guardado puede tener variantes, salvo una variante o un combo.
    val product = uiState.product
    if (product != null && product.details.parentProductId == null &&
        form.stockMode != ProductStockMode.COMPONENTS
    ) {
        SettingsRow(
            title = stringResource(R.string.product_variants_row),
            action = SettingsRowAction.Navigation { onAction(ProductEditAction.OpenVariants) },
        )
    }
    // Un producto guardado puede ofrecer extras en su línea de venta.
    if (product != null) {
        SettingsRow(
            title = stringResource(R.string.product_extras_row),
            action = SettingsRowAction.Navigation { onAction(ProductEditAction.OpenExtras) },
        )
    }
    if (!uiState.isNew && uiState.listPrices.isNotEmpty()) ProductListPrices(uiState, onAction)
    // Al final, los campos propios y las etiquetas, si el negocio tiene alguno.
    if (uiState.extras.visible) ProductExtrasSection(uiState.extras, onAction)
}

/**
 * `Uso`: si el producto se vende, es un insumo o las dos cosas, con una ayuda que dice qué cambia.
 * Un insumo no aparece al vender, pero sí en compras, conteos, recetas y producción. Si un
 * producto guardado que se vendía pasa a insumo, se avisa que sus cotizaciones y pedidos abiertos
 * lo dejarán por fuera; lo guardado no cambia.
 */
@Composable
private fun ProductUsageField(uiState: ProductEditUiState, onAction: (ProductEditAction) -> Unit) {
    val usage = uiState.form.usage
    Text(
        stringResource(R.string.product_usage_label),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = AppTheme.spacing.s8),
    )
    CompactChipRow(
        options = ProductUsage.entries,
        label = { stringResource(usageLabel(it)) },
        selected = setOf(usage),
        onToggle = { onAction(ProductEditAction.UsageChange(it)) },
        multiple = false,
    )
    Text(
        stringResource(
            when (usage) {
                ProductUsage.SALE -> R.string.product_usage_sale_help
                ProductUsage.INPUT -> R.string.product_usage_input_help
                ProductUsage.BOTH -> R.string.product_usage_both_help
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = AppTheme.spacing.s16),
    )
    if (uiState.warnsInputChange) {
        Text(
            stringResource(R.string.product_usage_input_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = AppTheme.spacing.s16),
        )
    }
}

/**
 * `Controlar por lotes`, solo con stock propio. Sin historia es un interruptor; con historia ya no
 * cambia, porque el libro no mezcla filas con lote y sin lote: si lleva lotes se dice para leer, y
 * si no, la fila no aparece, porque no hay nada que hacer con ella.
 */
@Composable
private fun ProductLotsRow(uiState: ProductEditUiState, onAction: (ProductEditAction) -> Unit) {
    val title = stringResource(R.string.lot_tracks_label)
    when {
        !uiState.lotsLocked ->
            SettingsRow(
                title = title,
                action = SettingsRowAction.Toggle(uiState.form.tracksLots) {
                    onAction(ProductEditAction.TracksLotsChange(it))
                },
            )

        uiState.form.tracksLots ->
            SettingsRow(
                title = title,
                summary = stringResource(R.string.lot_tracks_locked),
                action = SettingsRowAction.Value { },
            )
    }
}

/**
 * La foto del producto: su miniatura con `Cambiar foto` y `Quitar foto`, o solo `Elegir foto`. Se
 * recodifica sin EXIF al elegirla y se anota al guardar el producto. Si no se pudo leer, la que
 * había sigue igual y se dice debajo.
 */
@Composable
private fun ProductPhotoField(uiState: ProductEditUiState, onAction: (ProductEditAction) -> Unit) {
    val spacing = AppTheme.spacing
    val photo = uiState.form.photo
    val choosing = if (uiState.importingPhoto) ActionState.WORKING else ActionState.IDLE
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        Text(
            stringResource(R.string.product_photo),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (photo == EditPhoto.None) {
            PrimaryActionButton(
                stringResource(R.string.product_photo_choose_action),
                { onAction(ProductEditAction.ChoosePhoto) },
                ActionVariant.OUTLINED,
                state = choosing,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (photo) {
                    is EditPhoto.Saved -> ProductPhoto(photo.mediaId, size = PhotoThumbSize.DETAIL)

                    is EditPhoto.Picked -> {
                        val cache = LocalPhotoSource.current.cache
                        rememberPhoto(photo.image, PhotoThumbSize.DETAIL, cache)?.let { state ->
                            PhotoThumb(state, photoDescription(state), size = PhotoThumbSize.DETAIL)
                        }
                    }

                    EditPhoto.None -> Unit
                }
                Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
                    InlineTextButton(
                        stringResource(R.string.product_photo_change_action),
                        { onAction(ProductEditAction.ChoosePhoto) },
                    )
                    InlineTextButton(
                        stringResource(R.string.product_photo_remove_action),
                        { onAction(ProductEditAction.RemovePhoto) },
                    )
                }
            }
        }
        uiState.photoProblem?.let { problem ->
            Text(
                stringResource(
                    when (problem) {
                        ImageProblem.UNREADABLE -> R.string.product_photo_error
                        ImageProblem.OUT_OF_SPACE -> R.string.error_device_storage
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * El precio del producto en cada lista vigente distinta de la inicial: el propio, el general o el
 * calculado, con su etiqueta. Tocar una fija abre la hoja de su precio; una relativa se calcula y no
 * se toca. El precio de la inicial es el `Precio de venta` de arriba.
 */
@Composable
private fun ProductListPrices(uiState: ProductEditUiState, onAction: (ProductEditAction) -> Unit) {
    val resources = LocalResources.current
    Text(
        stringResource(R.string.product_prices_by_list),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = AppTheme.spacing.s16).semantics { heading() },
    )
    Column {
        for (entry in uiState.listPrices) {
            key(entry.list.id) {
                val priced = entry.result as? ListPriceResult.Priced
                val tag =
                    when (priced?.origin) {
                        null -> null

                        ListPriceOrigin.SALE_PRICE -> stringResource(
                            R.string.price_list_tag_general,
                        )

                        ListPriceOrigin.OWN -> stringResource(R.string.price_list_tag_own)

                        ListPriceOrigin.CALCULATED -> stringResource(
                            R.string.price_list_tag_calculated,
                        )
                    }
                val calculated = entry.list.pricing is PriceListPricing.Relative
                ProductRow(
                    data = ProductRowData(
                        name = SeedText.displayName(
                            resources,
                            SeedTable.PRICE_LISTS,
                            entry.list.systemKey,
                            entry.list.name,
                        ),
                        stock = null,
                        trailing = ProductRowTrailing.Catalog(
                            price = priced?.price?.takeIf { it.minor > 0L },
                            margin = Margin.NoData,
                            hasCost = true,
                        ),
                        tag = tag,
                    ),
                    onClick = if (calculated) {
                        null
                    } else {
                        (
                            {
                                onAction(ProductEditAction.EditListPrice(entry.list.id))
                            }
                            )
                    },
                )
            }
        }
    }
}

/**
 * El precio de venta escrito en la moneda de referencia [reference], con su símbolo y sus
 * decimales. Solo aparece con una moneda de referencia elegida: de él parte repreciar con una
 * cotización.
 */
@Composable
private fun ReferencePriceField(
    uiState: ProductEditUiState,
    reference: ReferenceCurrency,
    onAction: (ProductEditAction) -> Unit,
) {
    Column {
        ProvideAccountCurrency(AccountCurrency(reference.code, reference.decimals)) {
            MoneyField(
                value = uiState.form.referencePrice,
                onValueChange = { onAction(ProductEditAction.ReferencePriceChange(it)) },
                label = stringResource(R.string.product_reference_price),
                modifier = Modifier.fillMaxWidth(),
                error = fieldError(uiState, ProductEditField.REFERENCE_PRICE),
            )
        }
        if (uiState.errors[ProductEditField.REFERENCE_PRICE] == null) {
            Text(
                stringResource(R.string.product_reference_price_help, reference.code.code),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = AppTheme.spacing.s16,
                    top = AppTheme.spacing.s4,
                ),
            )
        }
    }
}

/**
 * Lo que costaría reponer una unidad, corregido a mano. Vacío es sin dato, y así se queda: nunca se
 * llena con el costo promedio. La ayuda recuerda que la última compra lo actualiza y que la ganancia
 * se sigue midiendo con el costo promedio.
 */
@Composable
private fun ReplacementCostField(
    uiState: ProductEditUiState,
    onAction: (ProductEditAction) -> Unit,
) {
    Column {
        MoneyField(
            value = uiState.form.replacementCost,
            onValueChange = { onAction(ProductEditAction.ReplacementCostChange(it)) },
            label = stringResource(R.string.product_replacement_cost),
            modifier = Modifier.fillMaxWidth(),
            error = fieldError(uiState, ProductEditField.REPLACEMENT_COST),
        )
        if (uiState.errors[ProductEditField.REPLACEMENT_COST] == null) {
            Text(
                stringResource(R.string.product_replacement_cost_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = AppTheme.spacing.s16,
                    top = AppTheme.spacing.s4,
                ),
            )
        }
    }
}

/**
 * Las unidades de compra y venta del producto: una fila por conversión, que abre su hoja, y
 * `Agregar conversión`. Se edita aquí, con la misma hoja de la pantalla de unidades.
 */
@Composable
private fun ProductConversions(uiState: ProductEditUiState, onAction: (ProductEditAction) -> Unit) {
    val product = uiState.product ?: return
    Text(
        stringResource(R.string.product_units_buy_sell),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = AppTheme.spacing.s16).semantics { heading() },
    )
    Column {
        for (conversion in uiState.conversions) {
            key(conversion.id) {
                ConversionRow(
                    entry = ConversionEntry(conversion, product),
                    units = uiState.units,
                    withProduct = false,
                    inset = false,
                    onClick = { onAction(ProductEditAction.EditConversion(conversion.id)) },
                )
            }
        }
    }
    InlineTextButton(
        text = stringResource(R.string.unit_add_conversion_action),
        onClick = { onAction(ProductEditAction.NewConversion) },
    )
}

/** El nombre de cada uso del producto, el mismo en el formulario y en el detalle. */
internal fun usageLabel(usage: ProductUsage): Int = when (usage) {
    ProductUsage.SALE -> R.string.product_usage_sale
    ProductUsage.INPUT -> R.string.product_usage_input
    ProductUsage.BOTH -> R.string.product_usage_both
}

private fun stockModeLabel(mode: ProductStockMode): Int = when (mode) {
    ProductStockMode.TRACKED -> R.string.combo_stock_mode_tracked
    ProductStockMode.UNTRACKED -> R.string.combo_stock_mode_untracked
    ProductStockMode.COMPONENTS -> R.string.combo_stock_mode_components
}

/** Precio y costo lado a lado; con letra grande, uno bajo el otro para que las cifras quepan. */
@Composable
private fun PriceAndCost(uiState: ProductEditUiState, onAction: (ProductEditAction) -> Unit) {
    val stacked = LocalDensity.current.fontScale > STACK_FONT_SCALE
    val price: @Composable (Modifier) -> Unit = { modifier ->
        MoneyField(
            value = uiState.form.salePrice,
            onValueChange = { onAction(ProductEditAction.SalePriceChange(it)) },
            label = stringResource(R.string.product_sale_price),
            modifier = modifier,
            error = fieldError(uiState, ProductEditField.SALE_PRICE),
        )
    }
    if (uiState.form.stockMode == ProductStockMode.COMPONENTS) {
        price(Modifier.fillMaxWidth())
        return
    }
    val cost: @Composable (Modifier) -> Unit = { modifier ->
        if (uiState.costLocked) {
            LockedCost(uiState.averageCost, modifier)
        } else {
            MoneyField(
                value = uiState.form.cost,
                onValueChange = { onAction(ProductEditAction.CostChange(it)) },
                label = stringResource(R.string.term_cost),
                modifier = modifier,
                error = fieldError(uiState, ProductEditField.COST),
            )
        }
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            price(Modifier.fillMaxWidth())
            cost(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            price(Modifier.weight(1f))
            cost(Modifier.weight(1f))
        }
    }
}

/**
 * El costo de un producto con movimientos: lo lleva el libro, así que se ve sin poder escribirlo.
 * Es un costo guardado, y en modo discreto se oculta.
 */
@Composable
private fun LockedCost(cost: Money, modifier: Modifier) {
    Column(modifier.padding(top = AppTheme.spacing.s8)) {
        Text(
            stringResource(R.string.term_cost),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoneyText(cost, MoneySize.ROW, sensitive = true)
    }
}

/**
 * La ganancia de cada unidad mientras se escriben precio y costo. Con pérdida, la tarjeta lo dice
 * en el color de pérdida; sin costo, avisa que la ganancia real no se puede calcular. En modo
 * discreto se oculta. Con [suggestTarget], ofrece sugerir un precio con ese margen, también antes
 * de escribir el precio.
 */
@Composable
private fun ProfitCard(
    profit: ProfitPreview,
    suggestTarget: BasisPoints?,
    onAction: (ProductEditAction) -> Unit,
) {
    if (profit == ProfitPreview.NoPrice && suggestTarget == null) return
    AppCard(Modifier.fillMaxWidth()) {
        val formats = LocalAppFormats.current
        when (profit) {
            ProfitPreview.NoPrice -> Unit

            ProfitPreview.NoCost ->
                Text(
                    stringResource(R.string.product_no_cost_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            is ProfitPreview.Profit -> DiscreetValue(style = MaterialTheme.typography.bodyLarge) {
                val amount = rememberMoneyDisplay(profit.profit, formats.money)
                val margin =
                    when (val value = profit.margin) {
                        is Margin.Defined -> formats.percent.format(value.value)
                        Margin.NoData, Margin.OutOfRange -> NO_VALUE_TEXT
                    }
                Text(
                    formatAnnotated(
                        stringResource(R.string.product_profit_per_unit),
                        amount.text,
                        margin,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            is ProfitPreview.Loss -> DiscreetValue(style = MaterialTheme.typography.bodyLarge) {
                val amount = rememberMoneyDisplay(profit.loss, formats.money)
                Text(
                    formatAnnotated(stringResource(R.string.product_loss_per_sale), amount.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTheme.colors.loss,
                )
            }
        }
        suggestTarget?.let { target ->
            InlineTextButton(
                text =
                    stringResource(
                        R.string.product_suggest_with_margin,
                        formats.percent.format(target),
                    ),
                onClick = { onAction(ProductEditAction.OpenSuggestion) },
            )
        }
    }
}

/**
 * La sugerencia de precio: el margen objetivo, que se puede cambiar sin guardarlo, y el precio que
 * lo alcanza. `Usar este precio` lo pone como precio de venta y cierra la hoja; solo aparece con un
 * precio que usar.
 */
@Composable
private fun SuggestionSheet(draft: PriceSuggestionDraft, onAction: (ProductEditAction) -> Unit) {
    EditorContainer(
        asPanel = false,
        title = stringResource(R.string.product_suggest_price),
        onDismiss = { onAction(ProductEditAction.DismissSuggestion) },
        action = {
            if (draft.price != null) {
                PrimaryActionButton(
                    text = stringResource(R.string.product_use_price),
                    onClick = { onAction(ProductEditAction.UseSuggestedPrice) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        PriceSuggestionPanel(
            draft = draft,
            onTargetChange = { onAction(ProductEditAction.SuggestionTarget(it)) },
        )
    }
}

/** El botón fijo abajo, con el aviso de guardado encima si lo hay. */
@Composable
private fun SaveBar(
    uiState: ProductEditUiState,
    onAction: (ProductEditAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    // Al pie del formulario y a su mismo ancho, también en una ventana ancha.
    Column(
        modifier
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .keepsMessagesAbove()
            .navigationBarsPadding()
            .padding(horizontal = spacing.s16, vertical = spacing.s12),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        when (val notice = uiState.notice) {
            null -> Unit

            is ProductEditNotice.SaveFailed ->
                NoticeCard(stringResource(saveErrorText(notice.outOfSpace)), NoticeVariant.ERROR)

            ProductEditNotice.CurrencyUnconfirmed ->
                NoticeCard(
                    text = stringResource(R.string.region_required_body),
                    variant = NoticeVariant.WARNING,
                    actionLabel = stringResource(R.string.region_required_action),
                    onAction = { onAction(ProductEditAction.ConfirmCurrency) },
                )
        }
        PrimaryActionButton(
            text = stringResource(R.string.product_save),
            onClick = { onAction(ProductEditAction.Save) },
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            state = if (uiState.saving) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

@Composable
private fun CategorySheet(
    categories: List<Category>,
    onAction: (ProductEditAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val normalized = TextNormalizer.normalize(query)
    val shown = categories.filter { it.details.normalizedName.contains(normalized) }
    val none = stringResource(R.string.term_no_category)
    SelectionSheet(
        title = stringResource(R.string.form_category),
        items = listOf<Category?>(null) + shown,
        key = { it?.id ?: NO_CATEGORY_KEY },
        query = query,
        onQueryChange = { query = it },
        onDismissRequest = onDismiss,
        searchHint = stringResource(R.string.category_search_hint),
        itemContent = { category ->
            SettingsRow(
                title = category?.details?.name ?: none,
                action =
                    SettingsRowAction.Navigation {
                        onAction(ProductEditAction.CategoryChange(category?.id))
                        onDismiss()
                    },
            )
        },
        onCreate = { name ->
            onAction(ProductEditAction.CreateCategory(name))
            onDismiss()
        },
    )
}

/** El error de [field], si tiene uno, con su texto. */
@Composable
private fun fieldError(uiState: ProductEditUiState, field: ProductEditField): String? =
    uiState.errors[field]?.let { problemText(it, field) }

@Composable
private fun FormSkeleton() {
    Column(
        Modifier.padding(AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        repeat(SKELETON_FIELDS) {
            SkeletonBlock(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = AppTheme.spacing.s4),
            )
        }
    }
}

private const val NO_CATEGORY_KEY = "none"
private const val SKELETON_FIELDS = 6

/** Desde esta escala de la letra, precio y costo no caben lado a lado. */
private const val STACK_FONT_SCALE = 1.3f

// ---------------------------------------------------------------- vistas previas

private val PREVIEW_UNITS =
    listOf(
        MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null),
        MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null),
    )

private val PREVIEW_CATEGORIES =
    listOf(
        Category(
            id = 1L,
            details = CategoryDetails("Granos", "granos", null),
            createdAt = Instant.fromEpochMilliseconds(0L),
            updatedAt = Instant.fromEpochMilliseconds(0L),
        ),
    )

private fun previewState(
    profit: ProfitPreview,
    form: ProductEditForm =
        ProductEditForm(
            name = "Arroz 1 kg",
            categoryId = 1L,
            unitId = 1L,
            salePrice = "5000",
            cost = "3800",
            initialStock = "24",
            minStock = "5",
        ),
) = ProductEditUiState(
    isNew = true,
    form = form,
    categories = PREVIEW_CATEGORIES,
    units = PREVIEW_UNITS,
    profit = profit,
)

@ThemePreviews
@Composable
private fun ProductEditPreview() {
    PreviewTheme {
        ProductEditScreen(
            previewState(ProfitPreview.Profit(Money(1_200L), Margin.Defined(BasisPoints(2_400L)))),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun ProductEditLossPreview() {
    PreviewTheme {
        ProductEditScreen(
            previewState(ProfitPreview.Loss(Money(200L))).copy(
                notice = ProductEditNotice.SaveFailed(outOfSpace = true),
                errors = mapOf(ProductEditField.NAME to FieldProblem.Duplicate("Arroz 1 kg")),
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun ProductEditLockedPreview() {
    PreviewTheme(discreet = true) {
        ProductEditScreen(
            previewState(ProfitPreview.NoCost).copy(
                isNew = false,
                unitLocked = true,
                costLocked = true,
                averageCost = Money(3_800L),
                notice = ProductEditNotice.CurrencyUnconfirmed,
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun ProductEditEmptyPreview() {
    PreviewTheme {
        ProductEditScreen(previewState(ProfitPreview.NoPrice, ProductEditForm(unitId = 1L)), {})
    }
}

/** Un producto con campos propios de cada tipo, uno con error, y etiquetas: se abre solo. */
@ThemePreviews
@Composable
private fun ProductEditExtrasPreview() {
    PreviewTheme {
        ProductEditScreen(
            previewState(ProfitPreview.NoCost).copy(
                extras =
                    ProductExtrasForm(
                        fields =
                            listOf(
                                CustomField(1L, "Marca", CustomFieldType.TEXT, 0L, false, 3),
                                CustomField(2L, "Garantía", CustomFieldType.NUMBER, 1L, false, 1),
                                CustomField(3L, "Vence", CustomFieldType.DATE, 2L, false, 1),
                                CustomField(
                                    4L,
                                    PreviewSamples.LONG_NAME,
                                    CustomFieldType.BOOLEAN,
                                    3L,
                                    false,
                                    1,
                                ),
                            ),
                        tags = listOf(ProductTag(1L, "Temporada", 4), ProductTag(2L, "Nuevo", 0)),
                        values = mapOf(1L to "Del valle", 2L to "12,5", 3L to "2026-12-31"),
                        tagIds = setOf(1L),
                        errors = mapOf(2L to CustomValueIssue.NOT_A_NUMBER),
                        calendar =
                            FieldCalendar(
                                BusinessDate.parse("2026-11-13") ?: return@PreviewTheme,
                                DayOfWeek.MONDAY,
                            ),
                    ),
            ),
            {},
        )
    }
}
