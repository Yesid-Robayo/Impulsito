package io.github.yesidrobayo.shopledger.domain.model.backup

import kotlin.time.Duration

/**
 * Los trabajos de subida que se encolan juntos cuando toca respaldar, y cuándo puede correr cada
 * uno. Con la app cerrada nada propio ve volver el Wi-Fi, y un trabajo con retraso no se adelanta
 * solo: por eso con WebDAV van dos, uno que despierta con la primera red sin datos móviles y otro
 * que corre al vencer el intervalo.
 *
 * @property unmetered encola la subida que solo corre con una red sin datos móviles, sin retraso.
 * @property anyNetworkDelay el retraso de la subida con cualquier red, o nulo si esa subida no se
 *   encola, como con `Solo con Wi-Fi` o con la nube del teléfono, que no tiene copias automáticas.
 * @property anyNetworkNeedsNetwork si esa subida espera a tener red. Con la nube del teléfono no: la
 *   red la decide la app de esa nube.
 */
data class UploadSchedule(
    val unmetered: Boolean,
    val anyNetworkDelay: Duration?,
    val anyNetworkNeedsNetwork: Boolean,
)

/** Lo que la compuerta de red decide al empezar una subida automática. */
enum class UploadGateVerdict {
    /** La subida sigue. */
    PROCEED,

    /**
     * El intervalo entre subidas automáticas sigue corriendo: la subida sale sin hacer nada, y la
     * espera se vuelve a programar para calcular de nuevo el retraso.
     */
    WAIT_FOR_INTERVAL,

    /** Con `Solo con Wi-Fi` y datos móviles: sale sin hacer nada y espera a una red sin cobro. */
    WAIT_FOR_UNMETERED,

    /**
     * Con la nube del teléfono, nada sale solo: sale sin escribir, y espera a que la persona toque
     * `Guardar en {provider}` con la app a la vista.
     */
    WAIT_FOR_PERSON,
}
