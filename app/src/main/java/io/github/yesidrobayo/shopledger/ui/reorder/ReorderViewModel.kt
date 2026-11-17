package io.github.yesidrobayo.shopledger.ui.reorder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderPlanner
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderQuery
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderSupplierFilter
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ReorderOverview
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.PurchasePrefillLine
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

/** Lo que se leyó de la lista: todavía nada, la lista, o una falla que deja reintentar. */
private sealed interface ReorderLoad {
    data object Loading : ReorderLoad

    data class Ready(val overview: ReorderOverview) : ReorderLoad

    data object Failed : ReorderLoad
}

/** Lo que la persona eligió: los días a cubrir, los filtros y la ubicación. */
private data class ReorderChoice(
    val coverDays: Long,
    val supplier: ReorderSupplierFilter,
    val categoryId: Long?,
    val locationId: Long?,
)

/**
 * La reposición sugerida: lee la lista y deja cambiar cada cantidad antes de comprar.
 *
 * - **Nada se escribe.** Compartir abre otra app y `Crear compra` abre la compra con lo elegido;
 *   la compra se guarda solo al confirmarla allí.
 * - **Lo que se edita a mano gana.** Cambiar un filtro de proveedor o categoría lo conserva.
 *   Cambiar los días a cubrir o la ubicación lo descarta, porque cambia la cuenta de la que salió.
 * - **Lo elegido sobrevive** a que Android cierre el proceso: va en el estado guardado.
 *
 * @param planSource la lectura de la lista para unos días, unos filtros y una ubicación.
 * @param locations las ubicaciones vigentes; con menos de dos no hay selector.
 * @param initialSupplierId el proveedor con que se abrió, como desde su detalle.
 * @param producible los id de lo que se puede producir hoy. Sus filas ofrecen `Producir` y quedan
 *   fuera de la compra, de su costo estimado y de la lista compartida: lo que el negocio hace no
 *   se le compra a un proveedor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReorderViewModel(
    private val planSource: (ReorderQuery, Long?) -> Flow<ReorderOverview>,
    locations: Flow<List<Location>>,
    private val savedState: SavedStateHandle,
    computation: CoroutineDispatcher,
    initialSupplierId: Long? = null,
    producible: Flow<Set<Long>> = flowOf(emptySet()),
) : ViewModel() {
    private val coverText = MutableStateFlow(
        savedState.get<String>(COVER_TEXT) ?: ReorderRules.DEFAULT_COVER_DAYS.toString(),
    )
    private val choice = MutableStateFlow(
        ReorderChoice(
            coverDays = savedState.get<Long>(COVER_DAYS) ?: ReorderRules.DEFAULT_COVER_DAYS,
            supplier = supplierOf(savedState.get<Long>(SUPPLIER), initialSupplierId),
            categoryId = savedState.get<Long>(CATEGORY),
            locationId = savedState.get<Long>(LOCATION),
        ),
    )
    private val edits = MutableStateFlow(readEdits())
    private val attempts = MutableStateFlow(0)

    private val load: StateFlow<ReorderLoad> = combine(attempts, choice) { _, chosen -> chosen }
        .flatMapLatest { chosen ->
            val query = ReorderQuery(
                coverDays = chosen.coverDays,
                supplier = chosen.supplier,
                categoryId = chosen.categoryId,
            )
            planSource(query, chosen.locationId)
                .map<ReorderOverview, ReorderLoad> { ReorderLoad.Ready(it) }
                // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
                .catch { emit(ReorderLoad.Failed) }
        }.flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReorderLoad.Loading)

    // Sin ubicaciones legibles, todo se mira junto y no hay selector.
    private val choices = locations.catch { emit(emptyList()) }

    // Si no se puede leer qué se produce, todo se trata como compra, como antes de las recetas.
    private val loaded: Flow<Pair<ReorderLoad, Set<Long>>> =
        combine(load, producible.catch { emit(emptySet()) }) { read, made -> read to made }

    val uiState: StateFlow<ReorderUiState> =
        combine(loaded, choice, coverText, edits, choices) { both, chosen, text, edited, places ->
            build(both.first, chosen, text, edited, places, made = both.second)
        }.flowOn(computation)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initial(),
            )

    fun onAction(action: ReorderAction) {
        when (action) {
            ReorderAction.Retry -> attempts.update { it + 1 }

            is ReorderAction.CoverChange -> changeCover(action.text)

            is ReorderAction.SupplierFilterChange ->
                changeChoice { it.copy(supplier = action.filter) }

            is ReorderAction.CategoryChange ->
                changeChoice { it.copy(categoryId = action.categoryId) }

            is ReorderAction.LocationChange -> {
                if (action.locationId == choice.value.locationId) return
                changeEdits { emptyMap() }
                changeChoice { it.copy(locationId = action.locationId) }
            }

            is ReorderAction.Increment -> step(action.productId, up = true)

            is ReorderAction.Decrement -> step(action.productId, up = false)

            // Volver, administrar ubicaciones, compartir, comprar y producir los resuelve la
            // navegación.
            ReorderAction.Back,
            ReorderAction.ManageLocations,
            is ReorderAction.Share,
            is ReorderAction.CreatePurchase,
            is ReorderAction.Produce,
            -> Unit
        }
    }

    /**
     * La compra del grupo de [supplierId], con una línea por cada producto que no va en cero, en
     * su unidad de compra si tiene una. `null` si el grupo no tiene nada que comprar.
     */
    fun purchaseOf(supplierId: Long?): AppNavKey.Purchase? {
        val group = uiState.value.groups.firstOrNull { it.supplierId == supplierId } ?: return null
        val lines = group.toBuy.map { line ->
            val factor = line.purchaseFactor
            val unit = line.purchaseUnit
            if (unit != null && factor != null && line.quantity.milli % factor == 0L) {
                val inUnit = line.quantity.milli / factor * Scale.MILLI
                PurchasePrefillLine(line.productId, inUnit, unit.id)
            } else {
                PurchasePrefillLine(line.productId, line.quantity.milli)
            }
        }
        if (lines.isEmpty()) return null
        return AppNavKey.Purchase(
            prefillSupplierId = supplierId,
            prefillLines = lines,
            prefillLocationId = uiState.value.locationId,
        )
    }

    private fun changeCover(text: String) {
        val digits = text.filter(Char::isDigit).take(COVER_MAX_DIGITS)
        coverText.value = digits
        savedState[COVER_TEXT] = digits
        val days = digits.toLongOrNull() ?: return
        if (days < 1L || days > MAX_COVER_DAYS || days == choice.value.coverDays) return
        changeEdits { emptyMap() }
        changeChoice { it.copy(coverDays = days) }
    }

    private fun changeChoice(change: (ReorderChoice) -> ReorderChoice) {
        val next = change(choice.value)
        choice.value = next
        savedState[COVER_DAYS] = next.coverDays
        savedState[SUPPLIER] = when (val filter = next.supplier) {
            ReorderSupplierFilter.All -> ALL_SUPPLIERS
            ReorderSupplierFilter.Without -> WITHOUT_SUPPLIER
            is ReorderSupplierFilter.Of -> filter.supplierId
        }
        savedState[CATEGORY] = next.categoryId
        savedState[LOCATION] = next.locationId
    }

    /** Suma o resta un paso, nunca por debajo de cero. */
    private fun step(productId: Long, up: Boolean) {
        val line = uiState.value.groups.asSequence().flatMap { it.lines }
            .firstOrNull { it.productId == productId } ?: return
        val step = line.purchaseFactor ?: Scale.MILLI
        val next = if (up) {
            Math.addExact(line.quantity.milli, step)
        } else {
            (line.quantity.milli - step).coerceAtLeast(0L)
        }
        changeEdits { it + (productId to next) }
    }

    private fun changeEdits(change: (Map<Long, Long>) -> Map<Long, Long>) {
        val next = change(edits.value)
        edits.value = next
        savedState[EDITS] = next.flatMap { (id, milli) -> listOf(id, milli) }.toLongArray()
    }

    private fun readEdits(): Map<Long, Long> {
        val stored = savedState.get<LongArray>(EDITS) ?: return emptyMap()
        return stored.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
    }

    private fun initial(): ReorderUiState = ReorderUiState(
        coverText = coverText.value,
        supplierFilter = choice.value.supplier,
        categoryId = choice.value.categoryId,
        locationId = choice.value.locationId,
    )

    private fun build(
        read: ReorderLoad,
        chosen: ReorderChoice,
        text: String,
        edited: Map<Long, Long>,
        places: List<Location>,
        made: Set<Long>,
    ): ReorderUiState {
        val tooHigh = (text.toLongOrNull() ?: 0L) > MAX_COVER_DAYS
        val base = initial().copy(
            coverText = text,
            coverTooHigh = tooHigh,
            supplierFilter = chosen.supplier,
            categoryId = chosen.categoryId,
            // Con una sola ubicación no hay selector, y se mira todo junto.
            locations = places.takeIf { it.size > 1 }.orEmpty(),
            locationId = chosen.locationId.takeIf { places.size > 1 },
        )
        return when (read) {
            ReorderLoad.Loading -> base

            ReorderLoad.Failed -> base.copy(loading = false, loadFailed = true)

            is ReorderLoad.Ready -> {
                val overview = read.overview
                val groups = overview.plan.groups.map { group ->
                    val lines = group.lines.map { line ->
                        val quantity = edited[line.product.id] ?: line.suggestion.suggested.milli
                        ReorderLineUi(
                            productId = line.product.id,
                            name = line.product.details.name,
                            unit = overview.units[line.product.details.baseUnitId],
                            onHand = line.onHand,
                            daysLeft = line.suggestion.daysLeft,
                            recentSales = line.suggestion.recentSales,
                            soldOut = line.soldOut,
                            quantity = QuantityMilli(quantity),
                            purchaseUnit = line.purchaseUnit?.let { overview.units[it.unitId] },
                            purchaseFactor = line.purchaseUnit?.factorMilli,
                            producible = line.product.id in made,
                        )
                    }
                    // El costo estimado es el de la compra: lo que se produce no suma.
                    val costs = group.lines.zip(lines)
                        .filter { (_, shown) -> !shown.producible }
                        .map { (line, shown) -> line.unitCost to shown.quantity }
                    ReorderGroupUi(
                        supplierId = group.supplier?.id,
                        supplierName = group.supplier?.details?.name,
                        lines = lines,
                        estimatedCost = ReorderPlanner.estimatedCost(costs),
                    )
                }
                val empty = when {
                    groups.isNotEmpty() -> null
                    overview.plan.hasRecentSales -> ReorderEmpty.NOTHING_TO_RESTOCK
                    else -> ReorderEmpty.NEEDS_SALES
                }
                base.copy(
                    loading = false,
                    suppliers = overview.suppliers,
                    categories = overview.categories,
                    groups = groups,
                    empty = empty,
                )
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** El tope de `Cubrir`: un año. Más allá, la sugerencia deja de servir para una compra. */
        const val MAX_COVER_DAYS = 365L
        const val COVER_MAX_DIGITS = 4

        /** Lo que se guarda como proveedor elegido para decir `Sin proveedor` o todos. */
        const val WITHOUT_SUPPLIER = 0L
        const val ALL_SUPPLIERS = -1L

        const val COVER_TEXT = "reorder.coverText"
        const val COVER_DAYS = "reorder.coverDays"
        const val SUPPLIER = "reorder.supplier"
        const val CATEGORY = "reorder.category"
        const val LOCATION = "reorder.location"
        const val EDITS = "reorder.edits"

        fun supplierOf(stored: Long?, initial: Long?): ReorderSupplierFilter = when {
            stored == ALL_SUPPLIERS -> ReorderSupplierFilter.All
            stored == WITHOUT_SUPPLIER -> ReorderSupplierFilter.Without
            stored != null -> ReorderSupplierFilter.Of(stored)
            initial != null -> ReorderSupplierFilter.Of(initial)
            else -> ReorderSupplierFilter.All
        }
    }
}
