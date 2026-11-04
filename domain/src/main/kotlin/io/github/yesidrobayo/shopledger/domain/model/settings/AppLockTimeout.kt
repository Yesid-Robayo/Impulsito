package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * Cuánto puede estar la app en segundo plano antes de pedir de nuevo el desbloqueo. Se guarda en
 * segundos, y solo admite estos valores.
 */
enum class AppLockTimeout(val seconds: Int) {
    /** Pide desbloqueo cada vez que la app vuelve a primer plano. */
    IMMEDIATELY(0),

    /** Tras un minuto. */
    ONE_MINUTE(60),

    /** Tras cinco minutos. */
    FIVE_MINUTES(300),
    ;

    companion object {
        /**
         * El valor guardado en [seconds], o `null` si no es uno de los admitidos: viene de la base
         * o de una copia, y una copia no es confiable.
         */
        fun ofSeconds(seconds: Int): AppLockTimeout? = entries.firstOrNull { it.seconds == seconds }
    }
}
