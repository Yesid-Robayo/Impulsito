package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaCatalog
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El corpus de archivos hostiles: cada uno se fabrica desde una copia sana y se rechaza con su
 * código. Ninguno se versiona. Todos los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class CandidateCorpusTest {
    @Before
    fun clean() {
        CandidateFixtures.clean()
    }

    @After
    fun cleanUp() {
        CandidateFixtures.clean()
    }

    @Test
    fun aTableReplacedByAViewIsRefused() = assertSchemaRefused("vista") {
        execSQL("DROP TABLE message_templates")
        execSQL("CREATE VIEW message_templates AS SELECT * FROM tags")
    }

    @Test
    fun aTriggerOnSalesIsRefused() = assertSchemaRefused("disparador") {
        execSQL("CREATE TRIGGER sales_spy AFTER INSERT ON sales BEGIN SELECT 1; END")
    }

    @Test
    fun anAlteredOrMissingGuardIsRefused() {
        val guard = SchemaCatalog.current.triggers.first { it.name == "guard_sales_void_only" }
        assertSchemaRefused("guarda alterada") {
            execSQL("DROP TRIGGER ${guard.name}")
            execSQL(guard.sql.replace("BEGIN SELECT", "BEGIN  SELECT"))
        }
        assertSchemaRefused("guarda faltante") { execSQL("DROP TRIGGER ${guard.name}") }
        val tagsGuard = SchemaCatalog.current.triggers.first { it.table == "tags" }
        assertSchemaRefused("guarda en otra tabla") {
            execSQL("DROP TRIGGER ${tagsGuard.name}")
            execSQL(tagsGuard.sql.replace(" ON tags", " ON categories"))
        }
    }

    @Test
    fun anExtraTableOrIndexIsRefused() {
        assertSchemaRefused("tabla extra") {
            execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY)")
        }
        assertSchemaRefused("índice extra") { execSQL("CREATE INDEX sales_note ON sales (note)") }
        assertSchemaRefused("índice parcial") {
            execSQL("CREATE INDEX sales_open ON sales (status) WHERE status = 'ACTIVE'")
        }
    }

    @Test
    fun aVirtualTableIsRefusedBeforeAnyOfItsCodeRuns() {
        assertSchemaRefused("tabla virtual") {
            execSQL("CREATE VIRTUAL TABLE search USING fts5(body)")
        }
        // Una tabla de búsqueda con sus tablas internas alteradas: integrity_check recorrería su
        // código, y el esquema la rechaza antes.
        assertSchemaRefused("tabla de búsqueda alterada") {
            execSQL("CREATE VIRTUAL TABLE search USING fts5(body)")
            execSQL("INSERT INTO search (body) VALUES ('texto inventado')")
            execSQL("UPDATE search_data SET block = x'00ffffffff' WHERE id > 1")
            execSQL("DROP TABLE search_docsize")
        }
    }

    @Test
    fun anAddedCheckOrGeneratedColumnIsRefused() {
        assertSchemaRefused("CHECK añadido") {
            rebuild("tags") { it.replaceAfterLast(")", "").removeSuffix(")") + ", CHECK (id > 0))" }
        }
        assertSchemaRefused("columna generada") {
            execSQL(
                "ALTER TABLE tags ADD COLUMN shout TEXT GENERATED ALWAYS AS (upper(name)) VIRTUAL",
            )
        }
        assertSchemaRefused("valor por defecto") {
            rebuild("tags") {
                it.replace("`name` TEXT NOT NULL", "`name` TEXT NOT NULL DEFAULT (random())")
            }
        }
    }

    @Test
    fun aRebuiltTableWithTheSameStructureIsStillAccepted() {
        val candidate = CandidateFixtures.copyOf("rebuilt")
        corrupt(candidate) { rebuild("tags") { it } }
        assertClean("tabla reconstruida", candidate)
    }

    @Test
    fun aSchemaVersionTheAppDoesNotKnowIsRefused() {
        assertRefused("versión futura", CandidateRejection.SCHEMA_TOO_NEW) {
            execSQL("PRAGMA user_version = 2")
        }
        assertRefused("versión cero", CandidateRejection.UNKNOWN_SCHEMA_VERSION) {
            execSQL("PRAGMA user_version = 0")
        }
    }

    @Test
    fun aDatabaseInWriteAheadModeIsRefused() {
        val candidate = CandidateFixtures.copyOf("wal")
        corrupt(candidate) { texts("PRAGMA journal_mode = WAL") }
        for (suffix in listOf("-wal", "-shm")) File(candidate.path + suffix).delete()

        assertEquals(
            CandidateValidation.Refused(CandidateRejection.WAL_DATABASE),
            validate(candidate),
        )
    }

    @Test
    fun aTextFileIsRefused() {
        val candidate = File(File(CandidateFixtures.root, "text"), "candidate.db")
        requireNotNull(candidate.parentFile).mkdirs()
        candidate.writeText("solo texto inventado, que no es una base de datos\n".repeat(200))

        assertEquals(
            CandidateValidation.Refused(CandidateRejection.NOT_A_DATABASE),
            validate(candidate),
        )
    }

    @Test
    fun aTruncatedFileIsRefused() {
        val candidate = CandidateFixtures.copyOf("truncated")
        val pages = candidate.length() / PAGE
        RandomAccessFile(candidate, "rw").use { it.setLength((pages / 2) * PAGE) }
        assertEquals(
            CandidateValidation.Refused(CandidateRejection.NOT_A_DATABASE),
            validate(candidate),
        )

        val ragged = CandidateFixtures.copyOf("ragged")
        RandomAccessFile(ragged, "rw").use { it.setLength(ragged.length() - 100) }
        assertEquals(
            CandidateValidation.Refused(CandidateRejection.NOT_A_DATABASE),
            validate(ragged),
        )
    }

    @Test
    fun aDamagedPageIsRefused() {
        val candidate = CandidateFixtures.copyOf("damaged")
        val pages = candidate.length() / PAGE
        assertTrue("La base de prueba es muy chica: $pages páginas", pages > 20)
        RandomAccessFile(candidate, "rw").use { file ->
            // Una página del medio, lejos del esquema, con basura determinista.
            file.seek((pages / 2) * PAGE + 8)
            file.write(ByteArray(PAGE.toInt() - 16) { (it * 31).toByte() })
        }

        assertEquals(
            CandidateValidation.Refused(CandidateRejection.INTEGRITY_FAILED),
            validate(candidate),
        )
    }

    @Test
    fun textInAMoneyColumnIsFatal() = assertFatal("texto en dinero", CandidateCheck.STORAGE_TYPES) {
        withoutGuards("sales") {
            updateFirst("sales", "total = 'mucho'")
        }
    }

    @Test
    fun anUnknownLedgerTypeIsFatal() =
        assertFatal("tipo desconocido", CandidateCheck.LEDGER_ENUMS) {
            withoutGuards("stock_movements") {
                updateFirst("stock_movements", "type = 'TELEPORT'")
            }
        }

    @Test
    fun anUnknownDocumentStatusIsFatal() =
        assertFatal("estado desconocido", CandidateCheck.LEDGER_ENUMS) {
            withoutGuards("sales") {
                updateFirst("sales", "status = 'PAUSED'")
            }
        }

    @Test
    fun anUnconfirmedCurrencyWithMoneyRecordedIsFatal() =
        assertFatal("moneda sin confirmar", CandidateCheck.LEDGER_ENUMS) {
            withoutGuards("app_settings") {
                execSQL("UPDATE app_settings SET currency_code = 'XXX'")
            }
        }

    @Test
    fun aBrokenForeignKeyIsFatal() = assertFatal("llave rota", CandidateCheck.FOREIGN_KEYS) {
        withoutGuards("sale_lines") {
            updateFirst("sale_lines", "product_id = 999999")
        }
    }

    @Test
    fun anotherSchemaFingerprintIsFatal() =
        assertFatal("otra huella", CandidateCheck.SCHEMA_IDENTITY) {
            execSQL(
                "UPDATE room_master_table SET identity_hash = '00000000000000000000000000000000'",
            )
        }

    @Test
    fun aMissingFingerprintIsFatal() = assertFatal("sin huella", CandidateCheck.SCHEMA_IDENTITY) {
        execSQL("DELETE FROM room_master_table")
    }

    private fun assertSchemaRefused(case: String, change: SQLiteConnection.() -> Unit) =
        assertRefused(case, CandidateRejection.SCHEMA_MISMATCH, change)

    private fun assertRefused(
        case: String,
        reason: CandidateRejection,
        change: SQLiteConnection.() -> Unit,
    ) {
        val candidate = CandidateFixtures.copyOf("corpus")
        corrupt(candidate, change)
        assertEquals(case, CandidateValidation.Refused(reason), validate(candidate))
    }

    private fun assertFatal(
        case: String,
        check: CandidateCheck,
        change: SQLiteConnection.() -> Unit,
    ) {
        val candidate = CandidateFixtures.copyOf("corpus")
        corrupt(candidate, change)
        val result = validate(candidate)
        assertTrue("$case: $result", result is CandidateValidation.Checked)
        val verdict = (result as CandidateValidation.Checked).verdict
        assertTrue("$case: $verdict", verdict is CandidateVerdict.Rejected)
        val fatal = (verdict as CandidateVerdict.Rejected).fatal.map { it.check }
        assertTrue("$case: $fatal", check in fatal)
        assertEquals(case, CandidateVerdict.Rejected::class, verdict::class)
    }

    /**
     * Reconstruye [table] como lo haría una migración, con el texto de creación que da [sql]: tabla
     * nueva, copia de las filas, borrado de la vieja y renombrado. Las guardas que nombran la tabla
     * impiden renombrarla, así que todas se retiran antes y se vuelven a crear después con su texto,
     * igual que los índices de la tabla.
     */
    private fun SQLiteConnection.rebuild(table: String, sql: (String) -> String) {
        val created =
            texts("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '$table'").single()
        val indices =
            texts(
                "SELECT sql FROM sqlite_master " +
                    "WHERE type = 'index' AND tbl_name = '$table' AND sql IS NOT NULL",
            )
        val guards = SchemaCatalog.current.triggers
        for (guard in guards) execSQL("DROP TRIGGER ${guard.name}")
        execSQL(sql(created).replaceFirst("`$table`", "`${table}_rebuilt`"))
        execSQL("INSERT INTO ${table}_rebuilt SELECT * FROM $table")
        execSQL("DROP TABLE $table")
        execSQL("ALTER TABLE ${table}_rebuilt RENAME TO $table")
        for (index in indices) execSQL(index)
        for (guard in guards) execSQL(guard.sql)
    }

    private companion object {
        /** El tamaño de página de las bases de la app. */
        const val PAGE = 4096L
    }
}
