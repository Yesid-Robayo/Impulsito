package io.github.yesidrobayo.shopledger.domain.model.backup

import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import kotlin.time.Instant

/**
 * El estado del respaldo con lo que las pantallas necesitan para decirlo con hechos: cuándo se
 * guardó lo último, a qué destino y, si algo falla, qué y por qué.
 *
 * @property report el estado que gana, los cambios sin copia y los avisos aparte.
 * @property destinationKind el destino conectado, o nulo sin destino.
 * @property lastSuccessAt la última copia comprobada, o con la nube del teléfono la última entrega
 *   comprobada; nula si no hubo.
 * @property failureCode el fallo que dejó la última ejecución, si lo hay.
 * @property staleReason con [BackupStatus.STALE], por qué parece que no se ha podido copiar, si se
 *   sabe.
 * @property meteredPolicy cada cuánto se respalda con datos móviles.
 * @property nextMeteredUploadAt con WebDAV y un intervalo con datos móviles, cuándo vence la espera
 *   desde la última subida; nulo si no hay intervalo o nunca se subió.
 * @property lastFullReadAt con WebDAV, la última lectura completa de una copia, o la conexión si
 *   nunca hubo una: desde ahí cuenta el aviso de comprobación completa atrasada.
 * @property computedAt la hora con que se calculó todo: los tiempos relativos se miden desde ella.
 * @property invariantFailures con `LOCAL_INVARIANT`, cuántos productos tienen un stock que no
 *   coincide con su historial; 0 si no se sabe o no hay.
 */
data class BackupOverview(
    val report: BackupStatusReport,
    val destinationKind: BackupDestinationKind?,
    val lastSuccessAt: Instant?,
    val failureCode: BackupFailureCode?,
    val staleReason: StaleReason?,
    val meteredPolicy: MeteredBackupPolicy,
    val nextMeteredUploadAt: Instant?,
    val lastFullReadAt: Instant?,
    val computedAt: Instant,
    val invariantFailures: Long = 0,
)

/** Por qué un día sin copia, si se sabe. Cada uno tiene su frase. */
enum class StaleReason {
    /** La hora del teléfono es anterior a un cambio o a la última copia. */
    CLOCK,

    /** Las copias son solo con Wi-Fi, y no lo hay. */
    NO_WIFI,

    /** No hay una red que el sistema haya comprobado. */
    NETWORK,
}
