package io.github.yesidrobayo.shopledger.data.demo

import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseGate
import io.github.yesidrobayo.shopledger.data.local.GateCaller
import io.github.yesidrobayo.shopledger.data.local.GateOutcome
import io.github.yesidrobayo.shopledger.data.local.PreMigrationCopy
import io.github.yesidrobayo.shopledger.data.local.PreMigrationResult
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.error
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Lo que dio pedir entrar al modo demostración. */
sealed interface DemoEntry {
    /** La base de ejemplo quedó lista y marcada: hay que reiniciar la app para usarla. */
    data object Ready : DemoEntry

    /** Este proceso ya muestra la demostración. Nada cambió. */
    data object AlreadyActive : DemoEntry

    /** La base real no abrió, y sin ella no se sabe la región del negocio. Nada cambió. */
    data object BusinessUnavailable : DemoEntry

    /** La moneda del negocio todavía no está confirmada: sin ella no hay montos. Nada cambió. */
    data object CurrencyMissing : DemoEntry

    /** No se pudo escribir la base de ejemplo. Se descartó entera, y la base real no cambió. */
    data object Failed : DemoEntry
}

/**
 * El modo demostración del proceso: una base de ejemplo aparte, `demo.db`, que la interfaz usa en
 * lugar de la del negocio mientras existe la marca `demo-mode`.
 *
 * - **Nunca se mezcla.** La base de ejemplo tiene su propia instancia de la base, sus propios
 *   archivos y sus propios puertos, sin respaldo programado: confirmar un cambio en ella no cuenta
 *   para ninguna copia ni encola ningún trabajo. Los trabajos en segundo plano nunca la conocen:
 *   siguen con la base del negocio, y su respaldo sigue su curso.
 * - **Nunca se respalda.** Vive en `noBackupFilesDir`, y nada del respaldo, de las fotos ni del
 *   segundo destino la nombra.
 * - **Entrar y salir reinician la app**, como una restauración: así ninguna pantalla conserva
 *   datos de la otra base. La decisión se toma una vez por proceso, en [isActive], y no cambia
 *   mientras el proceso vive, salvo que la base de ejemplo no abra: entonces se descarta y se
 *   vuelve a la real.
 * - **Entrar no escribe en la base real:** solo lee su región, para que los montos de ejemplo
 *   salgan en la moneda y los formatos del negocio. Salir solo borra la marca: la base de ejemplo
 *   se borra al arrancar sin ella.
 * - Si el proceso muere en demostración, el arranque siguiente sigue en demostración.
 *
 * @param files los archivos de la demostración. Se piden en [ioContext].
 * @param openDatabase abre una base con Room, en la versión de la app, y la crea si no existe.
 * @param businessRegion la región de la base del negocio, leída sin escribir, o `null` si no abre.
 */
