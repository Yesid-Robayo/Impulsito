package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.MovementDocument
import kotlinx.coroutines.flow.Flow

/**
 * De qué venta o compra salió cada fila del libro de un producto. Solo lee: la fila guarda la línea
 * del documento, y el documento sale de esa línea.
 */
fun interface MovementDocumentSource {
    /**
     * El documento de cada fila de [productId] que salió de una venta o una compra, por id de la
     * fila. Las demás no aparecen. Vuelve a emitir con cada cambio confirmado del libro.
     */
    fun observeDocuments(productId: Long): Flow<Map<Long, MovementDocument>>
}
