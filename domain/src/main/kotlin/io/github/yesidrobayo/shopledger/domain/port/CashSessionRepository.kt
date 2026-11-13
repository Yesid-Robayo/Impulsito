package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashClosing
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.NewCashSession
import kotlinx.coroutines.flow.Flow

/**
 * Las sesiones de caja. Una sesión nace abierta y solo cambia al cerrarse, una vez; ninguna se
 * borra. Hay como máximo una abierta por cuenta.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir con cada transacción confirmada que cambia lo
 * leído. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface CashSessionRepository {
    /** Las sesiones de una cuenta, de la más reciente a la más vieja. */
    fun observeByAccount(accountId: Long): Flow<List<CashSession>>

    suspend fun findById(id: Long): CashSession?

    /** La sesión abierta de la cuenta, si hay una. */
    suspend fun findOpen(accountId: Long): CashSession?

    /**
     * Lo que movió la cuenta durante la sesión [sessionId], sin las diferencias de caja: lo esperado
     * es la apertura más esta suma.
     */
    suspend fun movementsTotal(sessionId: Long): Money

    /** La abre y devuelve su id. La base rechaza una segunda abierta en la misma cuenta. */
    suspend fun insert(session: NewCashSession): Long

    /** La cierra con [closing]. Devuelve `false` si ya estaba cerrada: no se toca. */
    suspend fun close(sessionId: Long, closing: CashClosing): Boolean
}
