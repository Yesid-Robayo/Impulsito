package io.github.yesidrobayo.shopledger.data.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewProduct
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.port.LowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import kotlin.time.Instant

// Repositorios reales de Room envueltos para fallar a propósito después de una escritura. Todos los
// datos de estas pruebas son inventados.

/** Una falla del código, inventada por la prueba, después de que la base ya escribió. */
internal class InjectedWriteFailure : RuntimeException("Falla inyectada por la prueba")

/**
 * Cuenta las escrituras de los repositorios envueltos y lanza [InjectedWriteFailure] justo después
 * de la número [failAfter], cuando la base ya la hizo dentro de la transacción. `null` no falla.
 */
internal class WriteFaults {
    var failAfter: Int? = null
    var writes: Int = 0
        private set

    fun reset(failAfter: Int?) {
        this.failAfter = failAfter
        writes = 0
    }

    suspend fun <T> after(write: suspend () -> T): T {
        val result = write()
        writes++
        if (writes == failAfter) throw InjectedWriteFailure()
        return result
    }
}

internal class FaultyProducts(
    private val real: ProductRepository,
    private val faults: WriteFaults,
) : ProductRepository by real {
    override suspend fun insert(product: NewProduct): Long = faults.after { real.insert(product) }

    override suspend fun updateDetails(id: Long, details: ProductDetails, updatedAt: Instant) =
        faults.after { real.updateDetails(id, details, updatedAt) }

    override suspend fun updateSalePrice(id: Long, salePrice: Money, updatedAt: Instant) =
        faults.after { real.updateSalePrice(id, salePrice, updatedAt) }

    override suspend fun updateInitialCost(id: Long, initialCost: Money, updatedAt: Instant) =
        faults.after { real.updateInitialCost(id, initialCost, updatedAt) }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
        faults.after { real.setArchivedAt(id, archivedAt, updatedAt) }

    override suspend fun delete(id: Long) = faults.after { real.delete(id) }
}

internal class FaultyPriceHistory(
    private val real: PriceHistoryRepository,
    private val faults: WriteFaults,
) : PriceHistoryRepository by real {
    override suspend fun append(change: NewPriceChange): Long = faults.after { real.append(change) }
}

internal class FaultyLedger(
    private val real: StockLedgerRepository,
    private val faults: WriteFaults,
) : StockLedgerRepository by real {
    override suspend fun append(movement: NewStockMovement): Long =
        faults.after { real.append(movement) }
}

internal class FaultyAlerts(
    private val real: LowStockAlertRepository,
    private val faults: WriteFaults,
) : LowStockAlertRepository by real {
    override suspend fun insert(alert: NewLowStockAlert): Long = faults.after { real.insert(alert) }

    override suspend fun resolve(id: Long, resolvedAt: Instant) = faults.after {
        real.resolve(id, resolvedAt)
    }
}

internal class FaultyCategories(
    private val real: CategoryRepository,
    private val faults: WriteFaults,
) : CategoryRepository by real {
    override suspend fun insert(details: CategoryDetails, createdAt: Instant): Long =
        faults.after { real.insert(details, createdAt) }

    override suspend fun update(id: Long, details: CategoryDetails, updatedAt: Instant) =
        faults.after { real.update(id, details, updatedAt) }

    override suspend fun delete(id: Long) = faults.after { real.delete(id) }
}
