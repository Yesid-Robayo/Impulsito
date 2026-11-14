package io.github.yesidrobayo.shopledger.domain.model.production

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import kotlin.time.Instant

/**
 * La cabecera de una producción que el caso de uso va a confirmar, todavía sin id.
 *
 * [quantity] es lo producido de [productId], en milésimas de su unidad base. [totalCost] es la
 * suma del costo de los insumos, y es el valor con que lo producido entra al stock: su costo
 * promedio sale de ahí. [locationId] es la ubicación de donde salen los insumos y a la que entra
 * lo producido, la misma para todo el lote.
 */
data class NewProductionBatch(
    val productId: Long,
    val locationId: Long,
    val quantity: QuantityMilli,
    val totalCost: Money,
    val note: String?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
)

/**
 * Una producción guardada. Nunca se edita: solo se anula, y entonces [voiding] guarda cuándo y por
 * qué.
 */
data class ProductionBatch(val id: Long, val content: NewProductionBatch, val voiding: Voiding?) {
    /** Activa o anulada, según [voiding]. */
    val status: DocumentStatus get() = DocumentStatus.of(voiding)
}

/**
 * Un insumo de una producción que el caso de uso va a guardar, todavía sin id.
 *
 * [componentProductId] es siempre una hoja: un producto con stock o un servicio, nunca un combo,
 * porque un combo de la receta se expande a sus componentes. [quantity] va en milésimas de su
 * unidad base. [unitCost] es el costo por unidad que se congela para mostrarlo: el promedio del
 * momento con stock, o el costo fijo de un servicio. [lineCost] es lo que el insumo suma al costo
 * de lo producido.
 */
data class NewProductionBatchLine(
    val componentProductId: Long,
    val quantity: QuantityMilli,
    val unitCost: Money,
    val lineCost: Money,
)

/** Un insumo guardado de la producción [productionBatchId]. No cambia nunca. */
data class ProductionBatchLine(
    val id: Long,
    val productionBatchId: Long,
    val content: NewProductionBatchLine,
)
