package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNetwork
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.model.backup.DeviceStorage
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.BackupStatusSource
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Lo que el estado del respaldo lee fuera de `backup_state`: el disco, los trabajos de subida y la
 * red. Cada pieza es de una parte distinta de la app, y aquí solo se pregunta.
 *
 * Nada de esto espera a la red: el estado se calcula al abrir la app aunque no haya ninguna.
 *
 * @param restorePending si hay una restauración en curso o a medias. Lee el disco.
 * @param policyAccepted si la persona aceptó la versión vigente de la política y los términos.
 * @param uploadWork cómo están los trabajos de subida.
 * @param network la red activa, en este momento.
 * @param firstHandoffAt con la nube del teléfono, la primera copia entregada, en milisegundos UTC,
 *   o nulo. Lee el disco.
 * @param changes emite al empezar a mirarlo, y otra vez cuando cambia algo de esto que no pasa por
 *   `backup_state`, como aceptar la política: así el estado se calcula otra vez sin esperar a la
 *   siguiente venta.
 * @param storage el espacio libre del teléfono y lo que ocupa la base, para los avisos de espacio,
 *   o nulo si no se pudo leer. Lee el disco.
 * @param connectedAt cuándo se conectó el destino, en milisegundos UTC, según `link.json`, o nulo.
 *   Lee el disco.
 */
internal class BackupStatusEnvironment(
    val restorePending: () -> Boolean,
    val policyAccepted: suspend () -> Boolean,
    val uploadWork: suspend () -> BackupUploadWork,
    val network: () -> BackupNetwork,
    val firstHandoffAt: suspend () -> Long?,
    val changes: Flow<Unit> = flowOf(Unit),
    val storage: () -> DeviceStorage? = { null },
    val connectedAt: suspend () -> Long? = { null },
) {
    companion object {
        /**
         * Sin nada fuera de la base: ninguna restauración, ningún trabajo, ninguna red ni ranuras.
         * Lo usan los puertos que se arman a mano, como los de las pruebas y el generador de datos.
         *
         * La política cuenta como aceptada: esos puertos no tienen a quién preguntarle, y la
         * compuerta de la app la lee de su archivo.
         */
        val Detached =
            BackupStatusEnvironment(
                restorePending = { false },
                policyAccepted = { true },
                uploadWork = { BackupUploadWork.NONE },
                network = { BackupNetwork.NONE },
                firstHandoffAt = { null },
            )
    }
}

/**
 * Lee lo que el estado del respaldo necesita, sin escribir nada: la fila de `backup_state`, si la
 * base tiene algo del negocio, la política con datos móviles y lo que dé [environment].
 *
 * Vuelve a leer cada vez que cambia `backup_state`, que es lo que cambia con cada venta y con cada
 * copia, y cada vez que [BackupStatusEnvironment.changes] avisa. El resto se lee en ese momento,
 * fuera del hilo principal.
 *
 * @param meteredPolicy cada cuánto se respalda con datos móviles, de los ajustes.
 */
internal class BackupStatusReader(
    private val state: BackupStateDao,
    private val meteredPolicy: suspend () -> MeteredBackupPolicy,
    private val environment: BackupStatusEnvironment,
    private val ioContext: CoroutineContext,
) : BackupStatusSource {
    override suspend fun current(): BackupStatusFacts =
        withContext(ioContext) { factsOf(state.get()) }

    override fun observe(): Flow<BackupStatusFacts> =
        combine(state.observe(), environment.changes) { row, _ -> row }
            .map { factsOf(it) }
            .flowOn(ioContext)

    private suspend fun factsOf(row: BackupStateEntity?): BackupStatusFacts = backupStatusFacts(
        row = row,
        restoreInProgress = environment.restorePending(),
        policyAccepted = environment.policyAccepted(),
        databaseEmpty = state.isEmpty(),
        uploadWork = environment.uploadWork(),
        firstHandoffAt = environment.firstHandoffAt(),
        meteredPolicy = meteredPolicy(),
        network = environment.network(),
        storage = environment.storage(),
        connectedAt = environment.connectedAt(),
    )
}

/**
 * Arma lo que necesita el estado del respaldo a partir de la fila guardada.
 *
 * Un valor guardado que no se reconoce nunca se ve como algo bueno: un enlace o un destino
 * desconocido cuentan como sin destino, porque el respaldo tampoco sube a él, y un fallo
 * desconocido como un fallo no previsto. Sin la fila no hay respaldo posible, y se ve igual que sin
 * destino.
 */
internal fun backupStatusFacts(
    row: BackupStateEntity?,
    restoreInProgress: Boolean,
    policyAccepted: Boolean,
    databaseEmpty: Boolean,
    uploadWork: BackupUploadWork,
    firstHandoffAt: Long?,
    meteredPolicy: MeteredBackupPolicy,
    network: BackupNetwork,
    storage: DeviceStorage? = null,
    connectedAt: Long? = null,
): BackupStatusFacts = BackupStatusFacts(
    restoreInProgress = restoreInProgress,
    linkState =
        BackupLinkState.entries.firstOrNull { it.name == row?.linkState }
            ?: BackupLinkState.NOT_CONNECTED,
    destinationKind = BackupDestinationKind.entries.firstOrNull { it.name == row?.destinationKind },
    policyAccepted = policyAccepted,
    databaseEmpty = databaseEmpty,
    uploadWork = uploadWork,
    changeSeq = row?.changeSeq ?: 0,
    verifiedSeq = row?.verifiedSeq ?: 0,
    firstUnsyncedAt = row?.firstUnsyncedAt?.let(Instant::fromEpochMilliseconds),
    lastSuccessAt = row?.lastSuccessAt?.let(Instant::fromEpochMilliseconds),
    lastUploadFinishedAt = row?.lastUploadFinishedAt?.let(Instant::fromEpochMilliseconds),
    failureCode =
        row?.failureCode?.let { code ->
            BackupFailureCode.entries.firstOrNull { it.name == code }
                ?: BackupFailureCode.UNEXPECTED
        },
    firstHandoffAt = firstHandoffAt?.let(Instant::fromEpochMilliseconds),
    lastHumanCheckAt = row?.lastHumanCheckAt?.let(Instant::fromEpochMilliseconds),
    meteredPolicy = meteredPolicy,
    network = network,
    storage = storage,
    lastFullVerifyAt = row?.lastFullVerifyAt?.let(Instant::fromEpochMilliseconds),
    connectedAt = connectedAt?.let(Instant::fromEpochMilliseconds),
)
