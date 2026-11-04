package io.github.yesidrobayo.shopledger.domain.model

/**
 * Escalas enteras con las que se guardan cantidades, porcentajes y cotizaciones.
 *
 * Todo número con decimales se guarda como entero escalado: los decimales binarios no representan
 * valores como 0,1 y acumulan error en cada suma, y un reporte que no cuadra con sus líneas es un
 * dato falso.
 */
object Scale {
    /** Milésimas por unidad entera: 0,250 kg se guarda como 250. */
    const val MILLI: Long = 1_000L

    /** Puntos básicos en un 100 %: 12,5 % se guarda como 1250. */
    const val BASIS_POINTS: Long = 10_000L

    /**
     * Escala de una cotización: unidades enteras de la moneda del negocio por una unidad entera de
     * la otra moneda, multiplicadas por un millón.
     */
    const val MICRO: Long = 1_000_000L
}
