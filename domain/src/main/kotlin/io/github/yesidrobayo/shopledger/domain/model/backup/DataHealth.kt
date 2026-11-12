package io.github.yesidrobayo.shopledger.domain.model.backup

import kotlin.time.Instant

/** Cómo está una señal de `Salud de mis datos`. Cada estado tiene icono y texto, no solo color. */
enum class HealthState {
    OK,
    PENDING,
    ATTENTION,
    NOT_APPLICABLE,
}

/** Las señales de `Salud de mis datos`, en el orden en que se muestran. */
enum class HealthSignal {
    /** La última copia comprobada, o con ranuras la última entrega. */
    LAST_COPY,

    /** Con ranuras, la última revisión en la nube: lo que dijo la persona, no una comprobación. */
    CLOUD_CHECK,

    /** Con WebDAV, la última vez que se leyó una copia entera. */
    FULL_VERIFY,

    /** El último simulacro de restaurar. */
    LAST_DRILL,

    /** Con WebDAV, cuántas copias están fijadas. */
    PINNED,

    /** El segundo destino, la copia local. */
    OFFSITE,

    /** El código de recuperación de las copias cifradas, comprobado. */
    CODE,

    /** Si el teléfono tiene bloqueo de pantalla. */
    DEVICE_LOCK,

    /** El espacio libre del teléfono para armar la copia. */
    PHONE_SPACE,

    /** Con WebDAV, el espacio libre del servicio. */
    DESTINATION_SPACE,
}

/**
 * Lo que `Salud de mis datos` necesita además del estado del respaldo. Todo se lee sin red y sin
 * escribir nada.
 *
 * @property lastHumanCheckAt con ranuras, la última revisión que anotó la persona.
 * @property lastFullVerifyAt con WebDAV, la última lectura completa de una copia.
 * @property lastDrillAt el último simulacro, el mensual o el pedido, y [lastDrillResult] su
 *   resultado.
 * @property deviceLocked si el teléfono tiene bloqueo de pantalla.
 * @property serviceSpaceKnown si la última sonda supo el espacio libre del servicio.
 * @property offsiteAvailable si existe el segundo destino local. Mientras no exista, esa señal no
 *   aplica.
 * @property codeAvailable si existe el cifrado con código. Mientras no exista, esa señal no aplica.
 */
data class HealthFacts(
    val lastHumanCheckAt: Instant?,
    val lastFullVerifyAt: Instant?,
    val lastDrillAt: Instant?,
    val lastDrillResult: BackupDrillResult?,
    val deviceLocked: Boolean,
    val serviceSpaceKnown: Boolean,
    val offsiteAvailable: Boolean = false,
    val codeAvailable: Boolean = false,
)

/**
 * Una fila de `Salud de mis datos`.
 *
 * @property at el momento que dice la fila, si lo tiene.
 * @property count con [HealthSignal.PINNED], cuántas copias están fijadas, si se sabe.
 */
data class HealthRow(
    val signal: HealthSignal,
    val state: HealthState,
    val at: Instant? = null,
    val count: Int? = null,
)
