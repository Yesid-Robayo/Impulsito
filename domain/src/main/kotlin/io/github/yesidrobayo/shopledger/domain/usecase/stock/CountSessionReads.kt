package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSession
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionLine
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.CountSessionRepository
import io.github.yesidrobayo.shopledger.domain.port.LocationRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.stock.LocationRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockAdjustmentRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * Un conteo en la lista, con su ubicación y su categoría si las tiene, y cuántas de sus líneas se
 * contaron y cuántas tienen diferencia.
 */
data class CountSessionSummary(
    val session: CountSession,
    val location: Location?,
    val category: Category?,
    val counted: Int,
    val total: Int,
    val differences: Int,
)

/** Los conteos abiertos, que se siguen, y los terminados, del más nuevo al más antiguo. */
data class CountOverview(
    val open: List<CountSessionSummary>,
    val finished: List<CountSessionSummary>,
    /** Si hay más de una ubicación vigente: solo entonces el conteo nuevo pregunta dónde. */
    val showsLocations: Boolean,
    val activeLocations: List<Location>,
)

/**
 * Los conteos, para el inicio de la pantalla. Los abiertos vuelven a emitir con cada línea que se
 * cuenta, así su progreso está al día al volver; los terminados ya no cambian.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveCountOverviewUseCase(
    private val sessions: CountSessionRepository,
    private val locations: LocationRepository,
    private val categories: CategoryRepository,
) {
    operator fun invoke(): Flow<CountOverview> {
        val open = sessions.observeOpen().flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(list.map { session -> sessions.observeLines(session.id) }) { lines ->
                    list.zip(lines.toList())
                }
            }
        }
        val finished = sessions.observeFinished().mapLatest { list ->
            list.map { it to sessions.findLines(it.id) }
        }
        return combine(
            open,
            finished,
            locations.observe(ArchiveFilter.ACTIVE),
            locations.observe(ArchiveFilter.ARCHIVED),
        ) { openLines, finishedLines, active, archived ->
            Places(openLines, finishedLines, active, archived)
        }.mapLatest { (openLines, finishedLines, active, archived) ->
            val all = openLines + finishedLines
            val places = (active + archived).associateBy { it.id }
            val categoryIds = all.mapNotNull { it.first.categoryId }.toSet()
            val names = categoryIds.mapNotNull { categories.findById(it) }.associateBy { it.id }
            val summaries = all.map { (session, lines) ->
                CountSessionSummary(
                    session = session,
                    location = places[session.locationId],
                    category = session.categoryId?.let { names[it] },
                    counted = lines.count { it.counted != null },
                    total = lines.size,
                    differences = lines.count { (it.difference ?: QuantityMilli.ZERO).milli != 0L },
                )
            }
            CountOverview(
                open = summaries.take(openLines.size),
                finished = summaries.drop(openLines.size),
                showsLocations = LocationRules.showsLocations(active.size),
                activeLocations = active,
            )
        }.distinctUntilChanged()
    }
}

/** Lo que junta la lista de conteos antes de armarla. */
private data class Places(
    val open: List<Pair<CountSession, List<CountSessionLine>>>,
    val finished: List<Pair<CountSession, List<CountSessionLine>>>,
    val active: List<Location>,
    val archived: List<Location>,
)

/** Un producto de la sesión, listo para mostrar y valorar. */
data class CountLineView(val line: CountSessionLine, val product: Product?, val unit: MeasureUnit?)

/** Una sesión abierta o terminada, con sus líneas y lo que se ve de ella. */
data class CountSessionView(
    val session: CountSession,
    val location: Location?,
    val category: Category?,
    val lines: List<CountLineView>,
)

/**
 * Una sesión y sus líneas, para contar o revisar. Los productos, sus unidades y la ubicación se
 * leen una sola vez al abrirla, porque una sesión no cambia de productos; las líneas vuelven a
 * emitir con cada conteo anotado. `null` si la sesión no existe.
 */
class ObserveCountSessionUseCase(
    private val sessions: CountSessionRepository,
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val locations: LocationRepository,
    private val categories: CategoryRepository,
) {
    operator fun invoke(sessionId: Long): Flow<CountSessionView?> = flow {
        val session = sessions.findById(sessionId)
        if (session == null) {
            emit(null)
            return@flow
        }
        val first = sessions.findLines(sessionId)
        val byId = products.findByIds(first.map { it.productId }.toSet()).associateBy { it.id }
        val unitsById = byId.values.map { it.details.baseUnitId }.distinct()
            .mapNotNull { units.findById(it) }
            .associateBy { it.id }
        val location = locations.findById(session.locationId)
        val category = session.categoryId?.let { categories.findById(it) }
        emitAll(
            sessions.observeLines(sessionId).map { lines ->
                CountSessionView(
                    session = session,
                    location = location,
                    category = category,
                    lines = lines
                        .map { line ->
                            val product = byId[line.productId]
                            CountLineView(
                                line,
                                product,
                                product?.let { unitsById[it.details.baseUnitId] },
                            )
                        }
                        .sortedWith(
                            compareBy({ it.product?.details?.normalizedName }, { it.line.id }),
                        ),
                )
            },
        )
    }.distinctUntilChanged()
}

/**
 * Lo que dejaría aplicar un conteo: cuántos ajustes, el valor de lo que sobra y de lo que falta,
 * en positivo, y los productos que se movieron desde que se abrió. [surplus] o [shortage] son
 * `null` si la cuenta no cabe.
 */
data class CountReview(
    val adjustments: Int,
    val surplus: Money?,
    val shortage: Money?,
    val moved: Set<Long>,
)

/** Valora las diferencias de una sesión como las valoraría aplicarla, y lee qué se movió. */
class ReviewCountSessionUseCase(private val sessions: CountSessionRepository) {
    suspend operator fun invoke(view: CountSessionView): CountReview {
        val gains = mutableListOf<Money?>()
        val losses = mutableListOf<Money?>()
        var adjustments = 0
        for (item in view.lines) {
            val difference = item.line.difference ?: continue
            if (difference == QuantityMilli.ZERO) continue
            adjustments++
            val product = item.product ?: continue
            val state = CostState(product.onHand, product.averageCost, product.stockValue)
            val total = when (val value = StockAdjustmentRules.value(state, difference)) {
                is Checked.Value -> value.value.totalCost
                Checked.Overflow, is Checked.AboveLimit -> null
            }
            if (difference.milli > 0L) gains += total else losses += total?.let { -it }
        }
        return CountReview(
            adjustments = adjustments,
            surplus = totalOf(gains),
            shortage = totalOf(losses),
            moved = sessions.productsMovedSinceOpen(view.session.id),
        )
    }

    /** La suma, o `null` si una parte ya no cabía o la suma no cabe en `Long`. */
    private fun totalOf(parts: List<Money?>): Money? =
        if (parts.any { it == null }) null else parts.filterNotNull().sumOrNull()
}
