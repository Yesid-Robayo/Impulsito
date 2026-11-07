package io.github.yesidrobayo.shopledger.data.seed

import android.content.Context
import androidx.room3.useReaderConnection
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * Inventa un negocio completo en una base nueva, para las pruebas de rendimiento, de respaldo y de
 * restauración. Vive solo en el código de `debug`: nunca llega a una compilación de publicación.
 *
 * - **La misma semilla da la misma base**, fila por fila, con la misma [config]: todo sale de
 *   [seed], de un reloj simulado y de la configuración, nunca de la hora, la zona ni el idioma del
 *   teléfono.
 * - **Con las reglas de la app.** Escribe por los repositorios y el runner de transacciones, con
 *   las guardas activas, y calcula costo y stock con las reglas de `:domain`. Para el volumen
 *   mayor, cada día de la historia es una sola transacción.
 * - **Comprueba lo que escribió.** Al terminar corre las consultas de invariantes del modelo de
 *   datos y la reproducción del costo de cada producto, y lo devuelve en el informe.
 * - **Solo en una base nueva.** Si el archivo ya existe, se niega: nunca escribe encima de otra
 *   base, por si alguien lo apunta a la del negocio.
 *
 * Los datos son inventados de punta a punta: nombres sin marcas ni personas reales, sin teléfonos
 * ni correos. Nada de lo que escribe pasa por el registro.
 */
class SeedDataGenerator(
    private val seed: Long,
    private val profile: SeedProfile,
    private val config: SeedConfig,
) {
    /**
     * Crea la base en [databaseFile], la llena y la cierra. Fuera del hilo principal: con el perfil
     * de volumen tarda minutos.
     */
    suspend fun generate(
        context: Context,
        databaseFile: File,
        ioContext: CoroutineContext = Dispatchers.IO,
    ): SeedReport {
        require(databaseFile.isAbsolute) { "La ruta de la base debe ser absoluta" }
        require(!databaseFile.exists()) { "El generador solo escribe en una base nueva" }
        val zone = config.zone
        val end = LocalDate(END_YEAR, END_MONTH, END_DAY)
        val start = end.minus(profile.months, DateTimeUnit.MONTH).plus(1, DateTimeUnit.DAY)
        val setupDay = start.minus(1, DateTimeUnit.DAY)
        val setupAt = LocalDateTime(setupDay, LocalTime(SETUP_HOUR, 0)).toInstant(zone.timeZone)
        val closeAt = LocalDateTime(end, LocalTime(CLOSE_HOUR, 0)).toInstant(zone.timeZone)
        val clock = SeedClock(setupAt)
        val database =
            AppDatabase.open(
                context = context,
                databaseFile = databaseFile,
                queryContext = ioContext,
                clock = clock,
                initialRegion = { config.initialRegion },
            )
        try {
            val ports = DataPorts(database, databaseFile, ioContext, clock, RestoreLock { false })
            val random = SeedRandom(seed)
            val stock = SeedStock(ports.stockLedger)
            val books = SeedBooks(database)
            val catalog =
                SeedCatalogWriter(database, ports, stock, random, profile, config)
                    .write(setupAt, BusinessDate.of(setupAt, zone))
            val history =
                SeedHistory(database, ports, stock, books, random, profile, catalog, clock, zone)
            history.run(start, end)
            history.close(closeAt, BusinessDate.of(closeAt, zone))
            return SeedReport(
                rowCounts = rowCounts(database),
                heavyProductMovements = history.heavyMovements(),
                invariantFailures = SeedInvariants.check(database, zone),
                endedAt = closeAt,
            )
        } finally {
            database.close()
        }
    }

    private suspend fun rowCounts(database: AppDatabase): Map<String, Long> =
        database.useReaderConnection { connection ->
            val tables =
                connection.usePrepared(
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name NOT LIKE 'sqlite_%' AND name <> 'room_master_table' " +
                        "ORDER BY name",
                ) { statement -> buildList { while (statement.step()) add(statement.getText(0)) } }
            tables.associateWith { table ->
                connection.usePrepared("SELECT COUNT(*) FROM \"$table\"") { statement ->
                    check(statement.step())
                    statement.getLong(0)
                }
            }
        }

    companion object {
        /** La semilla por defecto de la estrategia de pruebas. */
        const val DEFAULT_SEED: Long = 20_261_101L

        /** La historia termina siempre este día, para que no dependa de cuándo se genera. */
        private const val END_YEAR = 2026
        private const val END_MONTH = 10
        private const val END_DAY = 31
        private const val SETUP_HOUR = 9
        private const val CLOSE_HOUR = 22
    }
}

/**
 * Lo que dejó el generador: filas por tabla, filas del producto pesado y, por invariante, cuántas
 * filas lo rompen. Solo cifras: ningún dato del negocio inventado.
 */
class SeedReport(
    val rowCounts: Map<String, Long>,
    val heavyProductMovements: Int,
    val invariantFailures: Map<String, Long>,
    val endedAt: Instant,
) {
    /** Si todas las comprobaciones dieron cero. */
    val invariantsHold: Boolean get() = invariantFailures.values.all { it == 0L }
}
