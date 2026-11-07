package io.github.yesidrobayo.shopledger.domain.model.backup

import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Los plazos con que el estado del respaldo decide qué mostrar. Son valores de diseño fijos, iguales
 * en cualquier teléfono y en cualquier región.
 */
object BackupTimings {
    /**
     * Cuánto puede quedar sin copia el cambio más viejo antes de avisar que el negocio lleva un día
     * sin respaldo. Se mide desde ese cambio, no desde la última copia: un negocio que cierra un día
     * no ve el aviso con su primera venta del día siguiente.
     */
    val STALE_NOTICE: Duration = 24.hours

    /** Cuánto dura la espera corta en que el texto dice que los cambios se guardan en un momento. */
    val SAVING_SOON: Duration = 15.minutes

    /** Cada cuánto, con la nube del teléfono, toca revisar en esa nube que las copias aparezcan. */
    val HUMAN_CHECK: Duration = 7.days

    /**
     * Lo menos que pasa entre dos escrituras automáticas en la nube del teléfono, con cualquier red:
     * cada escritura es una subida completa de la otra app.
     */
    val SLOT_MIN_INTERVAL: Duration = 60.minutes

    /**
     * Cuánto espera la copia después del último cambio, para juntar en una sola los cambios
     * seguidos. Cada cambio nuevo vuelve a empezar la espera.
     */
    val BACKUP_DEBOUNCE: Duration = 90.seconds

    /**
     * Lo más que una racha de cambios puede posponer la copia: pasado esto desde el cambio más viejo
     * sin copia, la subida se encola aunque sigan llegando cambios.
     */
    val BACKUP_MAX_WAIT: Duration = 10.minutes

    /**
     * Con el servicio lleno y sin un espacio libre que el servicio informe, cada cuánto una copia
     * automática lo vuelve a intentar: no se puede saber antes si ya cabe, y cada intento sube la
     * copia entera.
     */
    val QUOTA_RETRY: Duration = 24.hours

    /**
     * Los fallos de transporte seguidos tras los que el fallo queda a la vista como persistente. El
     * trabajo sigue reintentando: solo cambia lo que se muestra.
     */
    const val TRANSPORT_FAILURES_BEFORE_PERSISTENT: Long = 6

    /**
     * Lo menos que pasa entre dos subidas automáticas con datos móviles, según [policy], o nulo si
     * con datos móviles no hay subidas automáticas.
     */
    fun meteredInterval(policy: MeteredBackupPolicy): Duration? = when (policy) {
        MeteredBackupPolicy.EVERY_60_MIN -> 60.minutes
        MeteredBackupPolicy.EVERY_30_MIN -> 30.minutes
        MeteredBackupPolicy.WIFI_ONLY -> null
    }
}
