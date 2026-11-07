package io.github.yesidrobayo.shopledger.data.backup

import androidx.room3.useReaderConnection
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Los contadores con que el reemplazo comprueba que la base en su sitio es la que se preparó.
 *
 * `change_seq` sale de la fila única del estado del respaldo. `record_count` es la suma de filas de
 * los documentos y los libros, la misma cuenta que viaja con cada copia: una base vacía, o una que
 * no es la preparada, no la iguala.
 */
internal object RestoredDatabaseCounters {
    /** Las tablas que suman a `record_count`, en el orden de la documentación. */
    val RECORD_TABLES: List<String> =
        listOf(
            "sales",
            "purchases",
            "expenses",
            "stock_movements",
            "sale_returns",
            "purchase_returns",
            "customer_ledger_entries",
            "supplier_ledger_entries",
            "money_movements",
            "loyalty_ledger_entries",
        )

    /** `record_count` en una sola consulta. */
    val RECORD_COUNT_SQL: String =
        RECORD_TABLES.joinToString(" + ", prefix = "SELECT ") { "(SELECT COUNT(*) FROM $it)" }

    const val CHANGE_SEQ_SQL = "SELECT change_seq FROM backup_state WHERE id = 1"
}

/**
 * Verifica la base restaurada abriéndola con Room por [openRoom], como la abre la app, y leyendo sus
 * dos contadores. Si no abre, cierra lo que alcanzó a abrir y no deja pasar nada de la base.
 */
internal class RoomRestoredDatabaseVerifier(private val openRoom: suspend (File) -> AppDatabase) :
    RestoredDatabaseVerifier<AppDatabase> {
    override suspend fun open(database: File): RestoredDatabaseCheck<AppDatabase> {
        val opened =
            try {
                openRoom(database)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return RestoredDatabaseCheck.Failed
            }
        return try {
            val (changeSeq, records) = opened.useReaderConnection { connection ->
                val changeSeq = connection.usePrepared(RestoredDatabaseCounters.CHANGE_SEQ_SQL) {
                    if (it.step()) it.getLong(0) else -1L
                }
                val records = connection.usePrepared(RestoredDatabaseCounters.RECORD_COUNT_SQL) {
                    check(it.step()) { "La cuenta de registros no devolvió filas" }
                    it.getLong(0)
                }
                changeSeq to records
            }
            RestoredDatabaseCheck.Opened(changeSeq, records, opened) { opened.close() }
        } catch (cancellation: CancellationException) {
            opened.close()
            throw cancellation
        } catch (_: Exception) {
            opened.close()
            RestoredDatabaseCheck.Failed
        }
    }
}
