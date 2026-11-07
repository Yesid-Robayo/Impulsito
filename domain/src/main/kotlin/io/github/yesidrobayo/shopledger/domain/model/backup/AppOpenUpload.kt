package io.github.yesidrobayo.shopledger.domain.model.backup

/** Qué hace el respaldo cuando la persona abre la app. */
enum class AppOpenUpload {
    /** Nada: no hay cambios sin copia, ya hay una subida corriendo, o la subida no toca. */
    NONE,

    /**
     * Encola la subida de inmediato, con la app a la vista. Con datos móviles, o con la nube del
     * teléfono, respeta el intervalo entre subidas automáticas.
     */
    RESPECTING_INTERVAL,

    /**
     * Encola la subida de inmediato y sin esperar el intervalo: el cambio más viejo sin copia ya
     * lleva un día, o el reloj retrocedió.
     */
    PAST_INTERVAL,
}
