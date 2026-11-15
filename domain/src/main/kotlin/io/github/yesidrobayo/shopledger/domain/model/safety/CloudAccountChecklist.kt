package io.github.yesidrobayo.shopledger.domain.model.safety

/**
 * Un punto de la lista corta sobre la cuenta de la nube que guarda las copias. La app no puede
 * saber si está al día: lo marca la persona.
 */
enum class CloudAccountCheck {
    /** Verificación en dos pasos activa. */
    TWO_STEP,

    /** Correo de recuperación al día. */
    RECOVERY_EMAIL,

    /** Teléfono de recuperación al día. */
    RECOVERY_PHONE,
}

/** El tipo de destino de la cuenta marcada: las marcas de uno no valen para el otro. */
enum class CloudAccountKind {
    /** Un servicio WebDAV. */
    WEBDAV,

    /** La nube del teléfono, con sus ranuras. */
    DOCUMENT_SLOTS,
}

/**
 * Lo que la persona marcó de la cuenta, y para qué tipo de destino. Es de la instalación, no del
 * negocio: no viaja en una copia.
 *
 * @property kind el destino que estaba conectado al marcar.
 * @property checked los puntos marcados.
 */
data class CloudAccountMarks(val kind: CloudAccountKind, val checked: Set<CloudAccountCheck>) {
    /**
     * Los puntos marcados para [current]: con otro tipo de destino, la cuenta es otra, y ninguno
     * cuenta como marcado.
     */
    fun checkedFor(current: CloudAccountKind): Set<CloudAccountCheck> =
        if (kind == current) checked else emptySet()
}
