package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MovementDocument
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductOverview
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductPricing
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectCostUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidAdjustmentResult
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lo que la pantalla del detalle le pide al dominio. Es una interfaz para que las pruebas del
 * ViewModel den respuestas fijas sin una base.
 */
interface ProductDetailOperations {
    fun overview(productId: Long): Flow<ProductOverview?>

    /** Lo que el negocio fija para medir márgenes y sugerir precios. */
    fun pricing(): Flow<PricingContext>

    suspend fun correctCost(productId: Long, cost: Money, note: String?): CorrectCostResult

    suspend fun changePrice(productId: Long, price: Money): ChangePriceResult

    suspend fun archive(productId: Long): ArchiveProductResult

    suspend fun reactivate(productId: Long): ReactivateProductResult

    suspend fun delete(productId: Long): DeleteProductResult

    /** Anula el ajuste o el inventario inicial [movementId] con su fila compensatoria. */
    suspend fun voidAdjustment(movementId: Long): VoidAdjustmentResult
}

/** Lo que muestra el detalle de un producto. */
@Immutable
sealed interface ProductDetailContent {
    data object Loading : ProductDetailContent

    /** El producto no existe, por ejemplo porque se acaba de eliminar. */
    data object Missing : ProductDetailContent

    /**
     * El producto y su libro, con [pricing]: cómo se mide el margen de su precio y qué precio se le
     * sugiere.
     */
    data class Ready(val overview: ProductOverview, val pricing: ProductPricing) :
        ProductDetailContent

    data object ReadError : ProductDetailContent
}

/** Los segmentos del detalle. */
enum class ProductDetailTab {
    MOVEMENTS,
    PRICES,

    /** Lo último que se pagó a cada proveedor por el producto. */
    SUPPLIERS,
}

/**
 * La hoja de corregir el costo mientras se escribe: el costo nuevo como texto, la nota, el problema
 * del costo si lo hay, y si el último guardado falló. Lo escrito siempre se conserva.
 */
@Immutable
data class CostSheet(
    val cost: String,
    val note: String = "",
    val costProblem: FieldProblem? = null,
    val noteTooLong: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: SaveFailure? = null,
)

/**
 * La hoja de cambiar el precio mientras se escribe: el precio nuevo como texto, su problema si lo
 * hay, y si el último guardado falló. Lo escrito siempre se conserva. [suggestion] es el precio
 * sugerido para un margen objetivo, o `null` en un producto por componentes, cuyo costo sale de
 * ellos. Con [canFixCost], sin costo, la hoja ofrece corregirlo.
 */
@Immutable
data class PriceSheet(
    val price: String,
    val priceProblem: FieldProblem? = null,
    val saving: Boolean = false,
    val saveFailed: SaveFailure? = null,
    val suggestion: PriceSuggestionDraft? = null,
    val canFixCost: Boolean = false,
)

/** Lo que se confirma antes de hacerlo. [name] es el del producto, para el título. */
sealed interface ProductDetailDialog {
    val name: String

    data class Archive(override val name: String) : ProductDetailDialog

    data class Delete(override val name: String) : ProductDetailDialog

    /** Anular la fila [movementId] del libro, un ajuste o el inventario inicial. */
    data class VoidAdjustment(val movementId: Long, override val name: String) :
        ProductDetailDialog
}

/** Un aviso breve, abajo, tras una acción. */
sealed interface ProductDetailMessage {
    data object CostCorrected : ProductDetailMessage

    data object PriceChanged : ProductDetailMessage

    data object Archived : ProductDetailMessage

    data object Reactivated : ProductDetailMessage

    data object Deleted : ProductDetailMessage

    data object AdjustmentVoided : ProductDetailMessage

    /** Reactivar no se pudo: otro producto vigente usa su nombre o su código. */
    data object NameTaken : ProductDetailMessage

    /** Reactivar una variante no se pudo: su producto principal sigue archivado. */
    data object ParentArchived : ProductDetailMessage

    /** Eliminar no se pudo: tiene historia, y solo se archiva. */
    data object HasHistory : ProductDetailMessage

    data class Failed(val outOfSpace: Boolean) : ProductDetailMessage
}

/**
 * El estado del detalle: lo que se muestra, el segmento elegido, la hoja de corregir el costo, la
 * confirmación pendiente y el aviso de la última acción. [closed] pide volver atrás, tras eliminarlo.
 */
