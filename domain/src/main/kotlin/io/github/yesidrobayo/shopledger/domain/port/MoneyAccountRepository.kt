package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las cuentas de dinero. El saldo de una cuenta no se escribe aquí: lo mueve solo
 * [MoneyLedgerRepository.append], en la transacción que agrega su movimiento.
 *
 * Las listas van en el orden en que se crearon las cuentas, por id, que no depende del idioma. Las
 * lecturas que devuelven [Flow] vuelven a emitir con cada transacción confirmada que cambia lo leído.
 * Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface MoneyAccountRepository {
    fun observe(filter: ArchiveFilter): Flow<List<MoneyAccount>>

    suspend fun findById(id: Long): MoneyAccount?

    /** La cuenta vigente con ese nombre normalizado, si hay una: una archivada no reserva el suyo. */
    suspend fun findActiveByNormalizedName(normalizedName: String): MoneyAccount?

    /** Guarda la cuenta, vigente y con saldo cero, y devuelve su id. */
    suspend fun insert(account: NewMoneyAccount): Long

    /** Cambia lo que la persona escribe. No toca la moneda, el saldo ni el archivo. */
    suspend fun update(id: Long, details: MoneyAccountDetails, updatedAt: Instant)

    /** La archiva en [archivedAt], o la reactiva con `null`. */
    suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant)
}
