package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.ConnectedDestination
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.PinRelease
import io.github.yesidrobayo.shopledger.domain.port.BackupSettingsPort
import io.github.yesidrobayo.shopledger.domain.rule.backup.UploadUsageRule
import kotlinx.coroutines.sync.Mutex

/**
 * Los ajustes del respaldo con WebDAV: el consumo del mes, liberar una copia fijada y volver a
 * programar la subida tras cambiar la política con datos móviles.
 *
 * Liberar toma el cerrojo del respaldo sin esperar: con una copia en curso, la poda de esa copia
 * podría estar mirando las marcas, y la persona reintenta en un momento. Nunca borra nada: la
 * copia solo queda sin la marca, y la retención decide después.
 *
 * @param state la fila guardada del respaldo, o nula si la base no abre.
 * @param destinations abre el destino conectado, igual que para el respaldo.
 * @param usage los bytes subidos, en `upload-usage.json`.
 * @param month el mes de ahora en la zona del negocio, o nulo si no se sabe.
 * @param backupLock el cerrojo del respaldo: una ejecución a la vez en todo el proceso.
 * @param rescheduled lo que vuelve a encolar la subida con la política nueva.
 * @param encryption el cifrado de las copias en este teléfono, el mismo que mira el motor al armar
 *   cada copia.
 */
internal class BackupSettingsService(
    private val state: suspend () -> BackupStateEntity?,
    private val destinations: DestinationOpener,
    private val usage: UploadUsageStore,
    private val month: suspend () -> String?,
    private val backupLock: Mutex,
    private val rescheduled: suspend () -> Unit,
    private val encryption: suspend () -> LocalEncryption = { LocalEncryption.NONE },
) : BackupSettingsPort {
    override suspend fun uploadedThisMonth(): Long {
        val current = month() ?: return 0
        return UploadUsageRule.inMonth(usage.load(), current)
    }

    override suspend fun releasePinnedCopy(key: String): PinRelease {
        val row = state() ?: return PinRelease.FAILED
        // Solo WebDAV fija copias: con ranuras no hay nada que liberar.
        if (row.destinationKind != BackupDestinationKind.WEBDAV.name) return PinRelease.FAILED
        if (row.linkState == BackupLinkState.NOT_CONNECTED.name) return PinRelease.FAILED
        val id = row.destinationId ?: return PinRelease.FAILED
        if (key.isEmpty() || key.length > CopyRef.MAX_LENGTH) return PinRelease.FAILED
        if (!backupLock.tryLock()) return PinRelease.FAILED
        try {
            val link = ConnectedDestination(BackupDestinationKind.WEBDAV, id, row.destinationFolder)
            val destination =
                when (val opened = destinations.open(link)) {
                    is DestinationOpen.Opened -> opened.destination

                    DestinationOpen.PermissionLost, DestinationOpen.RetryLater ->
                        return PinRelease.FAILED
                }
            return when (
                CopyPinning.release(destination, CopyRef(key), TransferControl.Unbounded)
            ) {
                is DestinationResult.Success -> PinRelease.RELEASED
                is DestinationResult.Failure -> PinRelease.FAILED
            }
        } finally {
            backupLock.unlock()
        }
    }

    override suspend fun onMeteredPolicyChanged() = rescheduled()

    override suspend fun copiesEncrypted(): Boolean = encryption().active
}
