package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Dónde van las copias de la base: el servicio WebDAV que eligió la persona, o tres documentos fijos
 * en la nube del teléfono. Se guarda con su nombre en `backup_state.destination_kind`, así que los
 * valores no se renombran.
 *
 * El motor de respaldo es uno solo para los dos. Lo que cambia entre ellos vive detrás del destino:
 * cómo se lista, cómo se sube, cómo se comprueba lo subido y si hay poda. Lo que la persona ve
 * también cambia: con WebDAV el servidor confirma la copia, y con la nube del teléfono solo se sabe
 * que se entregó a la otra app, nunca que subió.
 */
enum class BackupDestinationKind {
    /** Un servicio WebDAV con usuario y contraseña. Hay lista, poda y verificación por el servidor. */
    WEBDAV,

    /**
     * Tres documentos que la persona creó con el selector del sistema, y que la app reescribe por
     * turnos. No hay lista ni poda: lo que hay en cada uno lo recuerda la app.
     */
    DOCUMENT_SLOTS,
}
