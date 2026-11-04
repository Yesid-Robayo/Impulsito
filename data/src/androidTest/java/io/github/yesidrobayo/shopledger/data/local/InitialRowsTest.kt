package io.github.yesidrobayo.shopledger.data.local

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.settings.AppSettingsEntity
import io.github.yesidrobayo.shopledger.domain.port.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las filas que nacen con la base, la única de los ajustes y la del estado del respaldo, y las
 * llaves e índices de las tablas de gastos, alertas, ajustes y respaldo.
 *
 * Las filas se crean una sola vez, cuando Room crea la base, en la misma transacción que las
 * tablas: una base que ya existía no las recibe de nuevo, y si crearlas falla no queda una base a
 * medias que se dé por completa.
 */
@RunWith(AndroidJUnit4::class)
internal class InitialRowsTest : CatalogDatabaseTest() {
    @Test
    fun aNewDatabaseHasOneRowOfSettingsAndOneOfBackupState() = runBlocking {
        val expectedSettings =
            AppSettingsEntity(
                id = 1,
                businessName = null,
                defaultTargetMarginBp = 3_000,
                lowStockNotificationsEnabled = 1,
                themeMode = "SYSTEM",
                colorPalette = "INDIGO_MUSTARD",
                appLockEnabled = 0,
                appLockTimeoutSeconds = 60,
                meteredBackupPolicy = "EVERY_60_MIN",
                onboardingCompletedAt = null,
                updatedAt = CREATED_AT,
            )

        assertEquals(expectedSettings, database.appSettingsDao().get())
        assertEquals(INITIAL_BACKUP_STATE, database.backupStateDao().get())
        assertEquals(1L, count("SELECT COUNT(*) FROM app_settings"))
        assertEquals(1L, count("SELECT COUNT(*) FROM backup_state"))
    }

