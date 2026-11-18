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
sealed interface BackupNotification {
    /** El cambio más viejo sin copia lleva un día o más, con un destino conectado. */
    data object Stale : BackupNotification

    /**
     * Con el cifrado activo, toca escribir el código de recuperación para comprobar que sigue a mano.
     * No lleva el código ni nada del negocio.
     */
    data object RecoveryCheckDue : BackupNotification

    /**
     * Pasaron 60 días sin buscar una versión nueva, con la búsqueda permitida y la automática
     * apagada. Sale sin red: solo pide abrir la pantalla de actualizaciones, y no dice si hay una.
     */
    data object UpdateCheckDue : BackupNotification

    /**
     * Con la nube del teléfono, toca revisar en esa nube que la copia aparezca. Sale con cualquier
     * estado, también con un fallo: entregada no es subida, y solo la persona puede comprobarlo.
     *
     * @property providerLabel el nombre de esa nube, de la lista fija de proveedores de la app.
     */
    data class CheckDue(val providerLabel: String) : BackupNotification {
        // El nombre es de la lista fija, pero no hace falta en un registro.
        override fun toString(): String = "CheckDue"
    }
}
