package io.github.yesidrobayo.shopledger.domain.model

/**
 * Topes universales de las cifras que entran al negocio.
 *
 * Protegen de desbordar `Long` en las cuentas que vienen después, no de cifras propias de una
 * moneda: valen igual con cualquier moneda y cualquier cantidad de decimales, y una copia de otro
 * negocio nunca se rechaza por ellos si sus datos son válidos. Cada tope acota solo por arriba; el
 * mínimo de cada campo lo fija su propia regla.
 *
 * Los nombres de las constantes de la documentación van en el KDoc de cada valor, para encontrarlos
 * con una búsqueda.
 */
enum class Limit(val max: Long) {
    /** `MAX_UNIT_MONEY`: precio o costo por una unidad entera, en unidad menor. */
    UNIT_MONEY(1_000_000_000_000L),

    /**
     * `MAX_DOCUMENT_TOTAL`: una línea, el costo de una línea, un documento o un gasto, en unidad
     * menor. Multiplicado por mil todavía cabe en `Long`, y eso permite calcular el costo de una
     * entrada sin aritmética de 128 bits.
     */
    DOCUMENT_TOTAL(1_000_000_000_000_000L),

    /**
     * `MAX_STOCK_VALUE`: valor total del stock de un producto, en unidad menor. Multiplicado por
     * mil todavía cabe en `Long`, y eso sostiene el cálculo del costo promedio.
     */
    STOCK_VALUE(9_000_000_000_000_000L),

    /** `MAX_POINTS`: saldo de puntos de un cliente. */
    POINTS(1_000_000_000_000L),

    /** `MAX_LINE_QUANTITY_MILLI`: cantidad de una línea, en milésimas. Es un millón de unidades. */
    LINE_QUANTITY_MILLI(1_000_000_000L),

    /**
     * `MAX_ON_HAND_MILLI`: stock positivo de un producto, en milésimas. Son ocho millones de
     * unidades. El stock negativo no tiene tope propio, porque vender nunca se bloquea por stock.
     */
    ON_HAND_MILLI(8_000_000_000L),

    /** `MAX_DOCUMENT_LINES`: líneas de una venta o de una compra. */
    DOCUMENT_LINES(200L),
    ;

    /** Si [value] no supera el tope. */
    fun allows(value: Long): Boolean = value <= max
}
