package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.data.transaction.SqliteFailures
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException

/**
 * Cómo escriben los repositorios dentro de la transacción del caso de uso. Las pocas escrituras que
 * no cuentan como cambio del negocio van fuera de ella, por [StandaloneWrites].
 */
internal class RepositoryWrites(private val database: RoomDatabase) {
    /**
     * Corre una escritura que solo vale dentro de la transacción del caso de uso.
     *
     * - **Exige la transacción abierta.** Fuera de ella, cada sentencia se confirmaría sola: una fila
     *   del libro podría quedar sin su saldo si la siguiente falla, y el cambio no se contaría para
     *   la copia. Es un error de programación, y lanza [IllegalStateException] antes de escribir.
     * - **Una regla rota sale tipada.** Lo que la base rechaza por una guarda, un índice único o una
     *   llave `RESTRICT` sale como [StorageFailure.INVARIANT_VIOLATION], sin el texto de SQLite, que
     *   puede traer datos. El resto de los errores de SQLite siguen tal cual hacia la transacción,
     *   que decide si es disco lleno o si reintenta una base ocupada.
     */
    suspend fun <T> inTransaction(block: suspend () -> T): T {
        val open = database.useWriterConnection { it.inTransaction() }
        check(open) { "Esta escritura solo corre dentro de la transacción del caso de uso" }
        return try {
            block()
        } catch (failure: SQLiteException) {
            throw constraintFailureOf(failure) ?: failure
        }
    }

    private fun constraintFailureOf(failure: SQLiteException): StorageFailureException? {
        val kind = SqliteFailures.resultCode(failure)?.let(SqliteFailures::storageFailureOf)
        val broken = kind == StorageFailure.INVARIANT_VIOLATION
        return if (broken) StorageFailureException(StorageFailure.INVARIANT_VIOLATION) else null
    }
}

/**
 * Las escrituras de una sola sentencia que no cuentan como cambio del negocio, como una preferencia,
 * el fin del primer uso o el estado del aviso de una alerta. Van sin la transacción del caso de uso.
 *
 * - **Con el cerrojo de la restauración.** Igual que la transacción de los casos de uso, ninguna
 *   entra mientras se restaura: escrita entre la copia de seguridad y el reemplazo, se perdería sin
 *   aviso. Sale como [StorageFailure.RESTORE_IN_PROGRESS], sin tocar la base.
 * - **Fallas sin texto.** Cualquier falla de almacenamiento sale como [StorageFailureException],
 *   sin el texto de SQLite. Un error que no es de almacenamiento sale tal cual.
 */
internal class StandaloneWrites(private val restoreLock: RestoreLock) {
    suspend fun <T> write(block: suspend () -> T): T {
        if (restoreLock.isHeld()) throw StorageFailureException(StorageFailure.RESTORE_IN_PROGRESS)
        return try {
            block()
        } catch (failure: SQLiteException) {
            val kind = SqliteFailures.resultCode(failure)?.let(SqliteFailures::storageFailureOf)
            throw kind?.let(::StorageFailureException) ?: failure
        }
    }
}
