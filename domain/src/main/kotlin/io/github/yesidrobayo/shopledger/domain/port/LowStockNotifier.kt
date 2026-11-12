package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit

/**
 * El aviso de una alerta de stock bajo, con lo que la notificación muestra: el producto, su saldo y
 * su mínimo del momento del cruce, en la unidad base del producto.
 */
data class LowStockNotice(
    val alertId: Long,
    val productId: Long,
    val productName: String,
    val onHand: QuantityMilli,
    val minStock: QuantityMilli,
    val unit: MeasureUnit?,
)

/**
 * Lanza los avisos de stock bajo como notificaciones locales del teléfono, sin servidor. Lo
 * implementa `:app`.
 *
 * Nunca toca la base: quien lo llama guarda después qué pasó con cada aviso.
 */
fun interface LowStockNotifier {
    /**
     * Muestra [notices], todos de una misma tanda: uno solo va suelto, y varios van en un grupo con
     * su resumen. Devuelve `false` si el sistema no deja avisar: el permiso, las notificaciones de
     * la app o su canal están apagados.
     */
    suspend fun show(notices: List<LowStockNotice>): Boolean
}
