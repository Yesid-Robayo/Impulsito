package io.github.yesidrobayo.shopledger.data.log

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.repository.AT
import io.github.yesidrobayo.shopledger.data.transaction.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.error
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ArchiveCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CreateCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.DeleteCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.EditCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ReactivateCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
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
 * Los clientes en la prueba de centinela: nombre, teléfono, correo y notas marcados recorren crear,
 * avisar del nombre repetido, rechazar lo mal escrito, editar, archivar, reactivar y eliminar, y una
 * escritura que la base rechaza. Al final, el registro del sistema no trae ninguno.
 */
@RunWith(AndroidJUnit4::class)
class CustomerLogSentinelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "customer-log-sentinel-test.db")
    private val markerDirectory = File(context.noBackupFilesDir, "customer-log-sentinel-test")

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
        markerDirectory.deleteRecursively()
    }

    @After
    fun cleanUp() {
        deleteDatabaseFiles(file)
        markerDirectory.deleteRecursively()
    }

    @Test
    fun noCustomerDataReachesTheLog() = runBlocking {
        val capture = LogcatCapture.start()
        val graph = DataGraph(
            context,
            Dispatchers.IO,
            FIXED_CLOCK,
            { DatabaseFiles(file, markerDirectory) },
            AndroidEventLog(),
        )
        val ports = (graph.open() as DataAccess.Ready).ports
        val tx = ports.transactions
        val create = CreateCustomerUseCase(tx, ports.customers, FIXED_CLOCK)
        val edit = EditCustomerUseCase(tx, ports.customers, FIXED_CLOCK)
        val form = CustomerForm(
            name = LogSentinels.CUSTOMER,
            phone = LogSentinels.PHONE,
            email = LogSentinels.EMAIL,
            notes = LogSentinels.NOTE,
        )

        val id = (create(form) as SaveCustomerResult.Saved).customerId
        assertEquals(SaveCustomerResult.SameName, create(form))
        assertTrue(
            create(form.copy(phone = "${LogSentinels.PHONE}x")) is SaveCustomerResult.Invalid,
        )
        assertEquals(
            SaveCustomerResult.Saved(id),
            edit(id, form.copy(notes = "${LogSentinels.NOTE} ${LogSentinels.CUSTOMER}")),
        )
        assertEquals(
            CustomerLifecycleResult.Done,
            ArchiveCustomerUseCase(tx, ports.customers, FIXED_CLOCK)(id),
        )
        assertEquals(
            CustomerLifecycleResult.Done,
            ReactivateCustomerUseCase(tx, ports.customers, FIXED_CLOCK)(id),
        )
        val refused = insertRefusedByTheDatabase(ports.customers, tx)
        graph.log.error(SentinelEvent.WRITE_REFUSED, refused, LogField.count("intentos", 1))
        assertEquals(CustomerLifecycleResult.Done, DeleteCustomerUseCase(tx, ports.customers)(id))
        graph.log.info(SentinelEvent.FINISHED, LogField.count("clientes", 1))

        capture.assertClean()
        capture.assertAppLogged(
            "Escritura rechazada: intentos=1, error=" + StorageFailureException::class.java.name +
                ", tipo=INVARIANT_VIOLATION",
        )
        capture.assertAppLogged("Recorrido terminado: clientes=1")
    }

    /**
     * Un cliente con un nombre de más de 80 caracteres, hecho de centinelas, que la guarda rechaza.
     * Devuelve esa falla, que no trae el texto de SQLite.
     */
    private suspend fun insertRefusedByTheDatabase(
        customers: CustomerRepository,
        tx: TransactionRunner,
    ): StorageFailureException {
        val longName = (LogSentinels.CUSTOMER + " ").repeat(5)
        val details = CustomerDetails(
            CustomerContact(longName, longName.lowercase(), LogSentinels.PHONE, null, null),
            null,
        )
        try {
            tx.write { TxResult.Commit(customers.insert(details, AT)) }
        } catch (refused: StorageFailureException) {
            assertEquals(StorageFailure.INVARIANT_VIOLATION, refused.failure)
            return refused
        }
        throw AssertionError("La base debía rechazar un nombre de más de 80 caracteres")
    }

    private enum class SentinelEvent(override val text: String) : LogEvent {
        WRITE_REFUSED("Escritura rechazada"),
        FINISHED("Recorrido terminado"),
    }
}
