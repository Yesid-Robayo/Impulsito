package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSession
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionLine
import io.github.yesidrobayo.shopledger.domain.model.stock.CountSessionStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewCountSession
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las sesiones de conteo y sus líneas. Los ajustes que deja aplicar una sesión no se escriben aquí:
 * los agrega [StockLedgerRepository.append], en la misma transacción.
 *
 * Las líneas solo cambian mientras su sesión está abierta, y una sesión terminada no vuelve a
 * abrirse. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface CountSessionRepository {
    /** Las sesiones abiertas, en el orden en que se abrieron. */
    fun observeOpen(): Flow<List<CountSession>>

    /** Las sesiones terminadas, de la más nueva a la más antigua. */
    fun observeFinished(): Flow<List<CountSession>>

    fun observeLines(sessionId: Long): Flow<List<CountSessionLine>>

    suspend fun findById(id: Long): CountSession?

    /** La sesión abierta de [locationId], o `null`: hay a lo sumo una por ubicación. */
    suspend fun findOpenAt(locationId: Long): CountSession?

    suspend fun findLines(sessionId: Long): List<CountSessionLine>

    /** Guarda la sesión, abierta, y devuelve su id. */
    suspend fun insert(session: NewCountSession): Long

    /** Guarda una línea de [sessionId] con lo esperado congelado, sin contar, y devuelve su id. */
    suspend fun insertLine(
        sessionId: Long,
        productId: Long,
        lotId: Long?,
        expected: QuantityMilli,
    ): Long

    /** Anota lo contado en la línea, o lo borra con `null`. Solo en una sesión abierta. */
    suspend fun setCounted(lineId: Long, counted: QuantityMilli?)

    /** Termina una sesión abierta como [status], en [finishedAt]. */
    suspend fun finish(id: Long, status: CountSessionStatus, finishedAt: Instant)

    /**
     * Los productos de la sesión con alguna fila del libro en su ubicación después de abrirla:
     * lo esperado de esos ya no es lo que hay, y la pantalla lo avisa antes de aplicar.
     */
    suspend fun productsMovedSinceOpen(sessionId: Long): Set<Long>
}
