package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierLastCost
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Un [PurchaseRepository] sobre [InMemoryDatabase]. */
internal class FakePurchaseRepository(private val database: InMemoryDatabase) :
    PurchaseRepository {
    override fun observeIn(period: ReportPeriod): Flow<List<Purchase>> = database.tables.map {
        it.purchases.values
            .filter { purchase -> purchase.content.businessDate in period }
            .sortedWith(
                compareByDescending<Purchase> {
                    it.content.businessDate
                }.thenByDescending { it.id },
            )
    }

    override fun observeById(id: Long): Flow<Purchase?> = database.tables.map { it.purchases[id] }

    override fun observeLines(purchaseId: Long): Flow<List<PurchaseLine>> =
        database.tables.map { linesOf(it, purchaseId) }

    override fun observeLastCosts(productId: Long): Flow<List<SupplierLastCost>> =
        database.tables.map { tables ->
            lastCostsOf(tables.purchases, tables.purchaseLines, productId)
        }

    override suspend fun findById(id: Long): Purchase? = database.read().purchases[id]

    override suspend fun findLines(purchaseId: Long): List<PurchaseLine> =
        linesOf(database.read(), purchaseId)

    override suspend fun insert(purchase: NewPurchase): Long {
        val id = database.nextId(TABLE)
        database.write {
            it.copy(purchases = it.purchases + (id to Purchase(id, purchase, voiding = null)))
        }
        return id
    }

    override suspend fun insertLine(purchaseId: Long, line: NewPurchaseLine): Long {
        check(purchaseId in database.read().purchases) { "La línea no tiene compra" }
        val id = database.nextId(LINE_TABLE)
        database.write {
            it.copy(purchaseLines = it.purchaseLines + (id to PurchaseLine(id, purchaseId, line)))
        }
        return id
    }

    override suspend fun markVoided(id: Long, voiding: Voiding) {
        val purchase = checkNotNull(database.read().purchases[id]) { "La compra no existe" }
        check(purchase.voiding == null) { "La compra ya está anulada" }
        database.write {
            it.copy(purchases = it.purchases + (id to purchase.copy(voiding = voiding)))
        }
    }

    /** Estas pruebas no modelan devoluciones al proveedor: ninguna compra tiene una. */
    override suspend fun hasActiveReturns(purchaseId: Long): Boolean = false

    private fun linesOf(tables: Tables, purchaseId: Long): List<PurchaseLine> =
        tables.purchaseLines.values.filter { it.purchaseId == purchaseId }.sortedBy { it.id }

    private companion object {
        const val TABLE = "purchases"
        const val LINE_TABLE = "purchase_lines"
    }
}
