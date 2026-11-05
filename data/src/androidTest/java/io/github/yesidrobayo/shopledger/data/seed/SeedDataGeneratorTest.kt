package io.github.yesidrobayo.shopledger.data.seed

import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El generador de datos de prueba en el dispositivo, con Room real, el SQLite empaquetado y las
 * guardas activas: que cada perfil cumple los invariantes, que la misma semilla da la misma base
 * sin importar el idioma ni la zona del teléfono, y que trae los casos que pide la estrategia de
 * pruebas.
 *
 * El volumen mayor no corre aquí, porque tarda minutos: lo mide [SeedVolumeMeasurementTest], que
 * solo corre si se pide.
 */
@RunWith(AndroidJUnit4::class)
internal class SeedDataGeneratorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = ArrayList<File>()

    @After
    fun deleteGeneratedDatabases() {
        files.forEach(::deleteDatabaseFiles)
    }

    @Test
    fun smallProfileKeepsEveryInvariantWithEachRegionalConfiguration() = runBlocking {
        var generated = 0
        RegionalMatrix.ROWS.forEachIndexed { index, row ->
            val config = seedConfigOf(row) ?: return@forEachIndexed
            val case = "fila ${index + 1}, $row"
            val report = generate(SeedProfile.SMALL, config, "seed-small-$index.db")

            assertInvariantsHold(case, report)
            assertEquals(case, 200L, report.rowCounts["sales"])
            assertEquals(case, 20L, report.rowCounts["products"])
            assertEquals(case, 20L, report.rowCounts["purchases"])
            assertEquals(case, 30L, report.rowCounts["expenses"])
            assertEquals(case, 30L, report.rowCounts["product_price_history"])
            assertEquals(case, 4L, report.rowCounts["categories"])
            assertEquals(case, 3L, report.rowCounts["suppliers"])
            assertEquals(case, 10L, report.rowCounts["customers"])
            generated++
        }
        // Todas las filas menos la que no tiene moneda confirmada.
        assertEquals(RegionalMatrix.ROWS.size - 1, generated)
    }

    @Test
    fun theSameSeedGivesTheSameDatabaseWhateverThePhoneLanguageAndZone() = runBlocking {
        val config = requireNotNull(seedConfigOf(SPANISH_ROW))
        val first = file("seed-same-first.db")
        SeedDataGenerator(SEED, SeedProfile.SMALL, config).generate(context, first)

        val second = file("seed-same-second.db")
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA"))
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Chatham"))
            SeedDataGenerator(SEED, SeedProfile.SMALL, config).generate(context, second)
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }

        assertEquals(rowDigest(first), rowDigest(second))
        // Byte por byte, también: las mismas operaciones dejan las mismas páginas.
        assertEquals(fileDigest(first), fileDigest(second))

        val other = file("seed-same-other.db")
        SeedDataGenerator(SEED + 1, SeedProfile.SMALL, config).generate(context, other)
        assertNotEquals(rowDigest(first), rowDigest(other))
    }

    @Test
    fun theLanguageOfTheNamesChangesTheTextsAndNoFigure() = runBlocking {
        val config = requireNotNull(seedConfigOf(SPANISH_ROW))
        val results =
            SeedLanguage.entries.map { language ->
                val target = file("seed-language-${language.name}.db")
                val report =
                    SeedDataGenerator(SEED, SeedProfile.SMALL, config.copy(language = language))
                        .generate(context, target)
                assertInvariantsHold(language.name, report)
                inspectWithoutRoom(target) { connection ->
                    FIGURE_QUERIES.map { connection.longValue(it) } to
                        connection.texts("SELECT name FROM products ORDER BY id")
                }
            }
        // Las cifras son las mismas en los tres idiomas; los nombres, no.
        val (figures, names) = results.unzip()
        assertEquals(1, figures.toSet().size)
        assertEquals(SeedLanguage.entries.size, names.toSet().size)
    }

    @Test
    fun typicalProfileHasTheCasesTheTestStrategyAsksFor() = runBlocking {
        val row = RegionalMatrix.ROWS[4]
        val config = requireNotNull(seedConfigOf(row))
        val target = file("seed-typical.db")
        val report =
            SeedDataGenerator(SeedDataGenerator.DEFAULT_SEED, SeedProfile.TYPICAL, config)
                .generate(context, target)

        assertInvariantsHold("perfil típico", report)
        val counts = report.rowCounts
        assertEquals(10_000L, counts["sales"])
        assertEquals(300L, counts["products"])
        assertEquals(1_000L, counts["purchases"])
        assertEquals(1_500L, counts["expenses"])
        assertEquals(900L, counts["product_price_history"])
        assertNear("sale_lines", 25_000L, counts)
        assertNear("purchase_lines", 3_000L, counts)
        assertNear("stock_movements", 30_000L, counts)

        val zone = requireNotNull(BusinessTimeZone.of(row.region?.businessTimeZoneId ?: "UTC"))
        inspectWithoutRoom(target) { connection ->
            // Archivados, sin stock, con stock negativo y vendidos a pérdida.
            assertEquals(30L, connection.longValue(ARCHIVED))
            assertTrue(connection.longValue(WITHOUT_STOCK) > 0L)
            assertTrue(connection.longValue(NEGATIVE_STOCK) > 0L)
            assertTrue(connection.longValue(SOLD_AT_LOSS) > 0L)
            // Unas 2 de cada 100 ventas anuladas.
            val voided = connection.longValue(VOIDED)
            assertTrue("anuladas: $voided", voided in 120L..300L)
            // Las cinco unidades base de la estrategia.
            assertEquals(5L, connection.longValue(BASE_UNITS))
            // Combos con componentes, servicios, pagos divididos, a cuenta y en dos ubicaciones.
            assertTrue(connection.longValue(COMPONENT_ROWS) > 0L)
            assertTrue(connection.longValue(SERVICE_LINES) > 0L)
            assertTrue(connection.longValue(SPLIT_SALES) > 0L)
            assertTrue(connection.longValue(CREDIT_SALES) > 0L)
            assertEquals(2L, connection.longValue(LOCATIONS_WITH_STOCK))
            // Letras que la búsqueda y el orden deben manejar, en algún nombre.
            val names = connection.texts("SELECT name FROM products").joinToString(" ")
            assertTrue(names.contains("ã") && names.contains("ç"))
            assertTrue(names.any { it.code > ASCII_MAX })
            // Nombres largos, sin pasar del tope.
            assertTrue(connection.longValue(LONGEST_NAME) in 70L..80L)
            // Más ventas en fin de semana que entre semana, por día.
            val weekend = connection.longValue(WEEKEND_PER_DAY)
            val weekday = connection.longValue(WEEKDAY_PER_DAY)
            assertTrue("fin de semana $weekend, entre semana $weekday", weekend > weekday)
            // Algunas cerca de medianoche en la zona del negocio.
            val nearMidnight =
                connection
                    .texts("SELECT CAST(occurred_at AS TEXT) FROM sales")
                    .map {
                        Instant.fromEpochMilliseconds(it.toLong()).toLocalDateTime(zone.timeZone)
                    }
                    .count { (it.hour == LAST_HOUR && it.minute >= HALF_HOUR) || it.hour == 0 }
            assertTrue("cerca de medianoche: $nearMidnight", nearMidnight > 0)
            // Las ventas se concentran: el 20 % de los productos lleva la mayoría de las líneas.
            val topShare = connection.longValue(TOP_FIFTH_SHARE_PERCENT)
            assertTrue("parte del 20 % más vendido: $topShare %", topShare >= TOP_SHARE_MIN)
        }
    }

    @Test
    fun theChecksSeeABalanceThatNoLongerMatchesItsLedger() = runBlocking {
        val config = requireNotNull(seedConfigOf(SPANISH_ROW))
        val target = file("seed-broken.db")
        SeedDataGenerator(SEED, SeedProfile.SMALL, config).generate(context, target)
        // Un saldo de cabecera que se aparta de su libro, escrito por fuera de la app.
        inspectWithoutRoom(target) { connection ->
            connection.execSQL(
                "UPDATE products SET on_hand_milli = on_hand_milli + 1000 WHERE id = " +
                    "(SELECT MIN(product_id) FROM stock_movements)",
            )
            connection.execSQL("UPDATE money_accounts SET balance = balance + 1 WHERE id = 1")
        }

        val database = AppDatabase.open(context, target, Dispatchers.IO)
        val failures =
            try {
                SeedInvariants.check(database, config.zone)
            } finally {
                database.close()
            }

        assertEquals(1L, failures["I-01"])
        assertEquals(1L, failures["I-10"])
        assertEquals(1L, failures[SeedInvariants.COST_REPLAY])
        assertEquals(1L, failures["I-16, saldo"])
        assertEquals(0L, failures["I-02"])
    }

    @Test
    fun refusesToWriteOverAnExistingDatabase() = runBlocking {
        val config = requireNotNull(seedConfigOf(SPANISH_ROW))
        val target = file("seed-existing.db")
        target.writeBytes(ByteArray(EXISTING_BYTES))
        val refused =
            try {
                SeedDataGenerator(SEED, SeedProfile.SMALL, config).generate(context, target)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        assertTrue(refused)
        assertEquals(EXISTING_BYTES.toLong(), target.length())
        assertFalse(File(target.path + "-wal").exists())
    }

    private suspend fun generate(
        profile: SeedProfile,
        config: SeedConfig,
        name: String,
    ): SeedReport = SeedDataGenerator(SEED, profile, config).generate(context, file(name))

    private fun file(name: String): File {
        val target = testDatabaseFile(context, name)
        deleteDatabaseFiles(target)
        files += target
        return target
    }

    private fun assertNear(table: String, expected: Long, counts: Map<String, Long>) {
        val actual = requireNotNull(counts[table]) { table }
        val margin = expected * NEAR_PERCENT / 100
        assertTrue(
            "$table: $actual, se esperaban unas $expected",
            actual in expected - margin..expected + margin,
        )
    }

    private companion object {
        const val SEED = 7L
        const val EXISTING_BYTES = 16
        const val NEAR_PERCENT = 25L
        const val ASCII_MAX = 127
        const val LAST_HOUR = 23
        const val HALF_HOUR = 30
        const val TOP_SHARE_MIN = 60L

        val FIGURE_QUERIES =
            listOf(
                "SELECT COUNT(*) FROM sales",
                "SELECT SUM(total) FROM sales",
                "SELECT SUM(total_cost) FROM sales",
                "SELECT SUM(quantity_milli) FROM stock_movements",
                "SELECT SUM(total_cost) FROM stock_movements",
                "SELECT SUM(amount) FROM money_movements",
                "SELECT SUM(amount) FROM customer_ledger_entries",
            )

        const val ARCHIVED = "SELECT COUNT(*) FROM products WHERE archived_at IS NOT NULL"
        const val WITHOUT_STOCK =
            "SELECT COUNT(*) FROM products WHERE stock_mode = 'TRACKED' AND on_hand_milli = 0"
        const val NEGATIVE_STOCK = "SELECT COUNT(*) FROM products WHERE on_hand_milli < 0"
        const val SOLD_AT_LOSS =
            "SELECT COUNT(*) FROM sale_lines " +
                "WHERE stock_effect = 'DIRECT' AND line_cost > net_amount"
        const val VOIDED = "SELECT COUNT(*) FROM sales WHERE status = 'VOIDED'"
        const val BASE_UNITS =
            "SELECT COUNT(DISTINCT base_unit_id) FROM products WHERE stock_mode = 'TRACKED'"
        const val COMPONENT_ROWS = "SELECT COUNT(*) FROM sale_line_components WHERE tracked = 1"
        const val SERVICE_LINES = "SELECT COUNT(*) FROM sale_lines WHERE stock_effect = 'NONE'"
        const val SPLIT_SALES =
            "SELECT COUNT(*) FROM (SELECT sale_id FROM sale_payments GROUP BY sale_id " +
                "HAVING COUNT(*) > 1)"
        const val CREDIT_SALES =
            "SELECT COUNT(*) FROM customer_ledger_entries WHERE type = 'CREDIT_SALE'"
        const val LOCATIONS_WITH_STOCK = "SELECT COUNT(DISTINCT location_id) FROM stock_balances"
        const val LONGEST_NAME = "SELECT MAX(length(name)) FROM products"
        const val WEEKEND_PER_DAY =
            "SELECT COUNT(*) / COUNT(DISTINCT business_date) FROM sales " +
                "WHERE strftime('%w', business_date) IN ('0', '6')"
        const val WEEKDAY_PER_DAY =
            "SELECT COUNT(*) / COUNT(DISTINCT business_date) FROM sales " +
                "WHERE strftime('%w', business_date) NOT IN ('0', '6')"
        const val TOP_FIFTH_SHARE_PERCENT =
            "WITH per_product AS (SELECT product_id, COUNT(*) AS lines FROM sale_lines " +
                "GROUP BY product_id), ranked AS (SELECT lines, ROW_NUMBER() OVER " +
                "(ORDER BY lines DESC) AS position, COUNT(*) OVER () AS products " +
                "FROM per_product) " +
                "SELECT SUM(CASE WHEN position * 5 <= products THEN lines ELSE 0 END) * 100 / " +
                "SUM(lines) FROM ranked"
    }
}
