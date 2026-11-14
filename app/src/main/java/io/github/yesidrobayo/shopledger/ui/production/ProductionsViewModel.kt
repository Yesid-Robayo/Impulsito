package io.github.yesidrobayo.shopledger.ui.production

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatch
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionCatalog
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionDetail
import io.github.yesidrobayo.shopledger.domain.usecase.production.VoidProductionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lo que muestra la lista de producciones.
 *
 * @param hasRecipes algún producto tiene receta: sin ninguna, el vacío ofrece crear una.
 * @param days las producciones por fecha de negocio, la más nueva primero.
 * @param canLoadMore hay más producciones que las cargadas: la lista pide el tramo siguiente.
 * @param detail la producción abierta en la hoja, si hay una.
 */
@Immutable
data class ProductionsUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val hasRecipes: Boolean = false,
    val days: List<ProductionDayUi> = emptyList(),
    val canLoadMore: Boolean = false,
    val detail: ProductionDetailUi? = null,
    val voidAsked: Boolean = false,
    val voiding: Boolean = false,
    val voidFailed: Boolean = false,
    val voided: Boolean = false,
)

/** Las producciones de una fecha de negocio. */
@Immutable
data class ProductionDayUi(val date: BusinessDate, val items: List<ProductionItemUi>)

/** Una producción en la lista: qué se produjo, cuánto, lo que costó y si se anuló. */
@Immutable
data class ProductionItemUi(
    val id: Long,
    val productName: String,
    val unit: MeasureUnit?,
    val quantity: QuantityMilli,
    val totalCost: Money,
    val voided: Boolean,
)

/**
 * Una producción abierta: sus cifras, sus insumos y, si se anulara, cuánto quedaría de lo
 * producido cuando eso deja el stock negativo.
 */
@Immutable
data class ProductionDetailUi(
    val id: Long,
    val productName: String,
    val unit: MeasureUnit?,
    val quantity: QuantityMilli,
    val totalCost: Money,
    val date: BusinessDate,
    val note: String?,
    val voided: Boolean,
    val lines: List<ProductionLineUi>,
    val leftIfVoided: QuantityMilli?,
)

/** Un insumo de una producción registrada, tal como quedó. */
@Immutable
data class ProductionLineUi(
    val name: String,
    val unit: MeasureUnit?,
    val quantity: QuantityMilli,
    val lineCost: Money,
)

/** Lo que la persona hace en la lista de producciones. */
sealed interface ProductionsAction {
    data class Open(val batchId: Long) : ProductionsAction

    data object CloseDetail : ProductionsAction

    data object AskVoid : ProductionsAction

    data object ConfirmVoid : ProductionsAction

    data object DismissVoid : ProductionsAction

    data object VoidedShown : ProductionsAction

    data object LoadMore : ProductionsAction

    data object Retry : ProductionsAction

    data object NewProduction : ProductionsAction

    data object CreateRecipe : ProductionsAction

    data object Back : ProductionsAction
}

