package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * La red activa del teléfono, tal como la ve el estado del respaldo. Es una foto: puede cambiar un
 * instante después, y por eso nunca decide qué se sube, solo qué se muestra.
 *
 * @property validated hay una red activa que el sistema comprobó que llega a internet.
 * @property metered la red activa cobra por datos, o no hay ninguna.
 * @property dataSaverRestricted el ahorro de datos del sistema no deja a la app usar datos móviles
 *   en segundo plano.
 */
data class BackupNetwork(
    val validated: Boolean,
    val metered: Boolean,
    val dataSaverRestricted: Boolean,
) {
    companion object {
        /** Sin ninguna red. */
        val NONE = BackupNetwork(validated = false, metered = true, dataSaverRestricted = false)
    }
}
