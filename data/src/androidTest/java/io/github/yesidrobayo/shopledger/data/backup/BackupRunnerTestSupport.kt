package io.github.yesidrobayo.shopledger.data.backup

import android.content.Context
import androidx.sqlite.execSQL
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.VerifyMode
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.repository.AT
import io.github.yesidrobayo.shopledger.data.repository.newProduct
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import java.io.File
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex

/** Un teléfono inventado: los primeros 8 caracteres van en el nombre de cada copia. */
internal const val TEST_DEVICE_ID = "7f3a9c1e-0b2d-4c6e-8f10-23456789abcd"

/** Otro teléfono inventado, que escribió en el mismo destino. */
internal const val OTHER_DEVICE_ID = "0c4d2e7a-91b3-4f5e-a6d7-8899aabbccdd"

/** La huella inventada del destino conectado. */
internal const val TEST_DESTINATION_ID = "0123456789abcdef0123456789abcdef"

/**
 * Una base de la app en un archivo propio de la prueba, abierta por la capa de datos, con su
 * carpeta de preparación del respaldo y un reloj que la prueba mueve.
 */
internal class BackupFixture(private val context: Context, name: String) {
    val file: File = testDatabaseFile(context, "$name.db")
    val noBackup = File(context.noBackupFilesDir, name)
    val stagingDir: File = File(noBackup, BackupStaging.DIRECTORY_NAME)
    val rejectedFile: File = File(noBackup, RejectedGenerationStore.FILE_NAME)
    val rejected = RejectedGenerationStore(rejectedFile, Dispatchers.IO)
    val fullVerifyFile: File = File(noBackup, FullVerifyGenerationStore.FILE_NAME)
    val fullVerify = FullVerifyGenerationStore(fullVerifyFile, Dispatchers.IO)
    val log = MemoryLog()

    /**
     * El reloj de la prueba, que solo avanza a pedido. Empieza una hora por delante del reloj real:
     * así los archivos que la prueba escribe nunca parecen del futuro, que la limpieza borra.
     */
    var now: Instant = Instant.fromEpochSeconds(System.currentTimeMillis() / 1000 + 3_600)
    val clock = Clock { now }
    private var graph = newGraph()
    lateinit var ports: DataPorts
        private set

    suspend fun open() {
        deleteDatabaseFiles(file)
        noBackup.deleteRecursively()
        ports = (graph.open() as DataAccess.Ready).ports
    }

    /**
     * Abre la base como un proceso nuevo, sin borrar nada: el arranque termina lo que haya quedado
     * pendiente, como un reemplazo. Los puertos anteriores ya no se usan.
     */
    suspend fun reopen(): DataAccess {
        graph = newGraph()
        val access = graph.open()
        if (access is DataAccess.Ready) ports = access.ports
        return access
    }

    fun close() {
        deleteDatabaseFiles(file)
        noBackup.deleteRecursively()
    }

    private fun newGraph() =
        DataGraph(context, Dispatchers.IO, clock, { DatabaseFiles(file, noBackup) }, log)

    /** Conecta el destino [kind] en el estado del respaldo, como lo deja la conexión. */
    fun connect(kind: BackupDestinationKind = BackupDestinationKind.WEBDAV) = sql(
        "UPDATE backup_state SET link_state = 'CONNECTED', destination_kind = '${kind.name}', " +
            "destination_id = '$TEST_DESTINATION_ID', destination_folder = " +
            (
                if (kind ==
                    BackupDestinationKind.WEBDAV
                ) {
                    "'https://dav.example.com/app/'"
                } else {
                    "NULL"
                }
                ) +
            " WHERE id = 1",
    )

    /** Registra un producto: un cambio del negocio, contado para la copia. */
    suspend fun addProduct(name: String) {
        // Sin moneda elegida, la base no guarda montos. La prueba la fija directo en el archivo,
        // con la moneda de prueba de ISO.
        sql(
            "UPDATE app_settings SET currency_code = 'XTS', currency_decimals = 2, " +
                "currency_locked_at = ${AT.toEpochMilliseconds()} WHERE id = 1",
        )
        ports.transactions.write { TxResult.Commit(ports.products.insert(newProduct(name))) }
    }

    fun sql(statement: String) {
        inspectWithoutRoom(file) {
            // Room puede tener la base ocupada un momento, por ejemplo al pasar el WAL al archivo.
            it.execSQL("PRAGMA busy_timeout = 5000")
            it.execSQL(statement)
        }
    }

    suspend fun state() = requireNotNull(ports.backupState.get())

    fun stagingFiles(): List<String> = stagingDir.list()?.sorted().orEmpty()

    /** El motor sobre esta base, con [destination] y las piezas reales de la copia. */
    fun runner(
        destination: BackupDestination,
        pruner: CopyPruner = CopyPruner.None,
        freeSpace: (File) -> Long = File::getUsableSpace,
        snapshots: SnapshotCreator = SnapshotCreator(file, Dispatchers.IO),
        packager: BackupPackager = BackupPackager(Dispatchers.IO),
        staging: BackupStaging = BackupStaging(stagingDir, Dispatchers.IO),
        verifyMode: (VerifyRequest) -> VerifyMode? = ::verifyModeFor,
    ): BackupRunner = BackupRunner(
        state = ports.backupState,
        databaseFile = file,
        destinations = { DestinationOpen.Opened(destination) },
        snapshots = snapshots,
        sealer = SnapshotSealer(Dispatchers.IO),
        packager = packager,
        staging = staging,
        deviceId = { TEST_DEVICE_ID },
        clock = clock,
        log = log,
        ioContext = Dispatchers.IO,
        schemaVersion = AppDatabase.SCHEMA_VERSION,
        appVersion = "1.0.0",
        deviceLabel = "Fabricante Modelo",
        mutex = Mutex(),
        rejected = rejected,
        fullVerify = fullVerify,
        pruner = pruner,
        freeSpace = freeSpace,
        verifyMode = verifyMode,
        meteredAllowed = {
            ports.settings.get().preferences.meteredBackupPolicy != MeteredBackupPolicy.WIFI_ONLY
        },
    )

    fun advance(by: Duration) {
        now += by
    }
}

/** Una poda que anota cuándo la llamaron y con qué copia, sin borrar nada. */
internal class RecordingPruner : CopyPruner {
    var calls = 0
        private set

    var lastConfirmed: ConfirmedCopy? = null
        private set

    override suspend fun prune(
        destination: BackupDestination,
        confirmed: ConfirmedCopy,
        control: TransferControl,
    ): DestinationResult<PruneReport> {
        calls++
        lastConfirmed = confirmed
        return DestinationResult.Success(PruneReport(PruneResult.PRUNED, 0))
    }
}

/** Lo que el trabajo da al destino, que la prueba detiene a pedido. */
internal class TestControl(
    var stopped: Boolean = false,
    var metered: Boolean = false,
    var backupNow: Boolean = false,
) : TransferControl {
    override fun isStopped(): Boolean = stopped

    override fun remainingMillis(): Long? = null

    override fun meteredStop(): Boolean = false

    override fun isMetered(): Boolean = metered

    override fun isBackupNow(): Boolean = backupNow
}

/** Guarda cada línea en memoria. Puede escribirse desde varios hilos a la vez. */
internal class MemoryLog : EventLog {
    private val written = mutableListOf<Pair<LogLevel, String>>()

    val lines: List<String> get() = synchronized(written) { written.map { it.second } }

    override fun write(level: LogLevel, line: LogLine) {
        synchronized(written) { written += level to line.text }
    }
}