@Immutable
data class ProductDetailUiState(
    val content: ProductDetailContent = ProductDetailContent.Loading,
    val tab: ProductDetailTab = ProductDetailTab.MOVEMENTS,
    val costSheet: CostSheet? = null,
    val dialog: ProductDetailDialog? = null,
    val message: ProductDetailMessage? = null,
    val closed: Boolean = false,
    val priceSheet: PriceSheet? = null,
)

/** Lo que la persona hace en el detalle. */
sealed interface ProductDetailAction {
    data object Back : ProductDetailAction

    data object Edit : ProductDetailAction

    data object Sell : ProductDetailAction

    data object RegisterPurchase : ProductDetailAction

    data object AdjustStock : ProductDetailAction

    data class OpenDocument(val document: MovementDocument) : ProductDetailAction

    data class SelectTab(val tab: ProductDetailTab) : ProductDetailAction

    data object OpenPriceSheet : ProductDetailAction

    data class PriceText(val text: String) : ProductDetailAction

    data object SavePrice : ProductDetailAction

    data object DismissPriceSheet : ProductDetailAction

    /** Escribió otro margen objetivo en la sugerencia. No se guarda en ninguna parte. */
    data class SuggestionTarget(val text: String) : ProductDetailAction

    /** Pone el precio sugerido en el campo del precio, para revisarlo antes de cambiarlo. */
    data object UseSuggestedPrice : ProductDetailAction

    /** Sin costo no hay sugerencia: cierra la hoja del precio y abre la de corregir el costo. */
    data object FixCostForSuggestion : ProductDetailAction

    data object OpenCostSheet : ProductDetailAction

    data class CostText(val text: String) : ProductDetailAction

    data class CostNote(val text: String) : ProductDetailAction

    data object SaveCost : ProductDetailAction

    data object DismissCostSheet : ProductDetailAction

    data object AskArchive : ProductDetailAction

    data object AskDelete : ProductDetailAction

    /** Pide anular la fila [movementId] del libro: un ajuste o el inventario inicial. */
    data class AskVoid(val movementId: Long) : ProductDetailAction

    data object Reactivate : ProductDetailAction

    data object ConfirmDialog : ProductDetailAction

    data object DismissDialog : ProductDetailAction

    data object Retry : ProductDetailAction

    data object MessageShown : ProductDetailAction
}

