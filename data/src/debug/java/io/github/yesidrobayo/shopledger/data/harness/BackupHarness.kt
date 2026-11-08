package io.github.yesidrobayo.shopledger.data.harness

import android.content.Context
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.BackupNowRequest
import io.github.yesidrobayo.shopledger.data.backup.BackupScheduler
import io.github.yesidrobayo.shopledger.data.backup.RestoreCoordinator
import io.github.yesidrobayo.shopledger.data.backup.RestoreDecision
import io.github.yesidrobayo.shopledger.data.backup.RestoreRefusal
import io.github.yesidrobayo.shopledger.data.backup.RestoreSource
import io.github.yesidrobayo.shopledger.data.backup.RestoreStage
import io.github.yesidrobayo.shopledger.data.backup.SafetyCopy
import io.github.yesidrobayo.shopledger.data.destination.ConnectResult
import io.github.yesidrobayo.shopledger.data.destination.ConnectedDestination
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.DestinationLink
import io.github.yesidrobayo.shopledger.data.destination.DestinationLinkStore
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.WebDavConnector
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateStore
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PolicyConsentPort
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * El arnés de depuración del respaldo: lo que hacen las pantallas de conectar, preparar los archivos
 * y restaurar mientras no existan, para subir, verificar y restaurar copias reales desde un
 * emulador o un celular de pruebas.
 *
 * **Solo vive en el código de `debug`:** ninguna compilación de publicación lo trae. Usa las mismas
 * piezas que usará la app: la prueba de conexión y la credencial sellada, `slots.json`, el
 * programador del respaldo, la preparación de una restauración y el estado guardado.
 *
 * - **La contraseña solo pasa por memoria:** llega de la pantalla, va a la prueba de conexión y, si
 *   pasa, a la credencial sellada. Nunca se guarda en otro lado ni va a un registro.
 * - **Las ranuras siguen las reglas de la app:** solo nubes de la lista fija, tres documentos
 *   distintos, que se puedan escribir y con tamaño. Si alguno no sirve, suelta sus permisos sin
 *   tocar los documentos.
 * - **Los datos que registra son inventados,** con el prefijo de nombre que le da la pantalla.
 * - **Nada corre a la vez que una copia:** conectar y preparar toman el cerrojo del respaldo sin
 *   esperar, y con una copia o una restauración en curso responden que está ocupado.
 * - **Una cuenta real entra por un archivo, no por la pantalla:** para probar con un servicio de
 *   verdad desde un emulador, la cuenta llega por `adb` a un archivo privado que se borra al leerlo.
 * - **Se restaura lo que lista el destino:** la copia más reciente con metadatos válidos, por el
 *   mismo camino que el de la lista de copias, con su suma esperada.
 *
 * Los resultados son códigos cortos, sin datos del negocio ni de la cuenta: la pantalla los muestra
 * tal cual.
 */
