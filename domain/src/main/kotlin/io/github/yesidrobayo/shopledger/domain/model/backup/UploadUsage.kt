package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Lo que la app subió a WebDAV en un mes del calendario del negocio, para mostrar `Subido este
 * mes`. Pertenece a la instalación, no a los datos: vive fuera de la base y no viaja en una copia.
 *
 * @property month el mes, como `AAAA-MM` en la zona del negocio.
 * @property bytes los bytes enviados en ese mes, también los de una subida cortada: esos datos ya se
 *   gastaron.
 */
data class UploadUsage(val month: String, val bytes: Long) {
    init {
        require(MONTH.matches(month)) { "El mes va como AAAA-MM" }
        require(bytes >= 0) { "Los bytes no pueden ser negativos" }
    }

    companion object {
        /** `AAAA-MM`, con cifras ASCII y un mes de 01 a 12. */
        val MONTH = Regex("[0-9]{4}-(0[1-9]|1[0-2])")
    }
}

/** Cómo terminó `Liberar copia`. Ninguno borra la copia: liberarla solo deja que la poda la mire. */
enum class PinRelease {
    /** La copia quedó sin la marca, comprobado al releer sus metadatos. */
    RELEASED,

    /** No se pudo: la copia sigue fijada. Se puede reintentar. */
    FAILED,
}
