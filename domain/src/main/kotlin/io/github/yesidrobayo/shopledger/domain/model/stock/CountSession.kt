package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant

/** En qué está una sesión de conteo. Es un valor guardado en la base: su nombre no cambia nunca. */
enum class CountSessionStatus {
    /** Se está contando: sus líneas todavía cambian. */
    OPEN,

    /** Se aplicó: sus diferencias quedaron como ajustes en el libro. */
    APPLIED,

    /** Se canceló sin escribir ningún movimiento. */
    CANCELLED,
}

/**
 * Una sesión de conteo de una ubicación, y de una categoría si se eligió. Con [isBlind], quien
 * cuenta no ve lo esperado hasta aplicar: así no se deja llevar por el número.
 */
data class CountSession(
    val id: Long,
    val status: CountSessionStatus,
    val isBlind: Boolean,
    val locationId: Long,
    val categoryId: Long?,
    val note: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val businessDate: BusinessDate,
)

/** Una sesión por guardar, abierta. */
data class NewCountSession(
    val isBlind: Boolean,
    val locationId: Long,
    val categoryId: Long?,
    val note: String?,
    val startedAt: Instant,
    val businessDate: BusinessDate,
)

/**
 * Un producto de la sesión. [expected] es lo que el libro decía de la ubicación al abrir, congelado:
 * lo que se mueva después no lo cambia. [counted] es lo contado, o `null` mientras no se cuenta.
 */
data class CountSessionLine(
    val id: Long,
    val sessionId: Long,
    val productId: Long,
    val lotId: Long?,
    val expected: QuantityMilli,
    val counted: QuantityMilli?,
) {
    /** Lo que el ajuste corregiría al aplicar, o `null` sin contar. */
    val difference: QuantityMilli? get() = counted?.let { it - expected }
}

/** Lo que la persona pide al abrir un conteo. [note] tal cual; en blanco es ninguna. */
data class OpenCountRequest(
    val locationId: Long,
    val categoryId: Long? = null,
    val isBlind: Boolean = true,
    val note: String? = null,
)
