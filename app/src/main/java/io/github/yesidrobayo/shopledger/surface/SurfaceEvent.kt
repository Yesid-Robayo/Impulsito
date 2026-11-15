package io.github.yesidrobayo.shopledger.surface

import io.github.yesidrobayo.shopledger.domain.port.LogEvent

/**
 * Lo que registran el widget, el mosaico y los atajos. Cada texto es fijo y no lleva datos: nunca un
 * monto, un conteo del negocio ni un nombre.
 */
enum class SurfaceEvent(override val text: String) : LogEvent {
    WIDGET_REFRESH_FAILED("No se pudo redibujar el widget"),
    WIDGET_READ_FAILED("El widget no pudo leer la base y quedó neutro"),
    TILE_OPEN_FAILED("El mosaico no pudo abrir la app"),
    TILE_REFRESH_FAILED("No se pudo pedir al panel que vuelva a escribir el mosaico"),
    SHORTCUTS_FAILED("No se pudieron publicar los atajos"),
}