class BackupHarness internal constructor(
    private val open: suspend () -> DataAccess,
    private val backupLock: Mutex,
    private val scheduler: BackupScheduler,
    private val restore: suspend () -> RestoreCoordinator,
    private val policy: PolicyConsentPort,
    private val connector: WebDavConnector,
    private val destinations: DestinationOpener,
    private val links: DestinationLinkStore,
    private val slots: SlotStateStore,
    private val documents: HarnessDocuments,
    private val credentialFile: File,
    private val clock: Clock,
    private val ioContext: CoroutineContext,
) {
    /**
     * El arnés sobre la capa de datos de la app, con sus archivos en la carpeta sin copia de
     * Android, los mismos que lee el respaldo.
     */
    constructor(
        graph: DataGraph,
        context: Context,
        clock: Clock,
        ioContext: CoroutineContext,
    ) : this(
        open = graph::open,
        backupLock = graph.backupMutex,
        scheduler = graph.backupScheduler,
        restore = graph::restoreCoordinator,
        policy = graph.policyConsent,
        connector =
            WebDavConnector(
                graph.http,
                graph.webDavCredentials,
                DestinationLinkStore({ context.noBackupFilesDir }, ioContext),
            ),
        destinations = graph.destinations,
        links = DestinationLinkStore({ context.noBackupFilesDir }, ioContext),
        slots = FileSlotStateStore.inNoBackupDir(context, ioContext),
        documents = ResolverHarnessDocuments(context.applicationContext.contentResolver),
        credentialFile = File(context.noBackupFilesDir, HarnessCredentialFile.FILE_NAME),
        clock = clock,
        ioContext = ioContext,
    )

    private var pendingSafetyCopies: List<SafetyCopy> = emptyList()

    /** El estado guardado del respaldo, de nuevo con cada cambio, o nulo si la base no abre. */
    fun observeState(): Flow<HarnessState?> = flow {
        when (val access = open()) {
            is DataAccess.Ready -> emitAll(access.ports.backupState.observe().map { it?.toState() })
            is DataAccess.Rescue -> emitAll(flowOf(null))
        }
    }

    /** Si la persona aceptó la versión vigente de la política y los términos. */
    suspend fun policyStatus(): PolicyConsentStatus = policy.status()

    /** Acepta la versión vigente, como la hoja de la política. Sin eso, ninguna copia sale. */
    suspend fun acceptPolicy(): HarnessResult = HarnessResult.Done(policy.acceptCurrent().name)

    /**
     * Conecta el servicio WebDAV de [url] con la prueba de conexión completa. Solo si pasa sella la
     * credencial, deja el destino en el estado del respaldo y en `link.json`, y fuerza una subida.
     *
     * @param appName el nombre de la app, que da la carpeta en el servicio.
     */
    suspend fun connectWebDav(
        url: String,
        username: String,
        password: String,
        appName: String,
    ): HarnessResult {
        val ports = ready() ?: return HarnessResult.Stopped(UNAVAILABLE)
        if (!backupLock.tryLock()) return HarnessResult.Stopped(BUSY)
        val result =
            try {
                val now = clock.now().toEpochMilliseconds()
                connector.connect(url, username, password, appName, emptyList(), now) { link ->
                    record(ports, link, now)
                }
            } finally {
                backupLock.unlock()
            }
        return when (result) {
            is ConnectResult.Connected -> {
                scheduler.onDataChanged()
                HarnessResult.Done(if (result.hasCopies) "CONNECTED_WITH_COPIES" else "CONNECTED")
            }

            is ConnectResult.AddressRejected -> HarnessResult.Stopped(result.reason.name)

            ConnectResult.MissingField -> HarnessResult.Stopped("MISSING_FIELD")

            ConnectResult.InvalidAppName -> HarnessResult.Stopped("INVALID_APP_NAME")

            is ConnectResult.CheckFailed ->
                HarnessResult.Stopped("${result.step.name}: ${codeOf(result.failure)}")

            ConnectResult.SaveFailed -> HarnessResult.Stopped("SAVE_FAILED")
        }
    }

    /**
     * Conecta la cuenta WebDAV de prueba que llegó en el archivo de [HarnessCredentialFile], en la
     * carpeta sin copia de la app, como [connectWebDav]. El archivo se borra apenas se lee, también
     * si no sirve o si la prueba de conexión falla.
     */
    suspend fun connectWebDavFromFile(appName: String): HarnessResult = when (
        val read =
            withContext(ioContext) { HarnessCredentialFile.readAndDelete(credentialFile) }
    ) {
        HarnessCredentialFile.Read.Missing -> HarnessResult.Stopped("CREDENTIAL_FILE_MISSING")

        HarnessCredentialFile.Read.Invalid -> HarnessResult.Stopped("CREDENTIAL_FILE_INVALID")

        is HarnessCredentialFile.Read.Account ->
            connectWebDav(read.url.trim(), read.username.trim(), read.password, appName)
    }

    /**
     * Prepara las tres ranuras con los documentos que la persona acaba de crear en el selector, en
     * el orden A, B y C. Toma sus permisos persistentes y los revisa: si alguno no sirve, suelta los
     * que no usan las ranuras actuales y no escribe nada. Si todos sirven, escribe `slots.json`, deja
     * el destino en el estado del respaldo y en `link.json`, y fuerza una subida.
     */
    suspend fun prepareSlots(uris: List<String>): HarnessResult {
        val ports = ready() ?: return HarnessResult.Stopped(UNAVAILABLE)
        if (!backupLock.tryLock()) return HarnessResult.Stopped(BUSY)
        val result =
            try {
                prepareLocked(ports, uris)
            } finally {
                backupLock.unlock()
            }
        if (result is HarnessResult.Done) scheduler.onDataChanged()
        return result
    }

    private suspend fun prepareLocked(ports: DataPorts, uris: List<String>): HarnessResult {
        val prepared =
            withContext(ioContext) {
                val documentsInfo =
                    uris.map { uri ->
                        if (documents.takePermission(uri)) documents.inspect(uri) else null
                    }
                prepareSlotState(uris, documentsInfo)
            }
        val state =
            when (prepared) {
                is SlotPreparation.Ready -> prepared.state

                is SlotPreparation.Rejected -> {
                    releaseUnused(uris)
                    return HarnessResult.Stopped(prepared.reason.name)
                }
            }
        try {
            slots.save(state)
        } catch (_: IOException) {
            releaseUnused(uris)
            return HarnessResult.Stopped("SAVE_FAILED")
        }
        val now = clock.now().toEpochMilliseconds()
        val link =
            DestinationLink(
                kind = BackupDestinationKind.DOCUMENT_SLOTS,
                account = null,
                destinationId = SLOTS_DESTINATION_ID,
                folderUrl = null,
                connectedAtMillis = now,
            )
        if (!record(ports, link, now)) return HarnessResult.Stopped("SAVE_FAILED")
        // `link.json` va de último: si no queda escrito, el arranque lo completa desde la base.
        return try {
            links.write(link)
            HarnessResult.Done("SLOTS_PREPARED")
        } catch (_: IOException) {
            HarnessResult.Done("SLOTS_PREPARED_LINK_PENDING")
        }
    }

    /** Suelta los permisos de [uris], salvo los de las ranuras que ya estaban preparadas. */
    private suspend fun releaseUnused(uris: List<String>) {
        val current =
            when (val load = slots.load()) {
                is SlotStateLoad.Loaded -> load.state.slots.values.map { it.uri }.toSet()
                SlotStateLoad.Missing, SlotStateLoad.Unreadable -> emptySet()
            }
        withContext(ioContext) {
            uris.filterNot { it in current }.distinct().forEach(documents::releasePermission)
        }
    }

    private suspend fun record(ports: DataPorts, link: DestinationLink, now: Long): Boolean =
        ports.backupState.connectDestination(
            kind = link.kind.name,
            account = link.account,
            destinationId = link.destinationId,
            folder = link.folderUrl,
            now = now,
        ) == 1

    /**
     * Registra [count] proveedores inventados, cada uno en su propia transacción, como los
     * registraría la persona: cada uno cuenta como un cambio y programa la copia.
     *
     * @param namePrefix el comienzo del nombre, en el idioma de la pantalla. Lo sigue un número que
     *   no se repite, para que ningún nombre choque con otro.
     */
    suspend fun recordInventedChanges(namePrefix: String, count: Int): HarnessResult {
        require(count > 0) { "Al menos un cambio" }
        val ports = ready() ?: return HarnessResult.Stopped(UNAVAILABLE)
        val stamp = clock.now()
        return try {
            repeat(count) { index ->
                val name = "$namePrefix ${stamp.toEpochMilliseconds()}-${index + 1}"
                ports.transactions.write {
                    ports.suppliers.insert(
                        SupplierDetails(
                            name = name,
                            normalizedName = TextNormalizer.normalize(name),
                            phone = null,
                            email = null,
                            notes = null,
                            paymentTermsDays = null,
                        ),
                        clock.now(),
                    )
                    TxResult.Commit(Unit)
                }
            }
            HarnessResult.Done("RECORDED_$count")
        } catch (failure: StorageFailureException) {
            HarnessResult.Stopped(failure.failure.name)
        }
    }

    /** `Respaldar ahora`: una subida sin espera ni intervalo, con cualquier red. */
    suspend fun backupNow(): BackupNowRequest = scheduler.backupNow()

    /**
     * Empieza a restaurar desde un archivo elegido con el selector del sistema, por el camino de
     * `Buscar un archivo`. El nombre del archivo nunca se usa: el formato sale de sus bytes.
     */
    suspend fun restoreFromFile(uri: String): HarnessRestore {
        val declared = withContext(ioContext) { documents.inspect(uri)?.sizeBytes }
        val source = RestoreSource.PickedFile(declared) {
            withContext(ioContext) { documents.open(uri) }
        }
        return restore().start(source).toHarness()
    }

    /**
     * Empieza a restaurar la copia más reciente que lista el destino conectado, por el camino de la
     * lista de copias: baja la copia y la compara con la suma de sus metadatos antes de revisarla.
     * Si no hay destino, no abre o no tiene copias con metadatos válidos, no empieza nada.
     */
    suspend fun restoreNewestCopy(): HarnessRestore {
        val ports = ready() ?: return HarnessRestore.Stopped(UNAVAILABLE)
        val link = ports.backupState.get()?.connected()
            ?: return HarnessRestore.Stopped(NOT_CONNECTED)
        val destination =
            when (val opened = destinations.open(link)) {
                is DestinationOpen.Opened -> opened.destination
                DestinationOpen.PermissionLost -> return HarnessRestore.Stopped("PERMISSION_LOST")
                DestinationOpen.RetryLater -> return HarnessRestore.Stopped("RETRY_LATER")
            }
        val listing =
            when (val listed = destination.listCopies(TransferControl.Unbounded)) {
                is DestinationResult.Success -> listed.value

                is DestinationResult.Failure ->
                    return HarnessRestore.Stopped("LIST_FAILED ${codeOf(listed.failure)}")
            }
        val newest = newestListedCopy(listing) ?: return HarnessRestore.Stopped("NO_COPIES")
        return restore().start(RestoreSource.Listed(newest)).toHarness()
    }

    /** Sigue la restauración detenida con lo que respondió la persona. */
    suspend fun answer(answer: RestoreAnswer): HarnessRestore {
        val decision =
            when (answer) {
                RestoreAnswer.Repair -> RestoreDecision.Repair

                RestoreAnswer.ReplaceWithEmptyCopy -> RestoreDecision.ReplaceWithEmptyCopy

                RestoreAnswer.ContinueWithoutSafetyCopy -> RestoreDecision.ContinueWithoutSafetyCopy

                is RestoreAnswer.DiscardSafetyCopy ->
                    RestoreDecision.DiscardSafetyCopy(
                        pendingSafetyCopies.getOrNull(answer.index)
                            ?: return HarnessRestore.Stopped("NO_SUCH_SAFETY_COPY"),
                    )
            }
        return restore().decide(decision).toHarness()
    }

    /** Cancela la restauración en espera de una respuesta. Devuelve `false` si no había ninguna. */
    suspend fun cancelRestore(): Boolean = restore().cancel()

    private fun RestoreStage.toHarness(): HarnessRestore = when (this) {
        RestoreStage.Busy -> HarnessRestore.Stopped(BUSY)

        is RestoreStage.NotEnoughSpace -> HarnessRestore.Stopped("NOT_ENOUGH_SPACE $missingBytes")

        is RestoreStage.Refused -> HarnessRestore.Stopped("REFUSED ${refusalCode(reason)}")

        is RestoreStage.Failed -> HarnessRestore.Stopped("FAILED ${cause?.let(::codeOf)}")

        is RestoreStage.ConfirmRepair -> HarnessRestore.Question(RestoreQuestion.REPAIR, rows)

        RestoreStage.ConfirmEmptyCopy -> HarnessRestore.Question(RestoreQuestion.EMPTY_COPY, 0)

        RestoreStage.ConfirmWithoutSafetyCopy ->
            HarnessRestore.Question(RestoreQuestion.WITHOUT_SAFETY_COPY, 0)

        is RestoreStage.ChooseSafetyCopyToDiscard -> {
            pendingSafetyCopies = copies
            HarnessRestore.Question(RestoreQuestion.DISCARD_SAFETY_COPY, copies.size.toLong())
        }

        is RestoreStage.ReadyToRestart -> HarnessRestore.ReadyToRestart(warnings)
    }

    private fun refusalCode(reason: RestoreRefusal): String = when (reason) {
        is RestoreRefusal.Candidate -> reason.reason.name
        is RestoreRefusal.Download -> codeOf(reason.failure)
        else -> codeOf(reason)
    }

    /** El destino conectado según el estado guardado, o nulo si no hay ninguno. */
    private fun BackupStateEntity.connected(): ConnectedDestination? {
        if (linkState == BackupLinkState.NOT_CONNECTED.name) return null
        val kind = BackupDestinationKind.entries.firstOrNull { it.name == destinationKind }
        val id = destinationId
        return if (kind == null || id == null) {
            null
        } else {
            ConnectedDestination(kind, id, destinationFolder)
        }
    }

    private suspend fun ready(): DataPorts? = when (val access = open()) {
        is DataAccess.Ready -> access.ports
        is DataAccess.Rescue -> null
    }

    private companion object {
        /** El `destination_id` de las ranuras: no hay cuenta ni dirección que lo distinga. */
        const val SLOTS_DESTINATION_ID = "document-slots"
        const val BUSY = "BUSY"
        const val UNAVAILABLE = "DATABASE_UNAVAILABLE"
        const val NOT_CONNECTED = "NOT_CONNECTED"

        /** Solo el nombre de la clase: un texto o una referencia de archivo nunca salen de aquí. */
        fun codeOf(value: Any): String = value::class.simpleName ?: "UNKNOWN"
    }
}

