package io.github.yesidrobayo.shopledger.ui.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.HistoryLockedField
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductPhotoChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogName
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductPricing
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceOrigin
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceResult
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.GenerateOwnBarcodesResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ProductListPrice
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceResult
import io.github.yesidrobayo.shopledger.ui.ReadOutcome
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
import io.github.yesidrobayo.shopledger.ui.payment.ImageProblem
import io.github.yesidrobayo.shopledger.ui.pricing.ListPriceEditorModel
import io.github.yesidrobayo.shopledger.ui.pricing.ListProductRow
import io.github.yesidrobayo.shopledger.ui.pricing.PriceListNumberFormat
import io.github.yesidrobayo.shopledger.ui.unit.ConversionEditorModel
import io.github.yesidrobayo.shopledger.ui.unit.UnitEditorModel
import io.github.yesidrobayo.shopledger.ui.unit.UnitNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * El formulario para crear o editar un producto.
 *
 * Lo escrito se guarda tal cual mientras se escribe, y se lee como número solo para la tarjeta de
 * ganancia y al guardar, con los símbolos del formato regional de [numbers]. Un error de un campo
 * aparece al salir del nombre o al intentar guardar, nunca mientras se escribe, y lo escrito nunca
 * se pierde por un error. Guardar corre una sola vez a la vez.
 *
 * @param productId el producto que se edita, o `null` para crear uno.
 * @param prefillName el nombre con que empieza un producto nuevo, el de una búsqueda sin resultados.
 * @param prefillBarcode el código con que empieza un producto nuevo, el que leyó el escáner.
 * @param generateBarcode guarda el código propio de un producto, en su propia transacción.
 * @param unitNames los nombres de las unidades en el idioma de la interfaz, para no crear una con el
 *   nombre de otra.
 * @param listPrices los precios del producto en las demás listas, que se editan aquí con la hoja de
 *   la pantalla de listas, cada uno en su propia transacción.
 * @param setListPrice deja o quita el precio propio del producto en una lista.
 * @param startAsCombo un producto nuevo empieza con `Por componentes` elegido.
 */
