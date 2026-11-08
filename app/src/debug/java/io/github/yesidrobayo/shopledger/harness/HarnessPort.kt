package io.github.yesidrobayo.shopledger.harness

import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.backup.BackupNowRequest
import io.github.yesidrobayo.shopledger.data.harness.BackupHarness
import io.github.yesidrobayo.shopledger.data.harness.HarnessRestore
import io.github.yesidrobayo.shopledger.data.harness.HarnessResult
import io.github.yesidrobayo.shopledger.data.harness.HarnessState
import io.github.yesidrobayo.shopledger.data.harness.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupStatusUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Lo que la pantalla del arnés pide a la capa de datos. Así su estado se prueba en la JVM con un
 * doble, sin base ni red.
 */
internal interface HarnessPort {
    /** El estado del respaldo como lo verá la persona, o nulo si la base no abre. */
    fun statusReports(): Flow<BackupStatusReport?>

    /** Los contadores guardados del respaldo, o nulo si la base no abre. */
    fun savedState(): Flow<HarnessState?>

    suspend fun policyStatus(): PolicyConsentStatus

    suspend fun acceptPolicy(): HarnessResult

    suspend fun connectWebDav(url: String, username: String, password: String): HarnessResult

    /** Conecta la cuenta de prueba que llegó por `adb` al archivo privado, y lo borra. */
    suspend fun connectWebDavFromFile(): HarnessResult

    suspend fun prepareSlots(uris: List<String>): HarnessResult

    suspend fun recordInventedChanges(count: Int): HarnessResult

    suspend fun backupNow(): BackupNowRequest

    suspend fun restoreFromFile(uri: String): HarnessRestore

    /** Restaura la copia más reciente que lista el destino conectado. */
    suspend fun restoreNewestCopy(): HarnessRestore

    suspend fun answer(answer: RestoreAnswer): HarnessRestore

    suspend fun cancelRestore(): Boolean
}

/**
 * El arnés sobre la capa de datos de la app.
 *
 * @param appName el nombre de la app, del recurso que nunca se traduce: da la carpeta en WebDAV.
 * @param supplierPrefix el comienzo del nombre de los proveedores inventados, en el idioma de la
 *   pantalla.
 */
internal class DataHarnessPort(
    private val data: DataGraph,
    private val harness: BackupHarness,
    private val clock: Clock,
    private val appName: String,
    private val supplierPrefix: String,
) : HarnessPort {
    override fun statusReports(): Flow<BackupStatusReport?> = flow {
        when (val access = data.open()) {
            is DataAccess.Ready ->
                emitAll(ObserveBackupStatusUseCase(access.ports.backupStatus, clock)())

            is DataAccess.Rescue -> emit(null)
        }
    }

    override fun savedState(): Flow<HarnessState?> = harness.observeState()

    override suspend fun policyStatus(): PolicyConsentStatus = harness.policyStatus()

    override suspend fun acceptPolicy(): HarnessResult = harness.acceptPolicy()

    override suspend fun connectWebDav(
        url: String,
        username: String,
        password: String,
    ): HarnessResult = harness.connectWebDav(url, username, password, appName)

    override suspend fun connectWebDavFromFile(): HarnessResult =
        harness.connectWebDavFromFile(appName)

    override suspend fun prepareSlots(uris: List<String>): HarnessResult =
        harness.prepareSlots(uris)

    override suspend fun recordInventedChanges(count: Int): HarnessResult =
        harness.recordInventedChanges(supplierPrefix, count)

    override suspend fun backupNow(): BackupNowRequest = harness.backupNow()

    override suspend fun restoreFromFile(uri: String): HarnessRestore = harness.restoreFromFile(uri)

    override suspend fun restoreNewestCopy(): HarnessRestore = harness.restoreNewestCopy()

    override suspend fun answer(answer: RestoreAnswer): HarnessRestore = harness.answer(answer)

    override suspend fun cancelRestore(): Boolean = harness.cancelRestore()
}
