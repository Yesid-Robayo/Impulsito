package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import io.github.yesidrobayo.shopledger.data.transaction.SqliteFailures
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException

/**
 * Cómo escriben los repositorios: dentro de la transacción del caso de uso, o, las pocas escrituras
 * que no cuentan como cambio del negocio, fuera de ella.
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

    /**
     * Corre una escritura de una sola sentencia que no cuenta como cambio del negocio, como una
     * preferencia o el estado del aviso de una alerta. Va sin la transacción del caso de uso, y por
     * eso traduce aquí cualquier falla de almacenamiento: sale como [StorageFailureException], sin
     * texto. Un error que no es de almacenamiento sale tal cual.
     */
    suspend fun <T> standalone(block: suspend () -> T): T = try {
        block()
    } catch (failure: SQLiteException) {
        val kind = SqliteFailures.resultCode(failure)?.let(SqliteFailures::storageFailureOf)
        throw kind?.let(::StorageFailureException) ?: failure
    }

    private fun constraintFailureOf(failure: SQLiteException): StorageFailureException? {
        val kind = SqliteFailures.resultCode(failure)?.let(SqliteFailures::storageFailureOf)
        val broken = kind == StorageFailure.INVARIANT_VIOLATION
        return if (broken) StorageFailureException(StorageFailure.INVARIANT_VIOLATION) else null
    }
}