class DemoSession internal constructor(
    private val files: () -> DemoFiles,
    private val ioContext: CoroutineContext,
    private val clock: Clock,
    private val log: EventLog,
    private val schemaVersion: Int,
    private val openDatabase: suspend (File) -> AppDatabase,
    private val businessRegion: suspend () -> RegionalSettings?,
) {
    private val mutex = Mutex()

    @Volatile private var decided: Boolean? = null

    @Volatile private var ready: DataAccess.Ready? = null

    private val activeState = MutableStateFlow(false)

    /**
     * Si la interfaz de este proceso muestra la demostración, para la banda fija. Vale `false`
     * hasta que [isActive] decide, y pasa a `false` si la base de ejemplo no abre.
     */
    val active: StateFlow<Boolean> = activeState.asStateFlow()

    /** Si ya se decidió que este proceso muestra la demostración. No lee el disco. */
    internal val isActiveNow: Boolean get() = decided == true

    /** Los puertos de la base de ejemplo, si ya abrió en este proceso. */
    internal val currentPorts: DataPorts? get() = ready?.ports

    /**
     * Si la interfaz de este proceso usa la base de ejemplo. Se decide una sola vez, fuera del
     * hilo principal: con la marca y una base de ejemplo entera, sí. Si queda algo de una
     * demostración a medias o ya terminada, se borra, y no.
     */
    suspend fun isActive(): Boolean {
        decided?.let { return it }
        return mutex.withLock { decided ?: withContext(ioContext + NonCancellable) { decide() } }
    }

    private fun decide(): Boolean {
        val active =
            try {
                val demo = files()
                when {
                    demo.isComplete() -> true

                    demo.hasLeftovers() -> {
                        demo.discard()
                        log.info(DataEvent.DEMO_LEFTOVERS_DISCARDED)
                        false
                    }

                    else -> false
                }
            } catch (failure: Exception) {
                // Lo que no se pudo borrar se intenta otra vez en el arranque siguiente.
                log.warn(DataEvent.DEMO_DISCARD_FAILED, failure)
                false
            }
        decided = active
        activeState.value = active
        return active
    }

    /**
     * Abre la base de ejemplo para la interfaz, una sola vez por proceso, por la misma compuerta
     * que la base del negocio, con sus propios archivos. Si no abre sin riesgo, la demostración se
     * descarta y devuelve `null`: quien llama sigue con la base real.
     */
    internal suspend fun open(): DataAccess.Ready? {
        ready?.let { return it }
        return mutex.withLock {
            ready ?: withContext(ioContext + NonCancellable) { openDemoDatabase() }
        }
    }

    private suspend fun openDemoDatabase(): DataAccess.Ready? {
        val demo = files()
        val gate =
            DatabaseGate(
                files = demo.databaseFiles(),
                schemaVersion = schemaVersion,
                ioContext = ioContext,
                log = log,
                // Los datos son inventados y se descartan al salir: no hace falta copia previa.
                preMigrationCopy = PreMigrationCopy { PreMigrationResult.Ready },
            ) { file -> openDatabase(file) }
        return when (val outcome = gate.pass(GateCaller.APP_IN_FOREGROUND)) {
            is GateOutcome.Opened -> {
                log.info(DataEvent.DEMO_OPENED)
                DataAccess.Ready(demoPorts(outcome.database, demo.database)).also { ready = it }
            }

            GateOutcome.MigrationPending,
            GateOutcome.RestorePending,
            is GateOutcome.Rescue,
            -> {
                abandon(demo)
                null
            }
        }
    }

    /** La base de ejemplo no abrió: se descarta, y la interfaz sigue con la base real. */
    private fun abandon(demo: DemoFiles) {
        log.warn(DataEvent.DEMO_OPEN_FAILED)
        try {
            demo.discard()
        } catch (failure: Exception) {
            log.warn(DataEvent.DEMO_DISCARD_FAILED, failure)
        }
        decided = false
        activeState.value = false
    }

    /**
     * Prepara la base de ejemplo con [texts], en el idioma de la interfaz, y deja la marca.
     * Después la app debe reiniciarse para usarla. Nunca escribe en la base del negocio: solo lee
     * su región.
     *
     * Si algo falla a mitad, la base de ejemplo se descarta entera y la marca no se deja: el
     * arranque siguiente abre la base real, como si nada.
     */
    suspend fun enter(texts: DemoTexts): DemoEntry = mutex.withLock {
        // Este proceso decide antes de preparar nada, y no cambia de base hasta reiniciarse.
        val active = decided ?: withContext(ioContext + NonCancellable) { decide() }
        if (active) return@withLock DemoEntry.AlreadyActive
        val region =
            try {
                businessRegion()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.error(DataEvent.DEMO_PREPARE_FAILED, failure)
                null
            } ?: return@withLock DemoEntry.BusinessUnavailable
        if (!region.currency.code.isConfirmed) return@withLock DemoEntry.CurrencyMissing
        withContext(ioContext + NonCancellable) { prepare(texts, region.toInput()) }
    }

    private suspend fun prepare(texts: DemoTexts, region: RegionalSettingsInput): DemoEntry {
        val demo = files()
        return try {
            demo.discard()
            demo.prepareDirectory()
            val database = openDatabase(demo.database)
            try {
                DemoBusinessWriter(demoPorts(database, demo.database), texts, region, clock.now())
                    .write()
            } finally {
                database.close()
            }
            demo.markSeeded()
            demo.activate()
            log.info(DataEvent.DEMO_PREPARED)
            DemoEntry.Ready
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.error(DataEvent.DEMO_PREPARE_FAILED, failure)
            try {
                demo.discard()
            } catch (discardFailure: Exception) {
                log.warn(DataEvent.DEMO_DISCARD_FAILED, discardFailure)
            }
            DemoEntry.Failed
        }
    }

    /**
     * Sale del modo demostración: borra la marca, y el arranque siguiente abre la base real, que
     * no cambió, y borra la de ejemplo. Después la app debe reiniciarse. Devuelve `false` si la
     * marca no se pudo borrar: la persona sigue en la demostración y puede intentarlo otra vez.
     */
    suspend fun exit(): Boolean = withContext(ioContext + NonCancellable) {
        try {
            files().deactivate()
            log.info(DataEvent.DEMO_LEFT)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.error(DataEvent.DEMO_EXIT_FAILED, failure)
            false
        }
    }

    /**
     * Los puertos de la base de ejemplo: sin cerrojo de restauración, sin estado del respaldo y
     * sin nada que programar al confirmar. Así ningún cambio aquí cuenta para una copia.
     */
    private fun demoPorts(database: AppDatabase, file: File): DataPorts =
        DataPorts(database, file, ioContext, clock, RestoreLock { false })
}

/** La región guardada, como la confirmaría la persona en su pantalla. */
private fun RegionalSettings.toInput(): RegionalSettingsInput = RegionalSettingsInput(
    currencyCode = currency.code,
    currencyDecimals = currency.decimals,
    currencySymbol = currency.symbol,
    cashRounding = cashRounding,
    formatLocaleTag = formatLocaleTag,
    businessTimeZoneId = businessTimeZoneId,
    weekStartDay = weekStartDay,
    csvSeparator = csvSeparator,
)
