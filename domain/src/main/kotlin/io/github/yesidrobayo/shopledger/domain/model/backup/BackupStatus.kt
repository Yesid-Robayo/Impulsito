package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * El estado del respaldo que ve la persona. No se guarda: se calcula cada vez que la app lo pide, a
 * partir del estado guardado, el reloj y lo que diga el teléfono, sin esperar a ningún trabajo en
 * segundo plano.
 *
 * El orden de los valores es el de prioridad: cuando varios se cumplen a la vez, gana el primero.
 * Por eso no se reordenan. Un estado que dice que todo está bien nunca tapa un cambio sin copia de
 * más de un día, ni un reloj que retrocedió.
 */
enum class BackupStatus {
    /** Hay una restauración sin terminar. La pantalla muestra su progreso, no un fallo. */
    RESTORING,

    /** No hay destino conectado. La pantalla de inicio ya tiene su aviso permanente. */
    NOT_CONNECTED,

    /** Falta aceptar la versión vigente de la política y los términos. Nada sale al destino. */
    POLICY_PENDING,

    /** El destino ya no deja entrar. La persona vuelve a conectar cuando quiera. */
    PERMISSION_LOST,

    /** El destino tiene algo que la app no pisa sin preguntar. */
    CONFLICT,

    /** La base no tiene nada del negocio todavía. */
    NOTHING_TO_BACK_UP,

    /** Una copia está subiendo ahora. */
    UPLOADING,

    /** El cambio más viejo sin copia lleva más de un día, o el reloj del teléfono retrocedió. */
    STALE,

    /** La última ejecución dejó un fallo que el respaldo no resuelve solo. */
    FAILED,

    /** Con la nube del teléfono, toca revisar en esa nube que las copias aparezcan. */
    CHECK_DUE,

    /** Con la nube del teléfono, la última copia se entregó a la otra app. Entregada, no subida. */
    HANDED_OFF,

    /** Con WebDAV, cada cambio tiene una copia comprobada en el servidor. */
    UP_TO_DATE,

    /** Solo hay red con datos móviles, y el ahorro de datos del sistema no deja usarla. */
    BLOCKED_BY_DATA_SAVER,

    /** La copia espera una red sin datos móviles, o que venza el intervalo con datos móviles. */
    WAITING_FOR_WIFI,

    /** La copia está encolada y no hay red. */
    WAITING_FOR_NETWORK,

    /** Hay cambios sin copia y la espera es la normal, por el agrupamiento o el intervalo. */
    PENDING,
}
