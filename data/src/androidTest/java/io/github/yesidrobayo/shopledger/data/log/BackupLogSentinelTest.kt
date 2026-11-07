package io.github.yesidrobayo.shopledger.data.log

import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.backup.BackupEngine
import io.github.yesidrobayo.shopledger.data.backup.BackupPackager
import io.github.yesidrobayo.shopledger.data.backup.BackupRun
import io.github.yesidrobayo.shopledger.data.backup.BackupRunner
import io.github.yesidrobayo.shopledger.data.backup.BackupStaging
import io.github.yesidrobayo.shopledger.data.backup.DatabaseBackupEngine
import io.github.yesidrobayo.shopledger.data.backup.RestoreMarkers
import io.github.yesidrobayo.shopledger.data.backup.SnapshotCreator
import io.github.yesidrobayo.shopledger.data.backup.SnapshotSealer
import io.github.yesidrobayo.shopledger.data.destination.APP_FOLDER
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.FakeHttpServer
import io.github.yesidrobayo.shopledger.data.destination.FakeResponse
import io.github.yesidrobayo.shopledger.data.destination.InMemoryWebDav
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.WebDavClient
import io.github.yesidrobayo.shopledger.data.destination.WebDavCredential
import io.github.yesidrobayo.shopledger.data.destination.WebDavDestination
import io.github.yesidrobayo.shopledger.data.destination.WebDavEndpoint
import io.github.yesidrobayo.shopledger.data.destination.testHttp
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.repository.AT
import io.github.yesidrobayo.shopledger.data.repository.newProduct
import io.github.yesidrobayo.shopledger.data.transaction.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.work.BackupWorker
import io.github.yesidrobayo.shopledger.data.work.DataWorkerFactory
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El camino del respaldo en la prueba de centinela: una copia de una base con datos marcados que
 * sube a un servicio con host, usuario y contraseña marcados, y que responde `404` a cada subida, y
 * una falla no prevista del trabajo con todos los centinelas en su mensaje. Al final, el registro
 * del sistema, leído como `adb logcat -d`, no trae ninguno, ni de la app ni de la biblioteca de
 * trabajos ni del cliente HTTP.
 */
@RunWith(AndroidJUnit4::class)
class BackupLogSentinelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "backup-log-sentinel-test.db")
    private val noBackup = File(context.noBackupFilesDir, "backup-log-sentinel-test")
    private val servers = ArrayList<FakeHttpServer>()

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
        noBackup.deleteRecursively()
    }

    @After
    fun cleanUp() {
        servers.forEach { it.close() }
        deleteDatabaseFiles(file)
        noBackup.deleteRecursively()
    }

    @Test
    fun aBackupThatGetsA404LogsNoSentinel() = runBlocking {
        val graph = graph()
        val ports = (graph.open() as DataAccess.Ready).ports
        inspectWithoutRoom(file) { connection ->
            connection.execSQL(
                "UPDATE app_settings SET currency_code = 'XTS', currency_decimals = 2, " +
                    "currency_locked_at = ${AT.toEpochMilliseconds()} WHERE id = 1",
            )
            connection.execSQL(
                "UPDATE backup_state SET link_state = 'CONNECTED', destination_kind = 'WEBDAV', " +
                    "destination_account = '${LogSentinels.WEBDAV_USER}', " +
                    "destination_id = '0123456789abcdef0123456789abcdef', " +
                    "destination_folder = '${LogSentinels.WEBDAV_URL}app/' WHERE id = 1",
            )
        }
        val product = newProduct(LogSentinels.PRODUCT).copy(salePrice = Money(LogSentinels.AMOUNT))
        ports.transactions.write { TxResult.Commit(ports.products.insert(product)) }
        val dav = InMemoryWebDav { request ->
            FakeResponse.status(404).takeIf {
                request.method ==
                    "PUT"
            }
        }
        dav.collections += "/dav/app/"
        val server = FakeHttpServer(keepAlive = true, handler = dav::handle).also { servers += it }
        val http =
            testHttp()
                .newBuilder()
                .dns { host ->
                    if (host == SENTINEL_HOST) {
                        listOf(InetAddress.getByName("127.0.0.1"))
                    } else {
                        Dns.SYSTEM.lookup(host)
                    }
                }.build()
        val client =
            WebDavClient(
                WebDavEndpoint(server.url(SENTINEL_HOST).toHttpUrl()),
                WebDavCredential(LogSentinels.WEBDAV_USER, LogSentinels.PASSWORD),
                http,
            )
        val destination = WebDavDestination(client, APP_FOLDER, sleep = {})
        val engine =
            DatabaseBackupEngine(
                restoreMarkers = { RestoreMarkers(noBackup) },
                openForWork = graph::openForWork,
                runnerFor = { opened ->
                    BackupRunner(
                        state = opened.backupState,
                        databaseFile = opened.databaseFile,
                        destinations = { DestinationOpen.Opened(destination) },
                        snapshots = SnapshotCreator(opened.databaseFile, Dispatchers.IO),
                        sealer = SnapshotSealer(Dispatchers.IO),
                        packager = BackupPackager(Dispatchers.IO),
                        staging =
                            BackupStaging(
                                File(noBackup, BackupStaging.DIRECTORY_NAME),
                                Dispatchers.IO,
                            ),
                        deviceId = { "7f3a9c1e-0b2d-4c6e-8f10-23456789abcd" },
                        clock = FIXED_CLOCK,
                        log = graph.log,
                        ioContext = Dispatchers.IO,
                        schemaVersion = AppDatabase.SCHEMA_VERSION,
                        appVersion = null,
                        deviceLabel = null,
                        mutex = Mutex(),
                    )
                },
                ioContext = Dispatchers.IO,
            )
        val capture = LogcatCapture.start()

        val result = worker(engine, graph).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        capture.assertClean()
        capture.assertAppLogged("Respaldo detenido: código=DESTINATION_UNSUPPORTED")
    }

    @Test
    fun anUnexpectedFailureOfTheWorkLogsNoSentinel() = runBlocking {
        val graph = graph()
        val engine =
            object : BackupEngine {
                override suspend fun run(control: TransferControl): BackupRun =
                    throw IllegalStateException(
                        LogSentinels.leakyMessage,
                        RuntimeException(LogSentinels.leakyMessage),
                    )

                override suspend fun recordUnexpected() = Unit
            }
        val capture = LogcatCapture.start()

        assertEquals(ListenableWorker.Result.failure(), worker(engine, graph).doWork())
        capture.assertClean()
        capture.assertAppLogged(
            "Falla no prevista del trabajo de respaldo: error=" +
                IllegalStateException::class.java.name,
        )
    }

    private fun worker(engine: BackupEngine, graph: DataGraph): BackupWorker =
        TestListenableWorkerBuilder<BackupWorker>(context)
            .setWorkerFactory(DataWorkerFactory(engine, graph.log))
            .build()

    private fun graph(): DataGraph = DataGraph(
        context,
        Dispatchers.IO,
        FIXED_CLOCK,
        { DatabaseFiles(file, noBackup) },
        AndroidEventLog(),
    )

    private companion object {
        const val SENTINEL_HOST = "sentinel-8a61.example.com"
    }
}
