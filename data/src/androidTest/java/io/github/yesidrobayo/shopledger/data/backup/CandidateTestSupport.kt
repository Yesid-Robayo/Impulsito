package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaCatalog
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.seed.SPANISH_ROW
import io.github.yesidrobayo.shopledger.data.seed.SeedDataGenerator
import io.github.yesidrobayo.shopledger.data.seed.SeedProfile
import io.github.yesidrobayo.shopledger.data.seed.assertInvariantsHold
import io.github.yesidrobayo.shopledger.data.seed.seedConfigOf
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateFinding
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

// Apoyo de las pruebas del validador de copias. Las bases salen del generador de datos de prueba:
// todo lo que contienen es inventado. Los archivos dañados se fabrican en cada prueba y nunca se
// versionan.

/** Las bases de prueba de las que salen las candidatas. */
internal object CandidateFixtures {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** La carpeta privada de las candidatas de prueba. */
    val root: File = File(context.noBackupFilesDir, "candidate-test")

    private val seeds = mutableMapOf<RegionalMatrixRow, File>()

    /**
     * La base generada para [row], una por proceso: generar el perfil chico tarda segundos, y cada
     * prueba trabaja sobre su propia copia.
     */
    @Synchronized
    fun seed(row: RegionalMatrixRow = SPANISH_ROW): File = seeds.getOrPut(row) {
        val config = requireNotNull(seedConfigOf(row)) { "La fila no tiene negocio que generar" }
        val file = testDatabaseFile(context, "candidate-seed-${seeds.size}.db")
        deleteDatabaseFiles(file)
        runBlocking {
            val report =
                SeedDataGenerator(SeedDataGenerator.DEFAULT_SEED, SeedProfile.SMALL, config)
                    .generate(context, file)
            assertInvariantsHold("base de prueba", report)
        }
        file
    }

    /** Una candidata nueva y sana, con nombre fijo en su propia carpeta, sacada de [source]. */
    fun copyOf(name: String, source: File = seed()): File {
        val target = File(File(root, name), "candidate.db")
        target.parentFile?.deleteRecursively()
        val result = runBlocking { SnapshotCreator(source, Dispatchers.IO).create(target) }
        assertTrue("$result", result is SnapshotResult.Created)
        return target
    }

    /** Una candidata de una base recién creada por la app, sin ningún dato del negocio. */
    fun emptyCopy(name: String): File {
        val file = testDatabaseFile(context, "candidate-empty.db")
        deleteDatabaseFiles(file)
        runBlocking { AppDatabase.open(context, file, Dispatchers.IO).close() }
        return copyOf(name, file).also { deleteDatabaseFiles(file) }
    }

    /** Borra las candidatas de prueba. Las bases generadas se conservan para el proceso. */
    fun clean() {
        root.deleteRecursively()
    }
}

/** Cambia [file] con una conexión propia, sin Room y sin llaves foráneas. */
internal fun corrupt(file: File, block: SQLiteConnection.() -> Unit) {
    inspectWithoutRoom(file) { it.block() }
}

/**
 * Corre [block] sin las guardas de [table], y las vuelve a poner con su texto exacto: así la base
 * guarda un dato que la app nunca escribiría, con el esquema intacto.
 */
internal fun SQLiteConnection.withoutGuards(table: String, block: SQLiteConnection.() -> Unit) {
    val guards = SchemaCatalog.current.triggers.filter { it.table == table }
    for (guard in guards) execSQL("DROP TRIGGER ${guard.name}")
    block()
    for (guard in guards) execSQL(guard.sql)
}

/** El primer `id` de [table] que cumple [where]. */
internal fun SQLiteConnection.firstId(table: String, where: String = "1"): Long =
    prepare("SELECT id FROM $table WHERE $where ORDER BY id LIMIT 1").use { statement ->
        check(statement.step()) { "No hay filas en $table que cumplan $where" }
        statement.getLong(0)
    }

/** Cambia con [set] la primera fila de [table] que cumple [where]. */
internal fun SQLiteConnection.updateFirst(table: String, set: String, where: String = "1") {
    execSQL("UPDATE $table SET $set WHERE id = ${firstId(table, where)}")
}

/** Una huella de las filas de [tables] en [file], en el orden de sus ids. */
internal fun tablesDigest(file: File, tables: List<String>): String =
    inspectWithoutRoom(file) { connection ->
        val digest = MessageDigest.getInstance("SHA-256")
        for (table in tables) {
            connection.prepare("SELECT * FROM $table ORDER BY id").use { statement ->
                while (statement.step()) {
                    for (column in 0 until statement.getColumnCount()) {
                        digest.update(
                            (if (statement.isNull(column)) "∅" else statement.getText(column))
                                .toByteArray(),
                        )
                        digest.update(0)
                    }
                }
            }
        }
        digest.digest().toLowerHex()
    }

/** Las guardas que hay en [file], con su texto, por nombre. */
internal fun guardsIn(file: File): Map<String, String> = inspectWithoutRoom(file) { connection ->
    connection.texts("SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
        .associateWith { name ->
            connection.prepare("SELECT sql FROM sqlite_master WHERE name = ?").use {
                it.bindText(1, name)
                check(it.step())
                it.getText(0)
            }
        }
}

/** Valida [file] como lo hace la restauración. */
internal fun validate(
    file: File,
    context: CandidateContext = CandidateContext.NEW_COPY,
): CandidateValidation = runBlocking { CandidateValidator(Dispatchers.IO).validate(file, context) }

/** Los hallazgos de una validación que llegó a revisar los datos, por comprobación. */
internal fun CandidateValidation.findings(): Map<CandidateCheck, Long> {
    assertTrue("$this", this is CandidateValidation.Checked)
    val verdict = (this as CandidateValidation.Checked).verdict
    val all: List<CandidateFinding> = when (verdict) {
        is CandidateVerdict.Restorable -> verdict.warnings
        is CandidateVerdict.NeedsRepair -> verdict.repairs + verdict.warnings
        is CandidateVerdict.Rejected -> verdict.fatal + verdict.repairs + verdict.warnings
    }
    return all.associate { it.check to it.cases }
}

/** Exige que [file] se valide sin ningún hallazgo. */
internal fun assertClean(case: String, file: File) {
    assertEquals(
        case,
        CandidateValidation.Checked(1, CandidateVerdict.Restorable(emptyList())),
        validate(file),
    )
}
