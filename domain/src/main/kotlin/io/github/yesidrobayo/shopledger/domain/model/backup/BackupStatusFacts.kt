package io.github.yesidrobayo.shopledger.domain.model.backup

import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import kotlin.time.Instant

/**
 * Lo que hace falta saber para calcular el estado del respaldo, ya leído del disco, del estado
 * guardado y del teléfono. No incluye la hora: el estado se calcula con el reloj del momento en que
 * se pide.
 *
 * @property restoreInProgress hay una restauración en curso o a medias.
 * @property linkState el enlace con el destino.
 * @property destinationKind el destino conectado, o nulo si no hay uno que se reconozca.
 * @property policyAccepted la persona aceptó la versión vigente de la política y los términos.
 * @property databaseEmpty la base no tiene nada del negocio.
 * @property uploadWork cómo están los trabajos de subida.
 * @property changeSeq los cambios del negocio confirmados.
 * @property verifiedSeq los cambios que trae la última copia comprobada.
 * @property firstUnsyncedAt el cambio más viejo sin copia, o nulo si no hay.
 * @property lastSuccessAt la última copia comprobada, o nulo si no hubo.
 * @property lastUploadFinishedAt cuándo se envió el último byte de la última subida, o nulo.
 * @property failureCode el fallo que dejó la última ejecución, o nulo.
 * @property firstHandoffAt con la nube del teléfono, la primera copia entregada, o nulo si no hubo.
 * @property lastHumanCheckAt la última vez que la persona dijo haber visto la copia en esa nube.
 * @property meteredPolicy cada cuánto se respalda con datos móviles.
 * @property network la red activa.
 * @property storage el espacio del teléfono y lo que ocupa la base, o nulo si no se pudo leer. Solo
 *   sirve a los avisos de espacio: nunca cambia el estado.
 */
data class BackupStatusFacts(
    val restoreInProgress: Boolean,
    val linkState: BackupLinkState,
    val destinationKind: BackupDestinationKind?,
    val policyAccepted: Boolean,
    val databaseEmpty: Boolean,
    val uploadWork: BackupUploadWork,
    val changeSeq: Long,
    val verifiedSeq: Long,
    val firstUnsyncedAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastUploadFinishedAt: Instant?,
    val failureCode: BackupFailureCode?,
    val firstHandoffAt: Instant?,
    val lastHumanCheckAt: Instant?,
    val meteredPolicy: MeteredBackupPolicy,
    val network: BackupNetwork,
    val storage: DeviceStorage? = null,
)