/**
 * La copia más reciente de [listing]: la de mayor generación entre las que traen metadatos válidos
 * que coinciden con su nombre, y entre dos de la misma generación, la de referencia menor, como
 * elige la restauración la cabeza. Sin metadatos válidos una copia no entra: no habría suma con que
 * comparar lo que se baja.
 */
internal fun newestListedCopy(listing: CopyListing): ListedCopy? {
    val valid =
        listing.copies.mapNotNull { copy ->
            val bytes = (copy.meta as? ListedMeta.Present)?.bytes ?: return@mapNotNull null
            val decoded =
                copy.name?.let { SlMetaCodec.decode(bytes, it.identity()) }
                    ?: SlMetaCodec.decodeUnnamed(bytes)
            (decoded as? CopyMetadata.Valid)?.meta?.let { copy to it.generation }
        }
    val top = valid.maxOfOrNull { it.second } ?: return null
    return valid.filter { it.second == top }.minByOrNull { it.first.ref.value }?.first
}

/** Cómo terminó una acción del arnés, con un código corto para la pantalla. */
sealed interface HarnessResult {
    val code: String

    data class Done(override val code: String) : HarnessResult

    data class Stopped(override val code: String) : HarnessResult
}

/** En qué quedó la preparación de una restauración. */
sealed interface HarnessRestore {
    /**
     * El diario quedó escrito y la base cerrada: falta reiniciar la app, y el arranque hace el
     * reemplazo. [warnings] es cuántos avisos tuvo la copia.
     */
    data class ReadyToRestart(val warnings: Int) : HarnessRestore

