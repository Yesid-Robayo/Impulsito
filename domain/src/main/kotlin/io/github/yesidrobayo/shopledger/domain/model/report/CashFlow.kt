package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo que lee la base para el flujo de caja proyectado, solo de las cuentas vigentes en la moneda
 * del negocio y en su unidad menor.
 *
 * - [accountCount] es cuántas cuentas entran. Sin ninguna, no hay saldo del que partir.
 * - [balance] es la suma de sus saldos, y [notYetAvailable] lo que ya está en esos saldos pero se
 *   acredita después de hoy, como un pago con tarjeta que llega en dos días.
 * - [short] y [long] son lo que entra y sale en cada horizonte, contado desde hoy.
 */
data class CashFlowSums(
    val accountCount: Int,
    val balance: Money,
    val notYetAvailable: Money,
    val short: CashFlowWindow,
    val long: CashFlowWindow,
)

/**
 * Lo que entra y sale hasta el final de un horizonte, sin los gastos recurrentes, que se cuentan
 * aparte por sus fechas: lo que se acredita después de hoy, lo que vence por cobrar y lo que vence
 * por pagar. Los tres van en positivo.
 */
data class CashFlowWindow(
    val settlements: Money,
    val receivables: Money,
    val payables: Money,
)

/**
 * El flujo de caja proyectado: [availableToday], lo que se puede usar hoy, y cada horizonte con su
 * cifra. Es una estimación, y así se muestra.
 */
data class CashFlowProjection(
    val today: BusinessDate,
    val availableToday: Money,
    val horizons: List<CashFlowHorizon>,
)

/**
 * Un horizonte del flujo: [days] días desde hoy, hasta [until], con lo que suma y resta y la cifra
 * [projected] que queda.
 */
data class CashFlowHorizon(
    val days: Int,
    val until: BusinessDate,
    val settlements: Money,
    val receivables: Money,
    val payables: Money,
    val recurring: Money,
    val projected: Money,
)
