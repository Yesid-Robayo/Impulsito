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
import io.github.yesidrobayo.shopledger.domain.rule.sale.ExtraLines
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
 * así sus líneas quedan en el orden en que se armó la venta. La lista de precios viaja con la
 * espera; el cliente no se toca aquí: llega con su módulo.
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
                priceListId = sale.priceListId,
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
            priceListId = sale.priceListId,
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

    /**
     * Escribe las líneas en orden: una principal va antes que sus extras, así que su id ya existe
     * cuando el extra la nombra.
     */
    private suspend fun insertLines(draftId: Long, sale: NewHeldSale) {
        val ids = ArrayList<Long>(sale.lines.size)
        for (line in sale.lines) {
            ids += drafts.insertLine(
                SaleDraftLineEntity(
                    draftId = draftId,
                    productId = line.productId,
                    unitId = line.unitId,
                    enteredQuantityMilli = line.quantity.milli,
                    unitPrice = line.unitPrice?.minor,
                    lineDiscount = line.discount.minor,
                    parentLineId = line.parentIndex?.let { ids[it] },
                ),
            )
        }
    }

    /**
     * La espera con sus líneas en orden de `id`, que es el orden en que se armó: cada extra apunta
     * a la posición de su principal, que va antes. Un extra cuya principal no está en la espera no
     * tendría a quién seguir: se omite.
     */
    private fun heldSaleOf(head: SaleDraftEntity, lines: List<SaleDraftLineEntity>): HeldSale {
        val positions = lines.withIndex().associate { (index, line) -> line.id to index }
        val held = lines.map { line ->
            HeldSaleLine(
                productId = line.productId,
                unitId = line.unitId,
                quantity = QuantityMilli(line.enteredQuantityMilli),
                unitPrice = line.unitPrice?.let(::Money),
                discount = Money(line.lineDiscount),
                // Una posición que no es anterior la descarta abajo, con su extra.
                parentIndex = line.parentLineId?.let { positions[it] ?: lines.size },
            )
        }
        return HeldSale(
            id = head.id,
            label = head.label,
            lines = ExtraLines.retain(held, { it.parentIndex }, { true })
                .map { it.line.copy(parentIndex = it.parentIndex) },
            createdAt = head.createdAt.toInstant(),
            updatedAt = head.updatedAt.toInstant(),
            priceListId = head.priceListId,
        )
    }
}
