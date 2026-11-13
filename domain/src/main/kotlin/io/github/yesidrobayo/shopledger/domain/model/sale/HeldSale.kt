package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant

/**
 * Una venta en espera: lo que la persona dejó armado para confirmar después, como el pedido de una
 * mesa. No es un hecho del negocio: no toca stock, libro, dinero ni reportes, no reserva nada y se
 * borra libremente.
 *
 * [label] es el nombre opcional que le puso la persona, de 1 a 40 caracteres. [createdAt] es cuándo
 * se puso en espera por primera vez; [updatedAt], la última vez que se guardó o se renombró.
 * [priceListId] es la lista de precios con que se armó, o `null` para la inicial.
 */
data class HeldSale(
    val id: Long,
    val label: String?,
    val lines: List<HeldSaleLine>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val priceListId: Long? = null,
)

/**
 * Una línea de una venta en espera.
 *
 * - [quantity] va en milésimas de la unidad [unitId] en que se vende.
 * - [unitPrice] nulo es el precio vigente del producto al retomar; con valor, es el que la persona
 *   escribió a mano en la línea, y se respeta al retomar.
 * - [discount] es el descuento de la línea en unidad menor. Un porcentaje se guarda ya convertido
 *   en monto, con el precio que tenía la línea al ponerla en espera: la base no guarda porcentajes.
 */
data class HeldSaleLine(
    val productId: Long,
    val unitId: Long,
    val quantity: QuantityMilli,
    val unitPrice: Money?,
    val discount: Money,
)

/**
 * Una venta en espera por guardar: su nombre, sus líneas, el instante en que se guarda y la lista de
 * precios con que se armó, `null` para la inicial.
 */
data class NewHeldSale(
    val label: String?,
    val lines: List<HeldSaleLine>,
    val at: Instant,
    val priceListId: Long? = null,
)