/**
 * El detalle de un producto: cuánto hay, cuánto deja y qué le ha pasado.
 *
 * Corregir el costo abre una hoja con el costo de ahora; guardar el mismo no escribe nada. Con
 * historia, el menú solo ofrece archivar; sin ella, eliminar. Cada una pregunta antes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductDetailViewModel(
    private val productId: Long,
    private val operations: ProductDetailOperations,
    private val numbers: ProductNumberFormat,
    openCostSheet: Boolean = false,
    openPriceSheet: Boolean = false,
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val extra = MutableStateFlow(Extra())
    private var latest: ProductOverview? = null
    private var latestPricing: ProductPricing? = null

    /** Si falta abrir la hoja de corregir el costo, pedida al llegar: se abre una sola vez. */
    private var costSheetPending = openCostSheet

    /** Si falta abrir la hoja de cambiar el precio, pedida desde `Revisar`: también una sola vez. */
    private var priceSheetPending = openPriceSheet

    private val content = attempts.flatMapLatest {
        combine(operations.overview(productId), operations.pricing()) { overview, context ->
            if (overview == null) {
                ProductDetailContent.Missing
            } else {
                ProductDetailContent.Ready(overview, ProductPricing.of(overview.product, context))
            }
        }
            .onStart { emit(ProductDetailContent.Loading) }
            .catch { emit(ProductDetailContent.ReadError) }
            .onEach { shown ->
                latest = (shown as? ProductDetailContent.Ready)?.overview
                latestPricing = (shown as? ProductDetailContent.Ready)?.pricing
                val product = latest?.product
                if (costSheetPending && product != null) {
                    costSheetPending = false
                    // Solo un producto con libro y vigente tiene costo que corregir.
                    val tracked = product.details.stockMode == ProductStockMode.TRACKED
                    if (tracked && !product.isArchived) onAction(ProductDetailAction.OpenCostSheet)
                }
                if (priceSheetPending && product != null) {
                    priceSheetPending = false
                    if (!product.isArchived) onAction(ProductDetailAction.OpenPriceSheet)
                }
            }
    }

    val uiState: StateFlow<ProductDetailUiState> =
        combine(content, extra) { shown, more ->
            ProductDetailUiState(
                content = shown,
                tab = more.tab,
                costSheet = more.costSheet,
                dialog = more.dialog,
                message = more.message,
                closed = more.closed,
                priceSheet = more.priceSheet,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ProductDetailUiState(),
        )

    fun onAction(action: ProductDetailAction) {
        when (action) {
            is ProductDetailAction.SelectTab -> extra.update { it.copy(tab = action.tab) }

            ProductDetailAction.OpenPriceSheet -> openPriceSheet()

            is ProductDetailAction.SuggestionTarget -> {
                val pricing = latestPricing ?: return
                priceSheet {
                    copy(
                        suggestion =
                            PriceSuggestionDraft(
                                action.text,
                                PriceSuggestionDraft.read(
                                    action.text,
                                    pricing,
                                    numbers.percentSymbols,
                                ),
                            ),
                    )
                }
            }

            ProductDetailAction.UseSuggestedPrice ->
                priceSheet {
                    suggestion?.price?.let { suggested ->
                        copy(price = moneyText(suggested), priceProblem = null, saveFailed = null)
                    } ?: this
                }

            ProductDetailAction.FixCostForSuggestion -> {
                extra.update { it.copy(priceSheet = null) }
                onAction(ProductDetailAction.OpenCostSheet)
            }

            is ProductDetailAction.PriceText ->
                priceSheet { copy(price = action.text, priceProblem = null, saveFailed = null) }

            ProductDetailAction.SavePrice -> savePrice()

            ProductDetailAction.DismissPriceSheet -> extra.update { it.copy(priceSheet = null) }

            ProductDetailAction.OpenCostSheet ->
                latest?.let { overview ->
                    val cost = moneyText(overview.product.averageCost)
                    extra.update { it.copy(costSheet = CostSheet(cost)) }
                }

            is ProductDetailAction.CostText ->
                sheet { copy(cost = action.text, costProblem = null, saveFailed = null) }

            is ProductDetailAction.CostNote ->
                sheet { copy(note = action.text, noteTooLong = false, saveFailed = null) }

            ProductDetailAction.SaveCost -> saveCost()

            ProductDetailAction.DismissCostSheet -> extra.update { it.copy(costSheet = null) }

            ProductDetailAction.AskArchive ->
                latest?.let { overview ->
                    val name = overview.product.details.name
                    extra.update { it.copy(dialog = ProductDetailDialog.Archive(name)) }
                }

            ProductDetailAction.AskDelete ->
                latest?.let { overview ->
                    val name = overview.product.details.name
                    extra.update { it.copy(dialog = ProductDetailDialog.Delete(name)) }
                }

            is ProductDetailAction.AskVoid ->
                latest?.let { overview ->
                    val name = overview.product.details.name
                    val dialog = ProductDetailDialog.VoidAdjustment(action.movementId, name)
                    extra.update { it.copy(dialog = dialog) }
                }

            ProductDetailAction.Reactivate ->
                viewModelScope.launch { show(reactivateMessage(operations.reactivate(productId))) }

            ProductDetailAction.ConfirmDialog -> confirmDialog()

            ProductDetailAction.DismissDialog -> extra.update { it.copy(dialog = null) }

            ProductDetailAction.Retry -> attempts.update { it + 1 }

            ProductDetailAction.MessageShown -> extra.update { it.copy(message = null) }

            // Las resuelve la pantalla con la navegación.
            ProductDetailAction.Back, ProductDetailAction.Edit, ProductDetailAction.Sell,
            ProductDetailAction.RegisterPurchase, ProductDetailAction.AdjustStock,
            is ProductDetailAction.OpenDocument,
            -> Unit
        }
    }

    /**
     * Abre la hoja del precio con el de ahora y la sugerencia con el margen objetivo del producto.
     * Un producto por componentes no tiene sugerencia; sin costo, solo uno con libro y vigente
     * puede corregirlo desde aquí.
     */
    private fun openPriceSheet() {
        val overview = latest ?: return
        val pricing = latestPricing ?: return
        val product = overview.product
        val mode = product.details.stockMode
        val suggestion = when (mode) {
            ProductStockMode.TRACKED, ProductStockMode.UNTRACKED ->
                PriceSuggestionDraft.open(pricing, numbers.percentSymbols)

            ProductStockMode.COMPONENTS -> null
        }
        val sheet = PriceSheet(
            price = moneyText(product.salePrice),
            suggestion = suggestion,
            canFixCost = mode == ProductStockMode.TRACKED && !product.isArchived,
        )
        extra.update { it.copy(priceSheet = sheet) }
    }

    private fun priceSheet(block: PriceSheet.() -> PriceSheet) {
        extra.update { it.copy(priceSheet = it.priceSheet?.block()) }
    }

    /** El precio escrito, o `null` si no se puede leer: su problema queda en la hoja. */
    private fun readPrice(text: String): Money? = when (
        val read = NumberInputParser.money(
            text,
            numbers.moneySymbols,
            numbers.currencyDecimals,
            Limit.UNIT_MONEY,
        )
    ) {
        is NumberInput.Value -> read.value

        // En blanco es cero: un producto puede quedar sin precio, y la pantalla lo marca.
        NumberInput.Blank -> Money.ZERO

        is NumberInput.TooManyDecimals -> {
            priceSheet { copy(priceProblem = FieldProblem.TooManyDecimals(read.allowed)) }
            null
        }

        is NumberInput.TooLarge -> {
            priceSheet { copy(priceProblem = FieldProblem.TooHigh) }
            null
        }

        NumberInput.Malformed -> {
            priceSheet { copy(priceProblem = FieldProblem.NotANumber) }
            null
        }
    }

    private fun savePrice() {
        val current = extra.value.priceSheet ?: return
        if (current.saving) return
        val price = readPrice(current.price) ?: return
        priceSheet { copy(saving = true, saveFailed = null) }
        viewModelScope.launch {
            when (val result = operations.changePrice(productId, price)) {
                is ChangePriceResult.Changed ->
                    extra.update {
                        it.copy(priceSheet = null, message = ProductDetailMessage.PriceChanged)
                    }

                // El mismo precio: no hay nada que cambiar, y la hoja se cierra.
                ChangePriceResult.Unchanged -> extra.update { it.copy(priceSheet = null) }

                ChangePriceResult.PriceOutOfRange ->
                    priceSheet { copy(saving = false, priceProblem = FieldProblem.TooHigh) }

                // Cambió algo por debajo, o la región o la moneda no permiten guardar: no se
                // guardó nada, y lo escrito sigue.
                ChangePriceResult.NotFound, ChangePriceResult.ProductArchived,
                ChangePriceResult.UnknownTimeZone, ChangePriceResult.CurrencyUnconfirmed,
                -> priceSheet { copy(saving = false, saveFailed = SaveFailure(outOfSpace = false)) }

                is ChangePriceResult.StorageFailed ->
                    priceSheet {
                        copy(saving = false, saveFailed = SaveFailure(result.failure.isOutOfSpace))
                    }
            }
        }
    }

    private fun sheet(block: CostSheet.() -> CostSheet) {
        extra.update { it.copy(costSheet = it.costSheet?.block()) }
    }

    private fun saveCost() {
        val current = extra.value.costSheet ?: return
        if (current.saving) return
        val read = NumberInputParser.money(
            current.cost,
            numbers.moneySymbols,
            numbers.currencyDecimals,
            Limit.UNIT_MONEY,
        )
        val cost = when (read) {
            is NumberInput.Value -> read.value

            // En blanco es cero: dar costo cero a un stock es una corrección válida.
            NumberInput.Blank -> Money.ZERO

            is NumberInput.TooManyDecimals ->
                return sheet { copy(costProblem = FieldProblem.TooManyDecimals(read.allowed)) }

            is NumberInput.TooLarge -> return sheet { copy(costProblem = FieldProblem.TooHigh) }

            NumberInput.Malformed -> return sheet { copy(costProblem = FieldProblem.NotANumber) }
        }
        sheet { copy(saving = true, saveFailed = null) }
        viewModelScope.launch {
            when (val result = operations.correctCost(productId, cost, current.note)) {
                is CorrectCostResult.Corrected ->
                    extra.update {
                        it.copy(costSheet = null, message = ProductDetailMessage.CostCorrected)
                    }

                // El mismo costo: no hay nada que corregir, y la hoja se cierra.
                CorrectCostResult.Unchanged -> extra.update { it.copy(costSheet = null) }

                CorrectCostResult.CostOutOfRange, CorrectCostResult.StockValueTooLarge ->
                    sheet { copy(saving = false, costProblem = FieldProblem.TooHigh) }

                is CorrectCostResult.NoteTooLong -> sheet {
                    copy(saving = false, noteTooLong = true)
                }

                // Cambió algo por debajo, o la región o la moneda no permiten guardar: no se guardó
                // nada, y lo escrito sigue.
                CorrectCostResult.NotFound, CorrectCostResult.ProductArchived,
                CorrectCostResult.NotTracked, CorrectCostResult.UnknownTimeZone,
                CorrectCostResult.CurrencyUnconfirmed,
                -> sheet { copy(saving = false, saveFailed = SaveFailure(outOfSpace = false)) }

                is CorrectCostResult.StorageFailed ->
                    sheet {
                        copy(saving = false, saveFailed = SaveFailure(result.failure.isOutOfSpace))
                    }
            }
        }
    }

    private fun confirmDialog() {
        val dialog = extra.value.dialog ?: return
        extra.update { it.copy(dialog = null) }
        viewModelScope.launch {
            when (dialog) {
                is ProductDetailDialog.Archive -> show(
                    archiveMessage(operations.archive(productId)),
                )

                is ProductDetailDialog.VoidAdjustment ->
                    show(voidMessage(operations.voidAdjustment(dialog.movementId)))

                is ProductDetailDialog.Delete -> {
                    val message = deleteMessage(operations.delete(productId))
                    extra.update {
                        it.copy(
                            message = message,
                            closed = message == ProductDetailMessage.Deleted,
                        )
                    }
                }
            }
        }
    }

    private fun show(message: ProductDetailMessage?) {
        if (message != null) extra.update { it.copy(message = message) }
    }

    /** Un monto como texto editable: cifras y el separador decimal, sin ceros de más. */
    private fun moneyText(amount: Money): String {
        if (amount == Money.ZERO) return ""
        var scale = 1L
        repeat(numbers.currencyDecimals) { scale *= RADIX }
        val whole = amount.minor / scale
        val fraction = (amount.minor % scale).toString()
            .padStart(numbers.currencyDecimals, '0')
            .trimEnd('0')
        val separator = numbers.moneySymbols.decimalSeparator
        return if (fraction.isEmpty()) whole.toString() else "$whole$separator$fraction"
    }

    private data class Extra(
        val tab: ProductDetailTab = ProductDetailTab.MOVEMENTS,
        val costSheet: CostSheet? = null,
        val dialog: ProductDetailDialog? = null,
        val message: ProductDetailMessage? = null,
        val closed: Boolean = false,
        val priceSheet: PriceSheet? = null,
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val RADIX = 10L

        /** Largo máximo de la nota, para el mensaje de error. */
        const val NOTE_MAX: Int = CorrectCostUseCase.MAX_NOTE_LENGTH

        internal fun archiveMessage(result: ArchiveProductResult): ProductDetailMessage? =
            when (result) {
                ArchiveProductResult.Archived -> ProductDetailMessage.Archived

                ArchiveProductResult.NotFound, ArchiveProductResult.AlreadyArchived -> null

                is ArchiveProductResult.StorageFailed ->
                    ProductDetailMessage.Failed(result.failure.isOutOfSpace)
            }

        internal fun reactivateMessage(result: ReactivateProductResult): ProductDetailMessage? =
            when (result) {
                ReactivateProductResult.Reactivated -> ProductDetailMessage.Reactivated

                ReactivateProductResult.NotFound, ReactivateProductResult.NotArchived -> null

                is ReactivateProductResult.NameTaken, is ReactivateProductResult.BarcodeTaken ->
                    ProductDetailMessage.NameTaken

                ReactivateProductResult.ParentArchived -> ProductDetailMessage.ParentArchived

                ReactivateProductResult.UnknownTimeZone -> ProductDetailMessage.Failed(false)

                is ReactivateProductResult.StorageFailed ->
                    ProductDetailMessage.Failed(result.failure.isOutOfSpace)
            }

        internal fun voidMessage(result: VoidAdjustmentResult): ProductDetailMessage? =
            when (result) {
                is VoidAdjustmentResult.Voided -> ProductDetailMessage.AdjustmentVoided

                // Ya estaba anulada o ya no existe: el libro ya muestra cómo quedó.
                VoidAdjustmentResult.NotFound, VoidAdjustmentResult.AlreadyVoided -> null

                VoidAdjustmentResult.NotVoidable, VoidAdjustmentResult.UnknownTimeZone,
                VoidAdjustmentResult.LedgerDamaged,
                -> ProductDetailMessage.Failed(outOfSpace = false)

                is VoidAdjustmentResult.StorageFailed ->
                    ProductDetailMessage.Failed(result.failure.isOutOfSpace)
            }

        internal fun deleteMessage(result: DeleteProductResult): ProductDetailMessage? =
            when (result) {
                DeleteProductResult.Deleted -> ProductDetailMessage.Deleted

                DeleteProductResult.NotFound -> null

                // Otros productos lo usan, o tiene historia: en los dos casos no se borra y se
                // archiva si se quiere sacar del catálogo.
                DeleteProductResult.HasHistory, is DeleteProductResult.LinkedInCatalog ->
                    ProductDetailMessage.HasHistory

                is DeleteProductResult.StorageFailed ->
                    ProductDetailMessage.Failed(result.failure.isOutOfSpace)
            }
    }
}
