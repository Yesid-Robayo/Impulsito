package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Lo que la pantalla del respaldo dice del destino conectado, leído sin red: con WebDAV, el usuario
 * del servicio y su dirección; con la nube del teléfono, su nombre de la lista fija de proveedores.
 *
 * Nunca lleva la contraseña ni una dirección con parámetros: solo lo que la persona escribió como
 * usuario y el nombre del servidor.
 */
sealed interface BackupDestinationInfo {
    /**
     * Un servicio WebDAV. [account] es el usuario con que se conectó, y [host] el nombre del
     * servidor; cualquiera puede faltar si no se guardó.
     */
    data class WebDav(val account: String?, val host: String?) : BackupDestinationInfo

    /** Las tres ranuras en la nube del teléfono, que se llama [providerLabel] si se sabe. */
    data class Slots(val providerLabel: String?) : BackupDestinationInfo
}