    /**
     * La preparación espera una respuesta. [count] son las filas por recalcular, o las copias de
     * seguridad entre las que elegir, según la pregunta.
     */
    data class Question(val question: RestoreQuestion, val count: Long) : HarnessRestore

    /** No se restaura: la base activa sigue como estaba. */
    data class Stopped(val code: String) : HarnessRestore
}

/** Lo que la preparación de una restauración pregunta. */
enum class RestoreQuestion {
    REPAIR,
    EMPTY_COPY,
    WITHOUT_SAFETY_COPY,
    DISCARD_SAFETY_COPY,
}

/** Lo que responde la persona. */
sealed interface RestoreAnswer {
    data object Repair : RestoreAnswer

    data object ReplaceWithEmptyCopy : RestoreAnswer

    data object ContinueWithoutSafetyCopy : RestoreAnswer

    /** Descarta la copia de seguridad número [index], en el orden en que se ofrecieron. */
    data class DiscardSafetyCopy(val index: Int) : RestoreAnswer
}

/**
 * Lo que el arnés muestra del estado guardado del respaldo. Sin la cuenta ni la carpeta: solo
 * estados, contadores y horas.
 */
data class HarnessState(
    val linkState: String,
    val destinationKind: String?,
    val changeSeq: Long,
    val verifiedSeq: Long,
    val headGeneration: Long,
    val failureCode: String?,
    val consecutiveFailures: Long,
    val lastSuccessAt: Long?,
    val lastFullVerifyAt: Long?,
)

private fun BackupStateEntity.toState() = HarnessState(
    linkState = linkState,
    destinationKind = destinationKind,
    changeSeq = changeSeq,
    verifiedSeq = verifiedSeq,
    headGeneration = headGeneration,
    failureCode = failureCode,
    consecutiveFailures = consecutiveFailures,
    lastSuccessAt = lastSuccessAt,
    lastFullVerifyAt = lastFullVerifyAt,
)
