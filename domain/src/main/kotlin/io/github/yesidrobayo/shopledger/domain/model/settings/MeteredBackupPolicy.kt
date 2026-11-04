package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * Cada cuánto se respalda automáticamente con datos móviles. Es un valor guardado en la base: su
 * nombre no cambia nunca.
 *
 * Con Wi-Fi la copia sale poco después de cada cambio; con datos móviles se espacia, para no gastar
 * el plan de la persona. Respaldar a mano siempre está disponible.
 */
enum class MeteredBackupPolicy {
    /** A lo sumo una copia automática cada 60 minutos. */
    EVERY_60_MIN,

    /** A lo sumo una copia automática cada 30 minutos. */
    EVERY_30_MIN,

    /** Ninguna copia automática con datos móviles: solo con Wi-Fi. */
    WIFI_ONLY,
}
