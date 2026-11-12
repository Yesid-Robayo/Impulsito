package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.catalog.MovementDocument
import io.github.yesidrobayo.shopledger.domain.port.MovementDocumentSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** La venta o la compra de cada fila del libro, con una consulta de solo lectura. */
internal class RoomMovementDocumentSource(database: AppDatabase) : MovementDocumentSource {
    private val movements = database.stockMovementDao()

    override fun observeDocuments(productId: Long): Flow<Map<Long, MovementDocument>> = movements
        .observeDocuments(productId)
        .map { rows ->
            rows.mapNotNull { row ->
                val document = row.saleId?.let { MovementDocument.Sale(it) }
                    ?: row.purchaseId?.let { MovementDocument.Purchase(it) }
                document?.let { row.movementId to it }
            }.toMap()
        }
        .distinctUntilChanged()
}
