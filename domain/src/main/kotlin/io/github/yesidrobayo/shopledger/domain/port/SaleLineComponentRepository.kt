package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLineComponent
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineComponent

/**
 * Las hojas que consumió cada línea de combo: agregar y leer, nada más. No hay función que edite
 * ni borre una hoja, y no debe haberla: explica lo que salió del stock y a qué costo. La escritura
 * solo se llama dentro de la transacción de la venta.
 */
interface SaleLineComponentRepository {
    /** Las hojas de la línea [saleLineId], en el orden en que se guardaron. */
    suspend fun findBySaleLine(saleLineId: Long): List<SaleLineComponent>

    /** Las hojas de todas las líneas de la venta [saleId], en el orden en que se guardaron. */
    suspend fun findBySale(saleId: Long): List<SaleLineComponent>

    /** Guarda una hoja de la línea [saleLineId] y devuelve su id. */
    suspend fun insert(saleLineId: Long, component: NewSaleLineComponent): Long
}
