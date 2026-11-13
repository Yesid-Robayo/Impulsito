package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los billetes y monedas que escribió la persona, por su orden y luego por id. La base nace sin
 * ninguno. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface CashDenominationRepository {
    /** Todas, también las inactivas, para editarlas. */
    fun observeAll(): Flow<List<CashDenomination>>

    suspend fun findById(id: Long): CashDenomination?

    /** La guarda y devuelve su id. */
    suspend fun insert(value: Money, sortOrder: Int, createdAt: Instant): Long

    /** Cambia su valor, si está activa y su orden. */
    suspend fun update(
        id: Long,
        value: Money,
        isActive: Boolean,
        sortOrder: Int,
        updatedAt: Instant,
    )
}