/**
 * La lista de producciones, por fecha y por tramos, con el detalle de una en una hoja y su
 * anulación.
 *
 * Anular pregunta antes, y si lo producido ya salió dice cuánto quedaría: la anulación no se
 * bloquea por eso. Nueva producción, crear una receta y volver los resuelve la navegación.
 *
 * @param catalogSource los productos, las recetas y las unidades, para los nombres.
 * @param recentSource las [Int] producciones más nuevas.
 * @param detailSource una producción con sus insumos.
 * @param void anula una producción, en una transacción.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductionsViewModel(
    private val catalogSource: () -> Flow<ProductionCatalog>,
    private val recentSource: (Int) -> Flow<List<ProductionBatch>>,
    private val detailSource: (Long) -> Flow<ProductionDetail?>,
    private val void: suspend (Long, VoidReason) -> VoidProductionResult,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val limit = MutableStateFlow(PAGE)
    private val attempts = MutableStateFlow(0)
    private val openId = MutableStateFlow<Long?>(null)
    private val overlay = MutableStateFlow(Overlay())

    private val loaded: Flow<Loaded?> = combine(attempts, limit) { _, size -> size }
        .flatMapLatest { size ->
            combine(catalogSource(), recentSource(size + 1)) { catalog, recent ->
                Loaded(catalog, recent.take(size), recent.size > size)
            }.map<Loaded, Loaded?> { it }
                // Una falla de lectura no cierra la lista: muestra el error y deja reintentar.
                .catch { emit(null) }
        }

    private val detail: Flow<ProductionDetail?> = openId.flatMapLatest { id ->
        if (id == null) flowOf(null) else detailSource(id).catch { emit(null) }
    }

    val uiState: StateFlow<ProductionsUiState> =
        combine(loaded, detail, overlay) { data, opened, shown ->
            if (data == null) {
                ProductionsUiState(loading = false, loadFailed = true)
            } else {
                ProductionsUiState(
                    loading = false,
                    hasRecipes = data.catalog.hasRecipes,
                    days = daysOf(data),
                    canLoadMore = data.hasMore,
                    detail = opened?.let { detailOf(it, data.catalog) },
                    voidAsked = shown.voidAsked,
                    voiding = shown.voiding,
                    voidFailed = shown.voidFailed,
                    voided = shown.voided,
                )
            }
        }.flowOn(computation)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                ProductionsUiState(),
            )

    fun onAction(action: ProductionsAction) {
        when (action) {
            is ProductionsAction.Open -> {
                openId.value = action.batchId
                overlay.value = Overlay()
            }

            ProductionsAction.CloseDetail -> {
                openId.value = null
                overlay.value = Overlay()
            }

            ProductionsAction.AskVoid -> overlay.update { it.copy(voidAsked = true) }

            ProductionsAction.DismissVoid -> overlay.update { it.copy(voidAsked = false) }

            ProductionsAction.ConfirmVoid -> voidNow()

            ProductionsAction.VoidedShown -> {
                openId.value = null
                overlay.value = Overlay()
            }

            ProductionsAction.LoadMore -> limit.update { it + PAGE }

            ProductionsAction.Retry -> attempts.update { it + 1 }

            // Crear, abrir una receta y volver los resuelve la navegación.
            ProductionsAction.NewProduction,
            ProductionsAction.CreateRecipe,
            ProductionsAction.Back,
            -> Unit
        }
    }

    private fun voidNow() {
        val id = openId.value ?: return
        if (overlay.value.voiding) return
        overlay.update { it.copy(voidAsked = false, voiding = true, voidFailed = false) }
        viewModelScope.launch {
            // La pantalla no pide motivo: anular desde el detalle es corregir un error de
            // registro.
            val result = void(id, VoidReason.ENTRY_ERROR)
            overlay.update {
                when (result) {
                    VoidProductionResult.Voided -> it.copy(voiding = false, voided = true)

                    // Ya estaba anulada: el detalle se actualiza solo y no hay nada que avisar.
                    VoidProductionResult.AlreadyVoided -> it.copy(voiding = false)

                    VoidProductionResult.NotFound,
                    VoidProductionResult.ReasonNotAllowed,
                    VoidProductionResult.NoteTooLong,
                    VoidProductionResult.UnknownTimeZone,
                    VoidProductionResult.LedgerDamaged,
                    is VoidProductionResult.StorageFailed,
                    -> it.copy(voiding = false, voidFailed = true)
                }
            }
        }
    }

    private fun daysOf(data: Loaded): List<ProductionDayUi> = data.recent
        .groupBy { it.content.businessDate }
        .map { (date, batches) ->
            ProductionDayUi(
                date,
                batches.map { batch ->
                    val product = data.catalog.products[batch.content.productId]
                    ProductionItemUi(
                        id = batch.id,
                        productName = product?.details?.name.orEmpty(),
                        unit = product?.let { data.catalog.units[it.details.baseUnitId] },
                        quantity = batch.content.quantity,
                        totalCost = batch.content.totalCost,
                        voided = batch.status == DocumentStatus.VOIDED,
                    )
                },
            )
        }

    private fun detailOf(
        opened: ProductionDetail,
        catalog: ProductionCatalog,
    ): ProductionDetailUi {
        val batch = opened.batch
        val product = catalog.products[batch.content.productId]
        val voided = batch.status == DocumentStatus.VOIDED
        // Anular saca lo producido: si ya salió, el saldo quedaría por debajo de cero.
        val left = product?.onHand?.let { it - batch.content.quantity }
        return ProductionDetailUi(
            id = batch.id,
            productName = product?.details?.name.orEmpty(),
            unit = product?.let { catalog.units[it.details.baseUnitId] },
            quantity = batch.content.quantity,
            totalCost = batch.content.totalCost,
            date = batch.content.businessDate,
            note = batch.content.note,
            voided = voided,
            lines = opened.lines.map { line ->
                val component = catalog.products[line.content.componentProductId]
                ProductionLineUi(
                    name = component?.details?.name.orEmpty(),
                    unit = component?.let { catalog.units[it.details.baseUnitId] },
                    quantity = line.content.quantity,
                    lineCost = line.content.lineCost,
                )
            },
            leftIfVoided = left?.takeIf { !voided && it.milli < 0L },
        )
    }

    private data class Loaded(
        val catalog: ProductionCatalog,
        val recent: List<ProductionBatch>,
        val hasMore: Boolean,
    )

    private data class Overlay(
        val voidAsked: Boolean = false,
        val voiding: Boolean = false,
        val voidFailed: Boolean = false,
        val voided: Boolean = false,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Cuántas producciones trae cada tramo de la lista. */
        const val PAGE = 50
    }
}
