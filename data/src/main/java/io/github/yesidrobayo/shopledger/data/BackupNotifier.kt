package io.github.yesidrobayo.shopledger.data

/**
 * Las notificaciones locales del respaldo, que lanza un trabajo en segundo plano de esta capa.
 *
 * Esta capa no tiene recursos de texto ni sabe cómo se ve una notificación: la capa de la interfaz
 * implementa este puerto con sus textos, en el idioma elegido, y con el canal del respaldo. Ninguna
 * lleva montos, nombres ni datos del negocio, y ninguna usa la red.
 */
fun interface BackupNotifier {
    /**
     * Lanza [notification]. Devuelve `true` si el sistema la aceptó, y `false` si no puede salir,
     * por ejemplo sin el permiso de avisos o con el canal del respaldo silenciado por la persona.
     * Responde enseguida, sin esperar a la persona.
     */
    fun show(notification: BackupNotification): Boolean

    companion object {
        /**
         * Sin interfaz: nada sale. Lo usan las capas de datos que se arman a mano, como las de las
         * pruebas.
         */
        val None = BackupNotifier { false }
    }
}

/**
 * Una notificación del respaldo. Es una lista cerrada: quien implementa el puerto le da a cada una
 * sus textos, y una sin textos no compila.
 */
enum class BackupNotification {
    /** El cambio más viejo sin copia lleva un día o más, con un destino conectado. */
    STALE,
}
