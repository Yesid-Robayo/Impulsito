package io.github.yesidrobayo.shopledger.data.log

import android.util.Log
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.repository.AT
import io.github.yesidrobayo.shopledger.data.repository.newProduct
import io.github.yesidrobayo.shopledger.data.transaction.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.port.Contained
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.contain
import io.github.yesidrobayo.shopledger.domain.port.error
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La prueba de centinela del registro limpio: los datos marcados de [LogSentinels] recorren la app y
 * sus fallas, y al final el registro del sistema, leído como `adb logcat -d`, no trae ninguno.
 *
 * Hoy recorre lo que existe: los ajustes del negocio, un producto con su precio, una escritura que
 * la base rechaza, una base ajena que no abre y una falla no prevista con todos los centinelas en
 * su mensaje. Cada tarea que agrega un camino con datos, como la venta, el respaldo, la
 * restauración o un trabajo en segundo plano, lo suma aquí con los mismos valores.
 */
@RunWith(AndroidJUnit4::class)
class LogSentinelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "log-sentinel-test.db")

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
    }

    @After
    fun cleanUp() {
        deleteDatabaseFiles(file)
    }

    @Test
    fun captureSeesWhatIsWrittenDirectlyToTheLog() {
        // Sin esta comprobación, un lector roto daría la prueba por buena sin leer nada.
        val capture = LogcatCapture.start()

        Log.w(PROBE_TAG, "valor directo ${LogSentinels.PRODUCT} por ${LogSentinels.AMOUNT}")

        val leaks = capture.leaks()
        assertTrue(leaks.any { it.contains(LogSentinels.PRODUCT) })
        assertTrue(leaks.any { it.contains(LogSentinels.AMOUNT.toString()) })
    }

    @Test
    fun noSentinelReachesTheLogThroughTheAppOrItsFailures() = runBlocking {
        val capture = LogcatCapture.start()
        val graph = graph()
        val ports = (graph.open() as DataAccess.Ready).ports

        writeSentinelBusiness(ports)
        val duplicate = insertSentinelProductTwice(ports)
        graph.log.error(SentinelEvent.WRITE_REFUSED, duplicate, LogField.count("intentos", 1))
        val contained =
            graph.log.contain<SentinelEvent, Unit>(SentinelEvent.UNEXPECTED) {
                throw IllegalStateException(
                    LogSentinels.leakyMessage,
                    RuntimeException(LogSentinels.leakyMessage),
                )
            }
        graph.log.warn(
            SentinelEvent.UNEXPECTED,
            UnsupportedOperationException(LogSentinels.leakyMessage),
        )
        graph.log.info(SentinelEvent.FINISHED, LogField.count("productos", 1))

        assertEquals(Contained.Failed(IllegalStateException::class.java.name), contained)
        capture.assertClean()
        // Las líneas de la app sí llegaron: el registro estaba escribiendo, y solo con clases.
        capture.assertAppLogged("Base abierta: duración=")
        capture.assertAppLogged(
            "Escritura rechazada: intentos=1, error=" + StorageFailureException::class.java.name +
                ", tipo=INVARIANT_VIOLATION",
        )
        capture.assertAppLogged(
            "Falla no prevista: error=" + IllegalStateException::class.java.name,
        )
        capture.assertAppLogged(
            "Falla no prevista: error=" + UnsupportedOperationException::class.java.name,
        )
        capture.assertAppLogged("Recorrido terminado: productos=1")
    }

    @Test
    fun aDatabaseThatDoesNotOpenIsLoggedByItsClassOnly() = runBlocking {
        writeForeignDatabaseWithSentinels(file)
        val capture = LogcatCapture.start()

        val access = graph().open()

        assertEquals(DataAccess.Unavailable, access)
        capture.assertClean()
        capture.assertAppLogged("La base no abrió: error=" + IllegalStateException::class.java.name)
    }

    /** Una base de una versión más nueva de la app, con los centinelas en sus filas. */
    private fun writeForeignDatabaseWithSentinels(target: File) {
        BundledSQLiteDriver().open(target.path).use { connection ->
            connection.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
            connection.execSQL("INSERT INTO notes (body) VALUES ('${LogSentinels.NOTE}')")
            connection.execSQL("PRAGMA user_version = ${AppDatabase.SCHEMA_VERSION + 1}")
        }
    }

    private fun graph(): DataGraph =
        DataGraph(context, Dispatchers.IO, FIXED_CLOCK, { file }, AndroidEventLog())

    private suspend fun writeSentinelBusiness(ports: DataPorts) {
        val profile =
            BusinessProfile(
                businessName = "${LogSentinels.CUSTOMER} ${LogSentinels.PHONE}",
                defaultTargetMargin = BasisPoints(3_000),
            )
        ports.transactions.write {
            ports.settings.updateProfile(profile, AT)
            TxResult.Commit(Unit)
        }
        assertEquals(profile, ports.settings.get().profile)
    }

    /** El segundo producto con el mismo nombre lo rechaza la base: devuelve esa falla. */
    private suspend fun insertSentinelProductTwice(ports: DataPorts): StorageFailureException {
        // Sin moneda elegida, la base no guarda montos. Todavía no hay caso de uso que la elija: la
        // prueba la fija directo en el archivo, con una moneda de prueba.
        inspectWithoutRoom(file) { connection ->
            connection.execSQL(
                "UPDATE app_settings SET currency_code = 'XTS', currency_decimals = 2, " +
                    "currency_locked_at = ${AT.toEpochMilliseconds()} WHERE id = 1",
            )
        }
        val product = newProduct(LogSentinels.PRODUCT).copy(salePrice = Money(LogSentinels.AMOUNT))
        ports.transactions.write { TxResult.Commit(ports.products.insert(product)) }
        try {
            ports.transactions.write { TxResult.Commit(ports.products.insert(product)) }
        } catch (refused: StorageFailureException) {
            assertEquals(StorageFailure.INVARIANT_VIOLATION, refused.failure)
            return refused
        }
        throw AssertionError("La base debía rechazar el segundo producto con el mismo nombre")
    }

    private enum class SentinelEvent(override val text: String) : LogEvent {
        WRITE_REFUSED("Escritura rechazada"),
        UNEXPECTED("Falla no prevista"),
        FINISHED("Recorrido terminado"),
    }

    private companion object {
        const val PROBE_TAG = "SentinelProbe"
    }
}
