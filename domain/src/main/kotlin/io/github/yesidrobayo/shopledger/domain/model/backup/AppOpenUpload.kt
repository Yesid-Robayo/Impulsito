package io.github.yesidrobayo.shopledger.domain.model.backup

/** Qué hace el respaldo cuando la persona abre la app. */
enum class AppOpenUpload {
    /** Nada: no hay cambios sin copia, ya hay una subida corriendo, o la subida no toca. */
    NONE,

    /**
     * Encola la subida de inmediato, con la app a la vista. Con datos móviles respeta el intervalo
     * entre subidas automáticas. Con la nube del teléfono no encola nada: no tiene copias
     * automáticas, y la persona la pide con `Guardar en {provider}`.
     */
    RESPECTING_INTERVAL,

    /**
     * Encola la subida de inmediato y sin esperar el intervalo: el cambio más viejo sin copia ya
     * lleva un día, o el reloj retrocedió.
     */
    PAST_INTERVAL,
}
