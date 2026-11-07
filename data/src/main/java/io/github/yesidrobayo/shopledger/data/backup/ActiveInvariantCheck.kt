package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck
import io.github.yesidrobayo.shopledger.domain.model.backup.FindingSeverity
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * La verificación diaria de la base activa: las mismas consultas de invariantes que revisan cada
 * copia, ahora sobre la base del negocio, para que un saldo que no coincide con su libro se note
 * aunque no haya copias.
 *
 * - **Solo cuenta.** La app nunca corrige sola la base activa: la salida es restaurar la cabeza, que
 *   recalcula los saldos con confirmación. Por eso solo importan los hallazgos reparables, los que
 *   esa restauración arregla, y de cada uno solo el número de casos, nunca cuáles ni sus datos.
 * - **Una conexión propia.** Se abre con el SQLite empaquetado, como la instantánea, y nunca con
 *   una de Room: las consultas recorren la base entera, y en una lectora de Room demorarían a las
 *   pantallas. Se abre sin la opción de crear, y todas las consultas van en una sola transacción de
 *   lectura: ven la misma foto de la base aunque una venta confirme mientras tanto.
 * - **Sin escribir.** La transacción termina sin confirmar nada.
 *
 * @param databaseFile la base del negocio, con ruta absoluta.
 * @param ioContext contexto de la revisión, fuera del hilo principal.
 */
internal class ActiveInvariantCheck(
    private val databaseFile: File,
    private val ioContext: CoroutineContext,
) {
    private val driver = BundledSQLiteDriver()

    /** Cuántos elementos tienen un saldo que no coincide con su libro, según la base de ahora. */
    suspend fun check(): ActiveInvariants = withContext(ioContext) {
        if (!databaseFile.isFile) return@withContext ActiveInvariants.DatabaseMissing
        try {
            driver.open(databaseFile.path, SQLITE_OPEN_READWRITE).use { connection ->
                connection.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
                ActiveInvariants.Checked(connection.inReadTransaction { repairCases() })
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ActiveInvariants.Failed(failure)
        }
    }

    /**
     * Los elementos con un saldo que recalcular. Un producto descuadrado falla a la vez en su stock
     * y en el saldo de su cabecera: se toma el hallazgo con más casos, como al revisar una copia,
     * para no contarlo dos veces.
     */
    private fun SQLiteConnection.repairCases(): Long {
        val cases = mutableMapOf<CandidateCheck, Long>()
        fun add(check: CandidateCheck, count: Long) {
            if (check.severity == FindingSeverity.REPAIRABLE && count > 0) {
                cases.merge(check, count, Long::plus)
            }
        }
        for (query in InvariantQueries.MODEL) {
            if (query.check.severity == FindingSeverity.REPAIRABLE) {
                add(query.check, countRows(query.sql))
            }
        }
        add(CandidateCheck.COST_AND_TOTALS, CostReplays.of(this).count { it.drifted }.toLong())
        return cases.values.maxOrNull() ?: 0
    }

    private inline fun <T> SQLiteConnection.inReadTransaction(block: SQLiteConnection.() -> T): T {
        execSQL("BEGIN")
        try {
            return block()
        } finally {
            execSQL("ROLLBACK")
        }
    }

    private companion object {
        /** Cinco segundos de espera ante un cerrojo ocupado, como la instantánea. */
        const val BUSY_TIMEOUT_MILLIS = 5_000
    }
}

/** Lo que dio la verificación diaria de la base activa. */
internal sealed interface ActiveInvariants {
    /**
     * La base se revisó entera: [repairCases] elementos tienen un saldo que no coincide con su
     * libro, o 0 si ninguno.
     */
    data class Checked(val repairCases: Long) : ActiveInvariants

    /** No existe la base: no hay nada que revisar, y nunca se crea una. */
    data object DatabaseMissing : ActiveInvariants

    /**
     * No se pudo revisar, por una falla de SQLite o del disco. Mañana se intenta de nuevo, y cada
     * copia corre la misma revisión. Solo la clase de [cause] puede ir al registro.
     */
    class Failed(val cause: Exception) : ActiveInvariants
}
