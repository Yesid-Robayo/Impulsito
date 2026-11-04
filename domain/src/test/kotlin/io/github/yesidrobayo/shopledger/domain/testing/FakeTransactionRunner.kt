package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/**
 * Un [TransactionRunner] sobre [InMemoryDatabase] que cuenta cuántas transacciones abre cada caso de
 * uso y cómo termina cada una.
 *
 * Hace lo que el contrato pide a la base real: con `Commit` confirma y cuenta un cambio, con
 * `Rollback` o una excepción deshace todo, y con [restoreInProgress] rechaza la escritura antes de
 * empezarla.
 */
internal class FakeTransactionRunner(private val database: InMemoryDatabase) : TransactionRunner {
    var restoreInProgress: Boolean = false

    var writeCalls: Int = 0
        private set
    var readCalls: Int = 0
        private set
    var commits: Int = 0
        private set
    var rollbacks: Int = 0
        private set

    override suspend fun <T> write(block: suspend () -> TxResult<T>): T {
        writeCalls++
        if (restoreInProgress) throw StorageFailureException(StorageFailure.RESTORE_IN_PROGRESS)
        database.begin()
        val result = try {
            block()
        } catch (failure: Throwable) {
            // Cualquier falla deshace todo y sigue su camino, también una cancelación.
            database.rollback()
            rollbacks++
            throw failure
        }
        return when (result) {
            is TxResult.Commit -> {
                database.commit()
                commits++
                result.value
            }

            is TxResult.Rollback -> {
                database.rollback()
                rollbacks++
                result.value
            }
        }
    }

    override suspend fun <T> read(block: suspend () -> T): T {
        readCalls++
        return block()
    }
}