    @Test
    fun anExistingDatabaseKeepsItsRowsWhenOpenedAgain() = runBlocking {
        val file = testDatabaseFile(context, "initial-rows-reopen.db")
        deleteDatabaseFiles(file)
        try {
            val first = AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK)
            try {
                first.appSettingsDao().updateThemeMode("DARK", LATER)
                first.backupStateDao().markChanged(LATER)
            } finally {
                first.close()
            }

            // Otro reloj: si la fila naciera otra vez, saldría con este instante.
            val later = Clock { Instant.fromEpochMilliseconds(LATER + 1) }
            val reopened = AppDatabase.open(context, file, Dispatchers.IO, later)
            try {
                val counts =
                    reopened.useReaderConnection {
                        it.longValue(
                            "SELECT (SELECT COUNT(*) FROM app_settings) + " +
                                "(SELECT COUNT(*) FROM backup_state)",
                        )
                    }
                assertEquals(2L, counts)
                assertEquals("DARK", reopened.appSettingsDao().get()?.themeMode)
                assertEquals(LATER, reopened.appSettingsDao().get()?.updatedAt)
                assertEquals(1L, reopened.backupStateDao().get()?.changeSeq)
            } finally {
                reopened.close()
            }
        } finally {
            deleteDatabaseFiles(file)
        }
    }

    @Test
    fun aFailedCreationLeavesNoHalfDatabaseAndTheNextOpenCreatesItWhole() = runBlocking {
        val file = testDatabaseFile(context, "initial-rows-failure.db")
        deleteDatabaseFiles(file)
        try {
            // El reloj falla en medio de la creación, después de crear las tablas.
            val broken = Clock { throw InjectedFailure() }
            var failed = false
            val attempt = AppDatabase.build(context, file, Dispatchers.IO, broken)
            try {
                attempt.useReaderConnection { it.longValue("PRAGMA user_version") }
            } catch (expected: InjectedFailure) {
                failed = true
            } finally {
                attempt.close()
            }
            assertTrue("La creación debía fallar", failed)

            // Nada de la creación quedó: ni tablas ni una versión que la dé por completa.
            inspectWithoutRoom(file) { connection ->
                assertEquals(0L, connection.longValue("PRAGMA user_version"))
                assertEquals(emptyList<String>(), connection.texts(OWN_TABLES_SQL))
            }

            val reopened = AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK)
            try {
                assertEquals(CREATED_AT, reopened.appSettingsDao().get()?.updatedAt)
                assertEquals(INITIAL_BACKUP_STATE, reopened.backupStateDao().get())
                assertEquals(
                    SCHEMA_TABLES,
                    reopened.useReaderConnection { it.texts(OWN_TABLES_SQL) },
                )
            } finally {
                reopened.close()
            }
        } finally {
            deleteDatabaseFiles(file)
        }
    }

    @Test
    fun indexesAreTheOnesOfTheDataModel() = runBlocking {
        val expected =
            mapOf(
                "expense_categories" to
                    listOf(
                        "index_expense_categories_normalized_name (normalized_name)",
                        "unique index_expense_categories_system_key (system_key)",
                    ),
                "expenses" to
                    listOf(
                        "index_expenses_expense_category_id (expense_category_id)",
                        "index_expenses_status_business_date (status, business_date)",
                    ),
                "low_stock_alerts" to
                    listOf(
                        "index_low_stock_alerts_notification_status (notification_status)",
                        "index_low_stock_alerts_product_id_resolved_at (product_id, resolved_at)",
                    ),
                "app_settings" to emptyList(),
                "backup_state" to emptyList(),
            )

        for ((table, indexes) in expected) assertEquals(table, indexes, database.indexesOf(table))
    }

    @Test
    fun foreignKeysPointToTheTablesThatExist() = runBlocking {
        // La cuenta de dinero y el gasto recurrente de un gasto llegan con sus tablas.
        val expected =
            mapOf(
                "expense_categories" to emptyList(),
                "expenses" to
                    listOf(
                        "expense_category_id -> expense_categories RESTRICT",
                        "money_account_id -> money_accounts RESTRICT",
                    ),
                "low_stock_alerts" to listOf("product_id -> products CASCADE"),
                "app_settings" to emptyList(),
                "backup_state" to emptyList(),
            )

        for ((table, keys) in expected) assertEquals(table, keys, database.foreignKeysOf(table))
    }

    @Test
    fun theDeviceIdentifierIsNotInTheDatabase() = runBlocking {
        // Identifica la instalación y vive fuera de la base. Las columnas *_device_id del respaldo
        // guardan el de otras copias, no el de este teléfono.
        val columns =
            database.useReaderConnection { connection ->
                connection.texts(
                    "SELECT m.name || '.' || c.name FROM sqlite_master m " +
                        "JOIN pragma_table_info(m.name) c WHERE m.type = 'table' " +
                        "AND c.name LIKE '%device%' ORDER BY 1",
                )
            }

        assertEquals(
            listOf(
                "backup_state.conflict_device_id",
                "backup_state.copy_device_id",
                "backup_state.head_device_id",
            ),
            columns,
        )
    }

    /** Una falla inventada a propósito, sin mensaje. */
    private class InjectedFailure : RuntimeException()

    private companion object {
        val INITIAL_BACKUP_STATE =
            BackupStateEntity(
                id = 1,
                changeSeq = 0,
                verifiedSeq = 0,
                firstUnsyncedAt = null,
                linkState = "NOT_CONNECTED",
                failureCode = null,
                consecutiveFailures = 0,
                lastFailureKind = null,
                invariantFailures = 0,
                lastAttemptAt = null,
                lastSuccessAt = null,
                lastUploadFinishedAt = null,
                lastStaleNoticeAt = null,
                lastDrillAt = null,
                lastDrillResult = null,
                lastFullVerifyAt = null,
                lastHumanCheckAt = null,
                humanCheckGeneration = null,
                destinationKind = null,
                destinationAccount = null,
                destinationId = null,
                destinationFolder = null,
                headGeneration = 0,
                headDeviceId = null,
                headFileRef = null,
                shrinkAcceptedGeneration = null,
                pendingGeneration = null,
                pendingSeq = null,
                pendingSha256 = null,
                pendingSizeBytes = null,
                conflictKind = null,
                conflictGeneration = null,
                conflictDeviceId = null,
                conflictFileRef = null,
                copyGeneration = null,
                copyDeviceId = null,
                copyCreatedAt = null,
            )
    }
}
