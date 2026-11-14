package io.github.yesidrobayo.shopledger.ui.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.OpeningBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RegisterOpeningBalanceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.AdjustStockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ObserveStockAdjustmentSubjectUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentSubject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Lo que el ajuste de stock le pide al dominio. Es una interfaz para que las pruebas del ViewModel
 * den respuestas fijas sin una base.
 */
interface StockAdjustmentOperations {
    fun subject(productId: Long): Flow<StockAdjustmentSubject?>

    /** El producto con lo que hay en [locationId], o con todo su stock si es `null`. */
    fun subject(productId: Long, locationId: Long?): Flow<StockAdjustmentSubject?> =
        subject(productId)

    /** Las ubicaciones vigentes. Sin ubicaciones conectadas, ninguna: no hay selector. */
    fun locations(): Flow<List<Location>> = flowOf(emptyList())

    suspend fun adjust(request: StockAdjustmentRequest): StockAdjustmentResult

    suspend fun registerOpening(
        productId: Long,
        quantity: QuantityMilli,
        unitCost: Money,
    ): OpeningBalanceResult
}

/** Las operaciones del ajuste sobre sus casos de uso. */
class StockAdjustmentUseCases(
    private val observeUseCase: ObserveStockAdjustmentSubjectUseCase,
    private val adjustUseCase: AdjustStockUseCase,
    private val openingUseCase: RegisterOpeningBalanceUseCase,
    private val locationsSource: (() -> Flow<List<Location>>)? = null,
) : StockAdjustmentOperations {
    override fun subject(productId: Long): Flow<StockAdjustmentSubject?> = observeUseCase(productId)

    override fun subject(productId: Long, locationId: Long?): Flow<StockAdjustmentSubject?> =
        observeUseCase(productId, locationId)

    override fun locations(): Flow<List<Location>> = locationsSource?.invoke() ?: super.locations()

    override suspend fun adjust(request: StockAdjustmentRequest): StockAdjustmentResult =
        adjustUseCase(request)

    override suspend fun registerOpening(
        productId: Long,
        quantity: QuantityMilli,
        unitCost: Money,
    ): OpeningBalanceResult = openingUseCase(productId, quantity, unitCost)
}
