package io.github.yesidrobayo.shopledger.domain.model.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * Los datos personales de un cliente, tal como se guardaron: solo el nombre es obligatorio, y
 * [normalizedName] es [name] pasado por `TextNormalizer`. No es único: dos personas pueden llamarse
 * igual. No hay documento de identidad, porque no hace falta para venderle ni para cobrarle.
 */
data class CustomerContact(
    val name: String,
    val normalizedName: String,
    val phone: String?,
    val email: String?,
    val notes: String?,
)

/**
 * Lo que la persona escribe de un cliente, ya validado: sus datos personales y la lista de precios
 * que se le aplica, si tiene una.
 */
data class CustomerDetails(val contact: CustomerContact, val priceListId: Long?)

/**
 * Un cliente guardado.
 *
 * [contact] es `null` en un cliente anonimizado: sus datos personales se borraron y se muestra como
 * cliente anónimo, pero su libro y sus ventas siguen intactos. [balance] es lo que debe: positivo
 * debe y negativo es saldo a favor. [pointsBalance] son sus puntos, que no son dinero. Los dos son
 * saldos derivados de sus libros: editar al cliente nunca los toca. Con historia ya no se borra: se
 * archiva, y [archivedAt] dice desde cuándo.
 */
data class Customer(
    val id: Long,
    val contact: CustomerContact?,
    val priceListId: Long?,
    val balance: Money,
    val pointsBalance: Long,
    val anonymizedAt: Instant?,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Si está archivado: no se ofrece al vender, pero sus saldos siguen contando. */
    val isArchived: Boolean get() = archivedAt != null

    /** Si se anonimizó: ya no tiene datos personales, y eso no se deshace. */
    val isAnonymized: Boolean get() = anonymizedAt != null

    /**
     * Si debe o tiene saldo a favor. Archivarlo o anonimizarlo no cambia ese saldo, y la pantalla
     * lo avisa antes.
     */
    val hasBalance: Boolean get() = balance != Money.ZERO

    /** Lo que se puede volver a editar: sus datos y su lista, o `null` si se anonimizó. */
    val details: CustomerDetails? get() = contact?.let { CustomerDetails(it, priceListId) }
}
