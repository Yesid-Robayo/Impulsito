package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.withReadTransaction
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.sale.SaleDraftEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleDraftLineEntity
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewHeldSale
import io.github.yesidrobayo.shopledger.domain.port.HeldSaleRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las ventas en espera sobre `sale_drafts` y `sale_draft_lines`.
 *
 * Guardar de nuevo una espera borra sus líneas y escribe las de ahora: una espera no es historia, y
 * así sus líneas quedan en el orden en que se armó la venta. El cliente y la lista de precios de la
 * espera no se tocan aquí: llegan con sus módulos.
 */
internal class RoomHeldSaleRepository(private val database: AppDatabase) : HeldSaleRepository {
    private val drafts = database.saleDraftDao()
    private val writes = RepositoryWrites(database)

    // Cualquier cambio en una de las dos tablas vuelve a leer las dos, también el borrado en
    // cascada de una línea por su producto. Combinar lo que emite cada una por separado dejaría ver
    // una espera nueva sin sus líneas entre las dos lecturas; por eso las dos lecturas van en una
    // misma transacción de lectura, que ve la base en un solo instante.
    override fun observeAll(): Flow<List<HeldSale>> =
        combine(drafts.observeAll(), drafts.observeAllLines()) { _, _ -> }
            .conflate()
            .map { readAll() }
            .distinctUntilChanged()

    private suspend fun readAll(): List<HeldSale> = database.withReadTransaction {
        val heads = drafts.findAll()
        val byDraft = drafts.findAllLines().groupBy { it.draftId }
        heads.map { heldSaleOf(it, byDraft[it.id].orEmpty()) }
    }

    override suspend fun findById(id: Long): HeldSale? =
        drafts.findById(id)?.let { heldSaleOf(it, drafts.findLines(id)) }

    override suspend fun insert(sale: NewHeldSale): Long = writes.inTransaction {
        val at = sale.at.toStoredMillis()
        val id = drafts.insert(
            SaleDraftEntity(
                label = sale.label,
                customerId = null,
                priceListId = null,
                createdAt = at,
                updatedAt = at,
            ),
        )
        insertLines(id, sale)
        id
    }

    override suspend fun replace(id: Long, sale: NewHeldSale): Boolean = writes.inTransaction {
        val current = drafts.findById(id) ?: return@inTransaction false
        drafts.update(
            id = id,
            label = sale.label,
            customerId = current.customerId,
            priceListId = current.priceListId,
            updatedAt = sale.at.toStoredMillis(),
        )
        drafts.deleteLines(id)
        insertLines(id, sale)
        true
    }

    override suspend fun rename(id: Long, label: String?, updatedAt: Instant): Boolean =
        writes.inTransaction {
            val current = drafts.findById(id) ?: return@inTransaction false
            drafts.update(
                id = id,
                label = label,
                customerId = current.customerId,
                priceListId = current.priceListId,
                updatedAt = updatedAt.toStoredMillis(),
            ) == 1
        }

    override suspend fun delete(id: Long): Boolean = writes.inTransaction { drafts.delete(id) == 1 }

    private suspend fun insertLines(draftId: Long, sale: NewHeldSale) {
        for (line in sale.lines) {
            drafts.insertLine(
                SaleDraftLineEntity(
                    draftId = draftId,
                    productId = line.productId,
                    unitId = line.unitId,
                    enteredQuantityMilli = line.quantity.milli,
                    unitPrice = line.unitPrice?.minor,
                    lineDiscount = line.discount.minor,
                    parentLineId = null,
                ),
            )
        }
    }

    private fun heldSaleOf(head: SaleDraftEntity, lines: List<SaleDraftLineEntity>): HeldSale =
        HeldSale(
            id = head.id,
            label = head.label,
            lines = lines.map { line ->
                HeldSaleLine(
                    productId = line.productId,
                    unitId = line.unitId,
                    quantity = QuantityMilli(line.enteredQuantityMilli),
                    unitPrice = line.unitPrice?.let(::Money),
                    discount = Money(line.lineDiscount),
                )
            },
            createdAt = head.createdAt.toInstant(),
            updatedAt = head.updatedAt.toInstant(),
        )
}
