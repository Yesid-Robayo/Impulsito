package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.stock.ExpiringLot

/** El aviso de un lote por vencer, con la unidad en que se cuenta lo que queda. */
data class ExpiryNotice(val lot: ExpiringLot, val unit: MeasureUnit?)

/**
 * Lanza los avisos de vencimiento como notificaciones locales del teléfono, sin servidor. Lo
 * implementa `:app`.
 *
 * Nunca toca la base: quien lo llama anota después qué lotes ya se avisaron.
 */
fun interface ExpiryNotifier {
    /**
     * Muestra [notices], todos de una misma tanda: uno solo va suelto, y varios van en un grupo
     * con su resumen. Devuelve `false` si el sistema no deja avisar: el permiso, las
     * notificaciones de la app o su canal están apagados.
     */
    suspend fun show(notices: List<ExpiryNotice>): Boolean
}
