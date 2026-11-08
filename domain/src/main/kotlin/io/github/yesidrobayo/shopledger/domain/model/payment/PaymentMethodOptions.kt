package io.github.yesidrobayo.shopledger.domain.model.payment

import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage

/**
 * Un método de pago con lo que se puede hacer con él, para el formulario y su menú.
 *
 * [hasSalePayments] dice si alguna venta, aunque esté anulada, se cobró con él. [activeCount] es
 * cuántos métodos vigentes hay, este incluido si lo está: siempre debe quedar uno para cobrar.
 * [image] es su imagen de cobro, como un código QR, si tiene una y su fila existe.
 */
data class PaymentMethodOptions(
    val method: PaymentMethodRecord,
    val hasSalePayments: Boolean,
    val activeCount: Int,
    val image: StoredImage? = null,
) {
    /** Si es el único vigente: no se archiva ni se elimina, porque no quedaría con qué cobrar. */
    val isLastActive: Boolean get() = !method.isArchived && activeCount <= 1

    /** Con ventas, el tipo queda en solo lectura. */
    val isKindLocked: Boolean get() = hasSalePayments

    /** Se archiva un vigente, salvo el último. */
    val canArchive: Boolean get() = !method.isArchived && !isLastActive

    /** Se reactiva un archivado. */
    val canReactivate: Boolean get() = method.isArchived

    /** Solo se elimina sin ventas: con ventas se archiva. Tampoco el último vigente. */
    val canDelete: Boolean get() = !hasSalePayments && !isLastActive
}
