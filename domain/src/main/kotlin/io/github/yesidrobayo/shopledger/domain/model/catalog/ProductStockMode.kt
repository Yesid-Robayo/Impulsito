package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Cómo lleva el stock un producto. Es un valor guardado en la base: su nombre no cambia nunca.
 *
 * Solo un producto [TRACKED] tiene libro de movimientos, saldo propio y alerta de stock bajo. Un
 * servicio no tiene existencias que vigilar, y un producto hecho de componentes descuenta el stock
 * de cada componente al venderse, no el suyo, así que su saldo propio es siempre cero.
 */
enum class ProductStockMode {
    /** Lleva stock propio, con su libro de movimientos. */
    TRACKED,

    /** No lleva stock: un servicio o algo que no se cuenta. */
    UNTRACKED,

    /** Al venderse descuenta el stock de sus componentes, no el propio. */
    COMPONENTS,
}
