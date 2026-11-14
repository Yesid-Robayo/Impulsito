package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import kotlin.time.Instant

/**
 * Las variaciones mensuales del índice de precios que escribe o importa la persona, una por mes
 * `AAAA-MM`. La app nunca descarga un índice ni trae series de ningún país.
 *
 * Las escrituras solo corren dentro de la transacción del caso de uso.
 */
interface PriceIndexRepository {
    /** Los meses que ya tienen variación. */
    suspend fun months(): Set<String>

    /** Guarda la variación de [month], que todavía no tiene. Devuelve el id nuevo. */
    suspend fun insert(month: String, change: BasisPoints, createdAt: Instant): Long

    /** Cambia la variación de [month], que ya tiene una, y conserva su nota. */
    suspend fun update(month: String, change: BasisPoints, updatedAt: Instant)
}
