package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

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

    /**
     * Todos los meses con su variación y su fuente, del más antiguo al más reciente, de nuevo cada
     * vez que cambian.
     */
    fun observeEntries(): Flow<List<PriceIndexEntry>>

    /** Guarda [entry], cuyo mes todavía no tiene variación, con su fuente. Devuelve su id. */
    suspend fun insertEntry(entry: PriceIndexEntry, createdAt: Instant): Long

    /** Cambia la variación y la fuente del mes de [entry], que ya tiene una. */
    suspend fun updateEntry(entry: PriceIndexEntry, updatedAt: Instant)

    /** Quita la variación de [month]. Devuelve si había una: nada depende de ella. */
    suspend fun delete(month: String): Boolean
}
