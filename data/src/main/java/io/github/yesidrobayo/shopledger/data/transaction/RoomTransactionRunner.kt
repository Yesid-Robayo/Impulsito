package io.github.yesidrobayo.shopledger.data.transaction

import androidx.room3.RoomDatabase
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteException
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Las transacciones de la app sobre Room: lo que ningún caso de uso debe recordar por su cuenta.
 *
 * - **Una sola transacción por escritura.** Los DAO que el bloque llama usan la conexión de la
 *   transacción, porque Room la lleva en el contexto de la corrutina. Si cualquier paso falla, o si
 *   el proceso muere antes de confirmar, SQLite deshace todo: no queda nada a medias.
 * - **Toma el bloqueo al empezar.** Room abre la escritura con `BEGIN IMMEDIATE`: leer un saldo y
 *   escribirlo después no puede chocar con otro escritor.
 * - **El cambio se cuenta dentro.** Con una confirmación, [ChangeCounter] suma el cambio en la misma
 *   transacción, justo antes de confirmar. Lo que corre después, con [CommitListener], va fuera.
 * - **Lo que no es del negocio no se cuenta.** Con [TxResult.CommitUncounted] se confirma por este
 *   mismo camino, con todo lo demás de esta lista, pero sin [ChangeCounter] ni [CommitListener]:
 *   no hay cambio que respaldar ni copia que programar.
 * - **No se cancela una vez empezada.** La persona ya confirmó: salir de la pantalla no la corta.
 * - **Sin restauración en curso.** Con el cerrojo de [RestoreLock] tomado, no empieza.
 * - **Fallas sin texto.** Un error de almacenamiento de SQLite sale como [StorageFailureException],
 *   sin el mensaje ni la causa original, que pueden traer SQL o datos. Si la base estaba ocupada,
 *   se intenta una vez más. Un error de programación, o lo que lance el propio bloque, sale tal cual.
 *
 * La escritura que da la base debe venir de `AppDatabase.open`, que deja la conexión de escritura
 * con `synchronous = FULL`: así lo que se confirma ya está en disco.
 */
internal class RoomTransactionRunner(
    private val database: RoomDatabase,
    private val changeCounter: ChangeCounter,
    private val restoreLock: RestoreLock,
    private val storageSpace: StorageSpace,
    private val clock: Clock,
    private val commitListener: CommitListener,
) : TransactionRunner {
    override suspend fun <T> write(block: suspend () -> TxResult<T>): T {
        // Una escritura dentro de otra contaría el cambio dos veces, y su confirmación no sería
        // real: la confirma la de afuera. Tampoco dentro de una lectura, que no puede escribir.
        check(currentCoroutineContext()[OpenTransactionKey] == null) {
            "Una transacción de escritura no se abre dentro de otra transacción"
        }
        return withContext(NonCancellable + OpenTransaction) {
            if (restoreLock.isHeld()) {
                throw StorageFailureException(StorageFailure.RESTORE_IN_PROGRESS)
            }
            when (val outcome = writeRetryingBusy(block)) {
                is Outcome.Committed -> {
                    notifyCommitted()
                    outcome.value
                }

                // Nada que programar: no se contó ningún cambio sin respaldar.
                is Outcome.CommittedUncounted -> outcome.value

                is Outcome.RolledBack -> outcome.value
            }
        }
    }

    override suspend fun <T> read(block: suspend () -> T): T = withContext(OpenTransaction) {
        try {
            database.withReadTransaction { block() }
        } catch (failure: SQLiteException) {
            throw storageFailureOf(failure)?.let(::StorageFailureException) ?: failure
        }
    }

    /**
     * Con un solo escritor, la base no debería estar ocupada. Si lo está, por ejemplo por otra
     * conexión que escribe al mismo archivo, Room ya esperó antes de fallar: se intenta una sola vez
     * más, desde el principio. Se puede porque el intento fallido se deshizo entero: casi siempre
     * falla al abrir la transacción, antes de que el bloque corra.
     */
    private suspend fun <T> writeRetryingBusy(block: suspend () -> TxResult<T>): Outcome<T> {
        var retriesLeft = BUSY_RETRIES
        while (true) {
            try {
                return writeOnce(block)
            } catch (failure: SQLiteException) {
                val kind = storageFailureOf(failure) ?: throw failure
                if (kind != StorageFailure.BUSY || retriesLeft == 0) {
                    throw StorageFailureException(kind)
                }
                retriesLeft--
            }
        }
    }

    private suspend fun <T> writeOnce(block: suspend () -> TxResult<T>): Outcome<T> =
        database.withWriteTransaction<Outcome<T>> {
            when (val result = block()) {
                is TxResult.Commit -> {
                    changeCounter.markChanged(clock.nowEpochMillis())
                    Outcome.Committed(result.value)
                }

                // Confirma igual al salir del bloque de Room, solo que sin contar.
                is TxResult.CommitUncounted -> Outcome.CommittedUncounted(result.value)

                // Deshace todo sin excepción: el rechazo de una regla es un resultado.
                is TxResult.Rollback -> rollback(Outcome.RolledBack(result.value))
            }
        }

    /**
     * La falla de almacenamiento de [failure], o `null` si no es una. Entonces es un error de
     * programación, que sale tal cual: no se disfraza de disco lleno.
     */
    private suspend fun storageFailureOf(failure: SQLiteException): StorageFailure? {
        val code = SqliteFailures.resultCode(failure) ?: return null
        val kind = SqliteFailures.storageFailureOf(code)
        // Con el disco casi lleno, SQLite también responde con errores de lectura o escritura.
        val diskFull = kind == StorageFailure.IO_ERROR && storageSpace.isLow()
        return if (diskFull) StorageFailure.DISK_FULL else kind
    }

    /**
     * El dato ya está confirmado y contado: una falla aquí no lo deshace, y no debe llegar a la
     * pantalla como si la venta no se hubiera guardado, porque la persona la repetiría. El arranque
     * siguiente programa lo que no se programó.
     */
    private fun notifyCommitted() {
        try {
            commitListener.onCommitted()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Ignorada a propósito: ver arriba.
        }
    }

    private sealed interface Outcome<out T> {
        val value: T

        class Committed<out T>(override val value: T) : Outcome<T>

        class CommittedUncounted<out T>(override val value: T) : Outcome<T>

        class RolledBack<out T>(override val value: T) : Outcome<T>
    }

    /**
     * Marca la corrutina que ya está dentro de una transacción de este runner. La llave es otro
     * objeto: un objeto que fuera su propia llave se pasaría a sí mismo, todavía sin crear, a su
     * constructor.
     */
    private object OpenTransaction : AbstractCoroutineContextElement(OpenTransactionKey)

    private object OpenTransactionKey : CoroutineContext.Key<OpenTransaction>

    private companion object {
        const val BUSY_RETRIES = 1
    }
}
