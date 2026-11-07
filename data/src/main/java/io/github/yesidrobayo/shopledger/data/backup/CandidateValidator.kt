package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.execSQL
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaCatalog
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Decide si una base candidata se puede restaurar: la de una copia que llega para reemplazar a la
 * base activa, o la instantánea de la que sale una copia nueva. Es el mismo código en los dos
 * caminos, para que una copia que la app no podría restaurar se note al crearla y no el día que hace
 * falta.
 *
 * Toda candidata es ajena, aunque la haya escrito esta app: estuvo fuera de su carpeta privada. Por
 * eso se revisa en este orden, y cada paso solo corre si el anterior pasó:
 *
 * 1. **El encabezado, por sus bytes,** antes de que SQLite abra nada ([CandidateHeader]).
 * 2. **Una conexión aislada:** propia, de solo lectura, con el SQLite empaquetado y nunca con la
 *    conexión de la base activa ni con Room. Antes de abrir se borran los archivos de diario que
 *    pudiera haber a su lado. Al abrir se apagan las funciones del esquema que no son inocuas, se
 *    revisa cada página que se lee, no se mapea el archivo en memoria y se prohíbe escribir; y cada
 *    pragma se lee de vuelta, porque uno desconocido se ignora sin error.
 * 3. **El esquema contra la lista permitida de su versión** ([CandidateSchema]), antes que
 *    `integrity_check`: esa revisión evalúa los `CHECK` del esquema y recorre las tablas de
 *    búsqueda, y sobre un esquema ajeno correría código ajeno. Con el esquema esperado, toda
 *    expresión que se evalúe después es de la app.
 * 4. **`integrity_check` completo:** solo vale una fila `ok`. No se usa `quick_check`, que no revisa
 *    índices.
 * 5. **Los datos** ([CandidateDataCheck]): la lista cerrada de comprobaciones, con su clase.
 *
 * Solo lee: nunca escribe en la candidata. No registra nada: quien la llama decide qué guardar del
 * resultado, que solo lleva códigos y conteos.
 *
 * @param ioContext contexto de la revisión, fuera del hilo principal.
 * @param appSchemaVersion la versión del esquema que compila esta app.
 */
internal class CandidateValidator(
    private val ioContext: CoroutineContext,
    private val appSchemaVersion: Int = AppDatabase.SCHEMA_VERSION,
) {
    private val driver = BundledSQLiteDriver()

    /** Revisa la candidata en [candidate], un archivo de nombre fijo en una carpeta privada. */
    suspend fun validate(candidate: File, context: CandidateContext): CandidateValidation =
        withContext(ioContext) {
            try {
                check(candidate, context)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (SqliteFailure.isCorruption(failure)) {
                    CandidateValidation.Refused(CandidateRejection.INTEGRITY_FAILED)
                } else {
                    CandidateValidation.Failed(failure)
                }
            }
        }

    private fun check(candidate: File, context: CandidateContext): CandidateValidation {
        val header = CandidateHeader.read(candidate, appSchemaVersion)
        val version = when (header) {
            is HeaderResult.Rejected -> return CandidateValidation.Refused(header.reason)
            is HeaderResult.Valid -> header.schemaVersion
        }
        val catalog = requireNotNull(SchemaCatalog.forVersion(version)) {
            "Falta el catálogo de la versión $version del esquema"
        }
        for (suffix in SIDE_FILES) File(candidate.path + suffix).delete()
        return driver.open(candidate.path, SQLITE_OPEN_READONLY).use { connection ->
            when {
                !connection.isolate() -> CandidateValidation.Refused(
                    CandidateRejection.ISOLATION_FAILED,
                )

                CandidateSchema.differences(connection, catalog).isNotEmpty() ->
                    CandidateValidation.Refused(CandidateRejection.SCHEMA_MISMATCH)

                !connection.isIntact() -> CandidateValidation.Refused(
                    CandidateRejection.INTEGRITY_FAILED,
                )

                else -> {
                    val findings =
                        CandidateDataCheck(catalog, version, context).findings(connection)
                    CandidateValidation.Checked(version, CandidateVerdict.of(findings))
                }
            }
        }
    }

    /** Pone las protecciones de una conexión sobre una base ajena, y comprueba que quedaron. */
    private fun SQLiteConnection.isolate(): Boolean {
        for ((pragma, value) in ISOLATION_PRAGMAS) execSQL("PRAGMA $pragma = $value")
        return ISOLATION_PRAGMAS.all { (pragma, value) -> singleLong("PRAGMA $pragma") == value }
    }

    private fun SQLiteConnection.isIntact(): Boolean = prepare("PRAGMA integrity_check").use {
        it.step() && it.getText(0) == INTEGRITY_OK && !it.step()
    }

    private companion object {
        const val INTEGRITY_OK = "ok"

        /** Los archivos de diario que SQLite usaría junto a la candidata. */
        val SIDE_FILES = listOf("-journal", "-wal", "-shm")

        /**
         * Sin funciones del esquema que no sean inocuas, con cada página revisada al leerla, sin
         * mapear el archivo en memoria y sin escribir.
         */
        val ISOLATION_PRAGMAS =
            listOf(
                "trusted_schema" to 0L,
                "cell_size_check" to 1L,
                "mmap_size" to 0L,
                "query_only" to 1L,
            )
    }
}

/** Lo que concluyó el validador sobre una candidata. */
internal sealed interface CandidateValidation {
    /**
     * La candidata no es una base de la app que se pueda abrir con confianza: un rechazo fatal, con
     * su código, antes de revisar sus datos o al no poder leerlos.
     */
    data class Refused(val reason: CandidateRejection) : CandidateValidation

    /** Estructura sana, de la versión [schemaVersion] del esquema, y lo que dicen sus datos. */
    data class Checked(val schemaVersion: Int, val verdict: CandidateVerdict) : CandidateValidation

    /**
     * No se pudo revisar, por una falla que no dice nada de la candidata. Se puede reintentar. Solo
     * la clase de [cause] puede ir al registro: su mensaje puede llevar rutas.
     */
    class Failed(val cause: Exception) : CandidateValidation
}