class ProductEditViewModel(
    private val useCases: ProductEditOperations,
    private val numbers: ProductNumberFormat,
    private val productId: Long?,
    prefillName: String?,
    unitNames: UnitNames,
    private val listPrices: (Long) -> Flow<List<ProductListPrice>> = { flowOf(emptyList()) },
    setListPrice: suspend (Long, Long, Money?) -> SetListPriceResult = { _, _, _ ->
        SetListPriceResult.ListNotFound
    },
    private val referenceCurrency: () -> Flow<ReferenceCurrency?> = { flowOf(null) },
    prefillBarcode: String? = null,
    private val generateBarcode: suspend (Long) -> GenerateOwnBarcodesResult = {
        GenerateOwnBarcodesResult.Done(emptyMap(), emptyList(), listOf(it))
    },
    startAsCombo: Boolean = false,
) : ViewModel() {
    private val state =
        MutableStateFlow(
            ProductEditUiState(
                isNew = productId == null,
                loading = productId != null,
                form = ProductEditForm(
                    name = prefillName.orEmpty(),
                    barcode = prefillBarcode.orEmpty(),
                    stockMode = if (startAsCombo) {
                        ProductStockMode.COMPONENTS
                    } else {
                        ProductStockMode.TRACKED
                    },
                ),
            ),
        )
    val uiState: StateFlow<ProductEditUiState> = state.asStateFlow()

    /** La hoja para crear una unidad sin salir del formulario. Al guardarla, queda elegida. */
    val unitEditor =
        UnitEditorModel(
            scope = viewModelScope,
            names = unitNames,
            activeUnits = { state.value.units },
            save = { _, draft -> useCases.saveUnit(draft) },
            onSaved = { id -> onAction(ProductEditAction.UnitChange(id)) },
        )

    /** La hoja de una conversión de este producto. */
    val conversionEditor =
        ConversionEditorModel(
            scope = viewModelScope,
            numbers = numbers,
            save = useCases::saveConversion,
            delete = useCases::deleteConversion,
            // La lista del producto ya muestra el cambio.
            onSaved = {},
        )

    /** La hoja del precio del producto en otra lista. */
    val listPriceEditor =
        ListPriceEditorModel(
            scope = viewModelScope,
            numbers = PriceListNumberFormat(
                numbers.percentSymbols,
                numbers.moneySymbols,
                numbers.currencyDecimals,
            ),
            formatPrice = {
                fixedPointInputText(it.minor, numbers.currencyDecimals, numbers.moneySymbols)
            },
            set = setListPrice,
            // La lista del producto ya muestra el cambio.
            onSaved = {},
        )

    /**
     * Categorías, unidades, precios, el producto que se edita y sus conversiones. Una falla de lectura
     * no cierra el formulario: se dice con `Reintentar`, y lo escrito sigue en el estado.
     */
    private val reads = ScreenReads()

    init {
        viewModelScope.launch {
            reads.guard { useCases.choices() }.collect { choices ->
                state.update { current ->
                    // Un producto nuevo empieza en la unidad de conteo, la primera de las iniciales.
                    val unitId = current.form.unitId ?: choices.units.firstOrNull()?.id
                    current.copy(
                        categories = choices.categories,
                        units = choices.units,
                        form = current.form.copy(unitId = unitId),
                    )
                }
            }
        }
        viewModelScope.launch {
            // Sin moneda de referencia, el campo no se muestra y el precio guardado no se toca.
            reads.guard(referenceCurrency).collect { reference ->
                state.update { current ->
                    val product = current.product
                    val filled = if (product != null && reference != null &&
                        current.form.referencePrice.isEmpty() &&
                        current.reference == null
                    ) {
                        referenceText(product.details.referencePrice, reference)
                    } else {
                        current.form.referencePrice
                    }
                    current.copy(
                        reference = reference,
                        form = current.form.copy(referencePrice = filled),
                    )
                }
            }
        }
        viewModelScope.launch {
            reads.guard { useCases.pricing() }.collect { context ->
                state.update { it.copy(pricing = context).withProfit() }
            }
        }
        if (productId != null) {
            viewModelScope.launch { showProduct(productId) }
            viewModelScope.launch {
                reads.guard { useCases.conversions(productId) }.collect { list ->
                    state.update { it.copy(conversions = list) }
                }
            }
            viewModelScope.launch {
                reads.guard { listPrices(productId) }.collect { list ->
                    state.update { it.copy(listPrices = list) }
                }
            }
        }
        viewModelScope.launch {
            reads.failed.collect { failed ->
                state.update { it.copy(readFailed = failed, loading = it.loading && !failed) }
            }
        }
    }

    fun onAction(action: ProductEditAction) {
        when (action) {
            ProductEditAction.Retry -> {
                // Un producto ya cargado no se vuelve a cargar, para no pisar lo que se cambió.
                val reload = productId != null && state.value.product == null
                state.update { it.copy(loading = reload) }
                reads.retry()
                if (reload && productId != null) viewModelScope.launch { showProduct(productId) }
            }

            is ProductEditAction.NameChange -> edit(ProductEditField.NAME) {
                copy(name = action.name)
            }

            ProductEditAction.NameLeft -> checkName()

            is ProductEditAction.CategoryChange -> edit(null) {
                copy(categoryId = action.categoryId)
            }

            is ProductEditAction.CreateCategory -> createCategory(action.name)

            is ProductEditAction.UnitChange -> edit(null) { copy(unitId = action.unitId) }

            is ProductEditAction.StockModeChange ->
                state.update { current ->
                    if (current.stockModeLocked) return@update current
                    current.copy(
                        form = current.form.copy(stockMode = action.mode),
                        errors =
                            current.errors - ProductEditField.INITIAL_STOCK -
                                ProductEditField.MIN_STOCK - ProductEditField.COST,
                        dirty = true,
                        notice = null,
                    ).withProfit()
                }

            ProductEditAction.NewUnit -> unitEditor.openNew()

            is ProductEditAction.EditConversion ->
                state.value.conversions
                    .firstOrNull { it.id == action.conversionId }
                    ?.let(conversionEditor::openEdit)

            ProductEditAction.NewConversion -> productId?.let(conversionEditor::openNew)

            is ProductEditAction.EditListPrice -> openListPrice(action.listId)

            // La pantalla abre la de unidades; aquí no cambia nada.
            ProductEditAction.ManageUnits -> Unit

            ProductEditAction.OpenVariants -> Unit

            ProductEditAction.ScanBarcode -> Unit

            ProductEditAction.GenerateBarcode -> generateOwnBarcode()

            ProductEditAction.OpenRecipe -> Unit

            is ProductEditAction.SalePriceChange ->
                edit(ProductEditField.SALE_PRICE) { copy(salePrice = action.text) }

            is ProductEditAction.CostChange -> edit(ProductEditField.COST) {
                copy(cost = action.text)
            }

            is ProductEditAction.InitialStockChange ->
                edit(ProductEditField.INITIAL_STOCK) { copy(initialStock = action.text) }

            is ProductEditAction.MinStockChange ->
                edit(ProductEditField.MIN_STOCK) { copy(minStock = action.text) }

            is ProductEditAction.BarcodeChange ->
                edit(ProductEditField.BARCODE) { copy(barcode = action.text) }

            is ProductEditAction.ReferencePriceChange ->
                edit(ProductEditField.REFERENCE_PRICE) { copy(referencePrice = action.text) }

            is ProductEditAction.ReplacementCostChange ->
                edit(ProductEditField.REPLACEMENT_COST) { copy(replacementCost = action.text) }

            ProductEditAction.Save -> save()

            ProductEditAction.Close ->
                state.update {
                    if (it.dirty) it.copy(confirmingDiscard = true) else it.copy(closed = true)
                }

            ProductEditAction.KeepEditing -> state.update { it.copy(confirmingDiscard = false) }

            ProductEditAction.Discard -> state.update {
                it.copy(confirmingDiscard = false, closed = true)
            }

            // La pantalla abre la configuración regional; aquí no cambia nada.
            ProductEditAction.ConfirmCurrency -> Unit

            // Sin de dónde sugerir, la tarjeta no ofrece la sugerencia.
            ProductEditAction.OpenSuggestion ->
                state.update { current ->
                    val pricing =
                        current.draftPricing()?.takeIf { it.canSuggest } ?: return@update current
                    current.copy(
                        suggestion = PriceSuggestionDraft.open(pricing, numbers.percentSymbols),
                    )
                }

            is ProductEditAction.SuggestionTarget ->
                state.update { current ->
                    val pricing = current.draftPricing() ?: return@update current
                    val outcome =
                        PriceSuggestionDraft.read(action.text, pricing, numbers.percentSymbols)
                    current.copy(suggestion = PriceSuggestionDraft(action.text, outcome))
                }

            ProductEditAction.UseSuggestedPrice -> useSuggestedPrice()

            ProductEditAction.DismissSuggestion -> state.update { it.copy(suggestion = null) }

            // El selector lo abre la ruta: aquí solo llega lo elegido.
            ProductEditAction.ChoosePhoto -> Unit

            is ProductEditAction.PickPhoto -> importPhoto(action.source)

            ProductEditAction.RemovePhoto -> {
                state.update { it.copy(photoProblem = null) }
                edit(null) { copy(photo = EditPhoto.None) }
            }
        }
    }

    /**
     * Trae la foto elegida. Mientras se recodifica no se elige otra; si no se puede leer, la que
     * había sigue igual y se dice por qué.
     */
    private fun importPhoto(source: String) {
        if (state.value.importingPhoto) return
        state.update { it.copy(importingPhoto = true, photoProblem = null) }
        viewModelScope.launch {
            val result = useCases.importPhoto(source)
            state.update { it.copy(importingPhoto = false) }
            when (result) {
                is ImageImport.Imported ->
                    edit(null) { copy(photo = EditPhoto.Picked(result.image)) }

                ImageImport.Unreadable ->
                    state.update { it.copy(photoProblem = ImageProblem.UNREADABLE) }

                ImageImport.OutOfSpace ->
                    state.update { it.copy(photoProblem = ImageProblem.OUT_OF_SPACE) }
            }
        }
    }

    /**
     * Abre la hoja del precio en [listId], solo en una fija vigente: una relativa se calcula. Sin el
     * producto guardado o sin lo que mide el margen, no abre nada.
     */
    private fun openListPrice(listId: Long) {
        val current = state.value
        val entry = current.listPrices.firstOrNull { it.list.id == listId } ?: return
        if (entry.list.pricing is PriceListPricing.Relative) return
        val product = current.product ?: return
        val context = current.pricing ?: return
        val priced = entry.result as? ListPriceResult.Priced
        val pricing = ProductPricing.of(product, context)
        val row = ListProductRow(
            productId = product.id,
            name = product.details.name,
            salePrice = product.salePrice,
            price = priced?.price,
            origin = priced?.origin,
            margin = priced?.price?.let(pricing::marginOf) ?: Margin.NoData,
            hasCost = pricing.cost != null,
        )
        listPriceEditor.open(listId, row, priced?.origin == ListPriceOrigin.OWN, pricing)
    }

    /** Pone el precio sugerido como precio de venta, como si se hubiera escrito, y cierra la hoja. */
    private fun useSuggestedPrice() {
        val price = state.value.suggestion?.price ?: return
        state.update { it.copy(suggestion = null) }
        edit(ProductEditField.SALE_PRICE) { copy(salePrice = moneyText(price)) }
    }

    /**
     * Cómo se mide el margen y se sugiere el precio de lo que se escribe, o `null` mientras no se
     * leen los ajustes del negocio. Un producto por componentes no tiene costo propio aquí. Con el
     * costo en manos del libro, el costo es el del producto guardado, sin redondear; si no, el que
     * se escribe.
     */
    private fun ProductEditUiState.draftPricing(): ProductPricing? {
        val context = pricing ?: return null
        if (form.stockMode == ProductStockMode.COMPONENTS) return null
        val saved = product
        val cost = if (costLocked && saved != null) {
            ProductPricing.of(saved, context).cost
        } else {
            money(form.cost) {}.takeIf { it > Money.ZERO }?.let(ExactUnitCost::perUnit)
        }
        return ProductPricing.draft(
            context = context,
            cost = cost,
            // La reposición que se está escribiendo, para sugerir con lo que la persona ve.
            replacementCost = money(form.replacementCost) {},
            productTarget = saved?.details?.targetMargin,
            categoryId = form.categoryId,
            taxRateId = saved?.details?.taxRateId,
        )
    }

    private suspend fun showProduct(id: Long) {
        val editing =
            when (val read = reads.once { useCases.load(id) }) {
                is ReadOutcome.Read -> read.value
                ReadOutcome.Failed -> return
            }
        if (editing == null) {
            state.update { it.copy(loading = false, notFound = true) }
            return
        }
        val product = editing.product
        val details = product.details
        val cost =
            if (details.stockMode ==
                ProductStockMode.UNTRACKED
            ) {
                details.fixedUnitCost
            } else {
                product.initialCost
            }
        val catalogCost = MarginCalculator
            .catalogUnitCost(product.onHand, product.stockValue, product.averageCost)
            .valueOrNull() ?: product.averageCost
        state.update { current ->
            val form = ProductEditForm(
                name = details.name,
                categoryId = details.categoryId,
                unitId = details.baseUnitId,
                stockMode = details.stockMode,
                salePrice = moneyText(product.salePrice),
                cost = moneyText(cost),
                minStock = quantityText(details.minStock),
                barcode = details.barcode.orEmpty(),
                replacementCost = moneyText(product.replacementCost),
                photo = details.photoMediaId?.let(EditPhoto::Saved) ?: EditPhoto.None,
                referencePrice =
                    current.reference
                        ?.let { referenceText(details.referencePrice, it) }
                        .orEmpty(),
            )
            current.copy(
                loading = false,
                form = form,
                unitLocked = HistoryLockedField.BASE_UNIT in editing.lockedFields,
                stockModeLocked = HistoryLockedField.STOCK_MODE in editing.lockedFields,
                product = product,
                costLocked = editing.costLocked,
                averageCost = catalogCost,
            ).withProfit()
        }
    }

    /**
     * Genera y guarda el código propio del producto que se edita. Queda en el campo como si se
     * hubiera escrito, sin marcar cambios: ya está guardado. Un choque lo dice en el campo.
     */
    private fun generateOwnBarcode() {
        val id = productId ?: return
        if (state.value.form.barcode.isNotBlank()) return
        viewModelScope.launch {
            when (val result = generateBarcode(id)) {
                is GenerateOwnBarcodesResult.Done -> {
                    val code = result.generated[id]
                    val clash = result.clashes.firstOrNull { it.productId == id }
                    state.update { current ->
                        when {
                            code != null ->
                                current.copy(
                                    form = current.form.copy(barcode = code),
                                    errors = current.errors - ProductEditField.BARCODE,
                                )

                            clash != null ->
                                current.copy(
                                    errors = current.errors +
                                        (
                                            ProductEditField.BARCODE to
                                                FieldProblem.Duplicate(clash.existingName)
                                            ),
                                )

                            else -> current
                        }
                    }
                }

                is GenerateOwnBarcodesResult.StorageFailed ->
                    state.update {
                        it.copy(
                            notice = ProductEditNotice.SaveFailed(result.failure.isOutOfSpace),
                        )
                    }
            }
        }
    }

    /** Cambia el formulario con [change] y borra el error de [field], si lo tenía. */
    private fun edit(field: ProductEditField?, change: ProductEditForm.() -> ProductEditForm) {
        state.update { current ->
            current.copy(
                form = current.form.change(),
                errors = if (field == null) current.errors else current.errors - field,
                dirty = true,
                notice = null,
            ).withProfit()
        }
    }

    private fun checkName() {
        val name = state.value.form.name
        viewModelScope.launch {
            val problem = nameProblem(name) ?: useCases.nameClash(name, productId)?.let {
                FieldProblem.Duplicate(it)
            }
            state.update { current ->
                // Si siguió escribiendo, el error ya no corresponde a lo que hay.
                if (current.form.name != name) return@update current
                val errors = current.errors - ProductEditField.NAME
                current.copy(
                    errors =
                        problem?.let { errors + (ProductEditField.NAME to it) } ?: errors,
                )
            }
        }
    }

    private fun createCategory(name: String) {
        viewModelScope.launch {
            when (val result = useCases.createCategory(name)) {
                is SaveCategoryResult.Saved -> edit(null) { copy(categoryId = result.categoryId) }

                // Ya existía: se elige la que tiene ese nombre.
                is SaveCategoryResult.DuplicateName -> {
                    val existing = state.value.categories.firstOrNull {
                        it.details.name ==
                            result.existingName
                    }
                    if (existing != null) edit(null) { copy(categoryId = existing.id) }
                }

                is SaveCategoryResult.Invalid, SaveCategoryResult.NotFound -> Unit

                is SaveCategoryResult.StorageFailed ->
                    state.update {
                        it.copy(notice = ProductEditNotice.SaveFailed(result.failure.isOutOfSpace))
                    }
            }
        }
    }

    private fun save() {
        val current = state.value
        if (current.saving || current.loading) return
        val read = readForm(current)
        if (read.errors.isNotEmpty()) {
            state.update { it.copy(errors = read.errors, notice = null) }
            return
        }
        state.update { it.copy(saving = true, notice = null) }
        viewModelScope.launch {
            val form = requireNotNull(read.form)
            val result =
                if (productId == null) {
                    useCases.create(form, read.initialStock)
                } else {
                    useCases.edit(productId, form)
                }
            state.update { it.copy(saving = false).withResult(result) }
        }
    }

    private fun ProductEditUiState.withResult(result: SaveProductResult): ProductEditUiState =
        when (result) {
            // Un combo recién creado, o un producto que pasa a combo, sigue con su receta.
            is SaveProductResult.Saved -> copy(
                savedProductId = result.productId,
                dirty = false,
                opensRecipe = form.stockMode == ProductStockMode.COMPONENTS &&
                    product?.details?.stockMode != ProductStockMode.COMPONENTS,
            )

            is SaveProductResult.Invalid -> copy(errors = errorsOf(result.issues))

            is SaveProductResult.DuplicateName ->
                copy(
                    errors =
                        errors +
                            (ProductEditField.NAME to FieldProblem.Duplicate(result.existingName)),
                )

            is SaveProductResult.DuplicateBarcode ->
                copy(
                    errors =
                        errors +
                            (
                                ProductEditField.BARCODE to FieldProblem.Duplicate(
                                    result.existingName,
                                )
                                ),
                )

            is SaveProductResult.AboveLimit ->
                copy(errors = errors + (ProductEditField.INITIAL_STOCK to FieldProblem.TooHigh))

            SaveProductResult.CurrencyUnconfirmed -> copy(
                notice = ProductEditNotice.CurrencyUnconfirmed,
            )

            is SaveProductResult.StorageFailed ->
                copy(notice = ProductEditNotice.SaveFailed(result.failure.isOutOfSpace))

            // Lo que el formulario ya impide o lo que cambió por debajo: no se guardó, y lo escrito
            // sigue aquí para intentarlo otra vez.
            is SaveProductResult.MissingReference, is SaveProductResult.LockedByHistory,
            SaveProductResult.CostLockedByMovements, SaveProductResult.ArchivedPriceLocked,
            SaveProductResult.NotFound, SaveProductResult.UnknownTimeZone,
            -> copy(notice = ProductEditNotice.SaveFailed(outOfSpace = false))
        }

    /** Lo escrito, leído como un producto, o los errores de los campos que no se pueden leer. */
    private class ReadForm(
        val form: ProductForm?,
        val initialStock: QuantityMilli,
        val errors: Map<ProductEditField, FieldProblem>,
    )

    private fun readForm(current: ProductEditUiState): ReadForm {
        val text = current.form
        val errors = mutableMapOf<ProductEditField, FieldProblem>()
        nameProblem(text.name)?.let { errors[ProductEditField.NAME] = it }
        val price = money(text.salePrice) { errors[ProductEditField.SALE_PRICE] = it }
        val cost = money(text.cost) { errors[ProductEditField.COST] = it }
        // Sin stock propio no hay stock que contar ni que vigilar: esos campos ni se muestran.
        val tracks = current.tracksStock
        val initial =
            if (!tracks) {
                QuantityMilli.ZERO
            } else {
                quantity(text.initialStock, Limit.LINE_QUANTITY_MILLI) {
                    errors[ProductEditField.INITIAL_STOCK] = it
                }
            }
        val minimum =
            if (!tracks) {
                QuantityMilli.ZERO
            } else {
                quantity(text.minStock, Limit.ON_HAND_MILLI) {
                    errors[ProductEditField.MIN_STOCK] = it
                }
            }
        val unit = current.unit
        if (tracks && unit != null && !unit.allowsFraction) {
            if (!initial.isWholeUnits) {
                errors[ProductEditField.INITIAL_STOCK] =
                    FieldProblem.FractionNotAllowed
            }
            if (!minimum.isWholeUnits) {
                errors[ProductEditField.MIN_STOCK] =
                    FieldProblem.FractionNotAllowed
            }
        }
        // Sin stock propio el producto no se compra: no lleva reposición, y el campo no se muestra.
        // Sin tocar el campo se manda `null`, para no pisar lo que una compra haya puesto mientras
        // el formulario estaba abierto.
        val untouched = current.product?.let {
            text.replacementCost == moneyText(it.replacementCost)
        }
        val replacement =
            when {
                !tracks -> Money.ZERO

                untouched == true -> null

                else -> money(text.replacementCost) {
                    errors[ProductEditField.REPLACEMENT_COST] = it
                }
            }
        val reference = referencePriceOf(current, errors)
        val barcode = CatalogText.barcode(text.barcode)
        if (barcode != null && CatalogText.length(barcode) > CatalogText.BARCODE_MAX) {
            errors[ProductEditField.BARCODE] = FieldProblem.TooLong(CatalogText.BARCODE_MAX)
        }
        // El nombre repetido que ya se vio al salir del campo se conserva hasta que cambie.
        current.errors[ProductEditField.NAME]?.let {
            if (it is FieldProblem.Duplicate) errors.putIfAbsent(ProductEditField.NAME, it)
        }
        if (errors.isNotEmpty()) return ReadForm(null, QuantityMilli.ZERO, errors)
        val form = ProductForm(
            name = text.name,
            categoryId = text.categoryId,
            baseUnitId = text.unitId,
            stockMode = text.stockMode,
            salePrice = price,
            // Con el costo en el libro, se guarda el inicial tal como estaba. Un producto hecho de
            // componentes no lleva costo propio: sale de sus componentes.
            cost =
                when {
                    text.stockMode == ProductStockMode.COMPONENTS -> Money.ZERO
                    current.costLocked -> initialCostOf(current)
                    else -> cost
                },
            minStock = minimum,
            barcode = barcode,
            replacementCost = replacement,
            referencePrice = reference,
            photo = photoChangeOf(current),
        )
        return ReadForm(form, if (current.isNew) initial else QuantityMilli.ZERO, emptyMap())
    }

    private fun initialCostOf(current: ProductEditUiState): Money = money(current.form.cost) {}

    /** Qué pasa con la foto al guardar: la elegida, quitar la que tenía o dejarla como está. */
    private fun photoChangeOf(current: ProductEditUiState): ProductPhotoChange =
        when (val photo = current.form.photo) {
            is EditPhoto.Picked -> ProductPhotoChange.Use(photo.image)

            is EditPhoto.Saved -> ProductPhotoChange.Keep

            EditPhoto.None ->
                if (current.product?.details?.photoMediaId != null) {
                    ProductPhotoChange.Remove
                } else {
                    ProductPhotoChange.Keep
                }
        }

    /**
     * El precio de referencia escrito, en la moneda de referencia: `null` lo deja como está, sin
     * moneda elegida o sin tocar el campo, y en blanco es cero, sin dato.
     */
    private fun referencePriceOf(
        current: ProductEditUiState,
        errors: MutableMap<ProductEditField, FieldProblem>,
    ): Money? {
        val reference = current.reference ?: return null
        val text = current.form.referencePrice
        val saved = current.product?.details?.referencePrice
        if (current.product != null && text == referenceText(saved, reference)) return null
        val read = NumberInputParser.money(
            text,
            numbers.moneySymbols,
            reference.decimals,
            Limit.UNIT_MONEY,
        )
        return when (read) {
            is NumberInput.Value -> read.value

            NumberInput.Blank -> Money.ZERO

            NumberInput.Malformed -> {
                errors[ProductEditField.REFERENCE_PRICE] = FieldProblem.NotANumber
                null
            }

            is NumberInput.TooManyDecimals -> {
                errors[ProductEditField.REFERENCE_PRICE] =
                    FieldProblem.TooManyDecimals(reference.decimals)
                null
            }

            is NumberInput.TooLarge -> {
                errors[ProductEditField.REFERENCE_PRICE] = FieldProblem.TooHigh
                null
            }
        }
    }

    /** [amount] como se escribe en la moneda [reference], o vacío sin dato. */
    private fun referenceText(amount: Money?, reference: ReferenceCurrency): String =
        if (amount == null || amount == Money.ZERO) {
            ""
        } else {
            decimalText(amount.minor, reference.decimals, numbers.moneySymbols.decimalSeparator)
        }

    /**
     * La tarjeta de ganancia con lo escrito: la ganancia y el margen sin el impuesto que el precio
     * lleva dentro, y el margen contra el costo sin redondear, como en el detalle. Mientras no se
     * leen los ajustes del negocio, mide sobre el precio escrito.
     */
    private fun ProductEditUiState.withProfit(): ProductEditUiState {
        val price = money(form.salePrice) {}
        val cost = if (costLocked) averageCost else money(form.cost) {}
        val pricing = draftPricing()
        val net = pricing?.netOf(price) ?: price
        val profit = when {
            price <= Money.ZERO -> ProfitPreview.NoPrice

            cost <= Money.ZERO -> ProfitPreview.NoCost

            cost > net -> ProfitPreview.Loss(cost - net)

            else ->
                ProfitPreview.Profit(
                    net - cost,
                    pricing?.marginOf(price) ?: MarginCalculator.margin(net, cost),
                )
        }
        return copy(
            profit = profit,
            suggestTarget = pricing?.takeIf { it.canSuggest }?.targetMargin,
        )
    }

    private fun nameProblem(name: String): FieldProblem? = when {
        CatalogText.name(
            name,
            CatalogText.PRODUCT_NAME_MAX,
        ) == CatalogName.Empty -> FieldProblem.Empty

        CatalogText.length(name.trim()) > CatalogText.PRODUCT_NAME_MAX ->
            FieldProblem.TooLong(CatalogText.PRODUCT_NAME_MAX)

        else -> null
    }

    /** Un monto por unidad entera; en blanco es cero. Un problema se informa por [problem]. */
    private fun money(text: String, problem: (FieldProblem) -> Unit): Money = when (
        val read = NumberInputParser.money(
            text,
            numbers.moneySymbols,
            numbers.currencyDecimals,
            Limit.UNIT_MONEY,
        )
    ) {
        is NumberInput.Value -> read.value

        NumberInput.Blank -> Money.ZERO

        else -> {
            problem(problemOf(read))
            Money.ZERO
        }
    }

    /** Una cantidad en milésimas hasta [limit]; en blanco es cero. */
    private fun quantity(
        text: String,
        limit: Limit,
        problem: (FieldProblem) -> Unit,
    ): QuantityMilli =
        when (val read = NumberInputParser.quantity(text, numbers.quantitySymbols, limit)) {
            is NumberInput.Value -> read.value

            NumberInput.Blank -> QuantityMilli.ZERO

            else -> {
                problem(problemOf(read))
                QuantityMilli.ZERO
            }
        }

    private fun problemOf(read: NumberInput<*>): FieldProblem = when (read) {
        is NumberInput.TooManyDecimals -> FieldProblem.TooManyDecimals(read.allowed)
        is NumberInput.TooLarge -> FieldProblem.TooHigh
        is NumberInput.Value, NumberInput.Blank, NumberInput.Malformed -> FieldProblem.NotANumber
    }

    private fun errorsOf(issues: Set<ProductFormIssue>): Map<ProductEditField, FieldProblem> =
        issues.associate { issue ->
            when (issue) {
                ProductFormIssue.NAME_EMPTY -> ProductEditField.NAME to FieldProblem.Empty

                ProductFormIssue.NAME_TOO_LONG ->
                    ProductEditField.NAME to FieldProblem.TooLong(CatalogText.PRODUCT_NAME_MAX)

                ProductFormIssue.SALE_PRICE_OUT_OF_RANGE ->
                    ProductEditField.SALE_PRICE to
                        FieldProblem.TooHigh

                ProductFormIssue.COST_OUT_OF_RANGE, ProductFormIssue.COST_NOT_APPLICABLE ->
                    ProductEditField.COST to FieldProblem.TooHigh

                ProductFormIssue.INITIAL_STOCK_OUT_OF_RANGE,
                ProductFormIssue.STOCK_NOT_APPLICABLE,
                ->
                    ProductEditField.INITIAL_STOCK to FieldProblem.TooHigh

                ProductFormIssue.INITIAL_STOCK_FRACTION_NOT_ALLOWED ->
                    ProductEditField.INITIAL_STOCK to FieldProblem.FractionNotAllowed

                ProductFormIssue.MIN_STOCK_OUT_OF_RANGE ->
                    ProductEditField.MIN_STOCK to
                        FieldProblem.TooHigh

                ProductFormIssue.MIN_STOCK_FRACTION_NOT_ALLOWED ->
                    ProductEditField.MIN_STOCK to FieldProblem.FractionNotAllowed

                ProductFormIssue.BARCODE_TOO_LONG ->
                    ProductEditField.BARCODE to FieldProblem.TooLong(CatalogText.BARCODE_MAX)

                ProductFormIssue.REPLACEMENT_COST_OUT_OF_RANGE,
                ProductFormIssue.REPLACEMENT_COST_NOT_APPLICABLE,
                ->
                    ProductEditField.REPLACEMENT_COST to FieldProblem.TooHigh

                ProductFormIssue.REFERENCE_PRICE_OUT_OF_RANGE ->
                    ProductEditField.REFERENCE_PRICE to FieldProblem.TooHigh
            }
        }

    /** Un monto como texto editable: cifras y el separador decimal, sin ceros de más. */
    private fun moneyText(amount: Money): String = if (amount ==
        Money.ZERO
    ) {
        ""
    } else {
        decimalText(
            amount.minor,
            numbers.currencyDecimals,
            numbers.moneySymbols.decimalSeparator,
        )
    }

    private fun quantityText(quantity: QuantityMilli): String =
        if (quantity == QuantityMilli.ZERO) {
            ""
        } else {
            decimalText(quantity.milli, QUANTITY_DECIMALS, numbers.quantitySymbols.decimalSeparator)
        }

    private companion object {
        const val QUANTITY_DECIMALS = 3
        const val RADIX = 10L

        fun decimalText(value: Long, decimals: Int, separator: Char): String {
            var scale = 1L
            repeat(decimals) { scale *= RADIX }
            val whole = value / scale
            val fraction = (value % scale).toString().padStart(decimals, '0').trimEnd('0')
            return if (fraction.isEmpty()) whole.toString() else "$whole$separator$fraction"
        }
    }
}
