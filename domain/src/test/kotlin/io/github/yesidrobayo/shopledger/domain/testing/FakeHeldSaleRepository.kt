package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewHeldSale
import io.github.yesidrobayo.shopledger.domain.port.HeldSaleRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Un [HeldSaleRepository] sobre [InMemoryDatabase]: las ventas en espera se reemplazan y se borran. */
internal class FakeHeldSaleRepository(private val database: InMemoryDatabase) : HeldSaleRepository {
    override fun observeAll(): Flow<List<HeldSale>> =
        database.tables.map { tables -> tables.heldSales.values.sortedBy { it.id } }

    override suspend fun findById(id: Long): HeldSale? = database.read().heldSales[id]

    override suspend fun insert(sale: NewHeldSale): Long {
        val id = database.nextId(TABLE)
        val stored = HeldSale(id, sale.label, sale.lines, sale.at, sale.at, sale.priceListId)
        database.write { it.copy(heldSales = it.heldSales + (id to stored)) }
        return id
    }

    override suspend fun replace(id: Long, sale: NewHeldSale): Boolean {
        val current = database.read().heldSales[id] ?: return false
        val stored = current.copy(
            label = sale.label,
            lines = sale.lines,
            updatedAt = sale.at,
            priceListId = sale.priceListId,
        )
        database.write { it.copy(heldSales = it.heldSales + (id to stored)) }
        return true
    }

    override suspend fun rename(id: Long, label: String?, updatedAt: Instant): Boolean {
        val current = database.read().heldSales[id] ?: return false
        val stored = current.copy(label = label, updatedAt = updatedAt)
        database.write { it.copy(heldSales = it.heldSales + (id to stored)) }
        return true
    }

    override suspend fun delete(id: Long): Boolean {
        if (id !in database.read().heldSales) return false
        database.write { it.copy(heldSales = it.heldSales - id) }
        return true
    }

    private companion object {
        const val TABLE = "sale_drafts"
    }
}
