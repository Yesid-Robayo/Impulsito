package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit

/**
 * Hasta dónde llegó lo acumulado frente al límite que escribió la persona. Cada nivel es una
 * orientación: la app no conoce ningún régimen y no dice si algo se cumple o no.
 */
enum class LimitLevel {
    /** Por debajo del primer aviso. */
    BELOW,

    /** Llegó al 80 % del límite. */
    FIRST_NOTICE,

    /** Llegó al 95 % del límite, o justo al límite. */
    SECOND_NOTICE,

    /** Pasó del límite. Nunca bloquea una venta. */
    EXCEEDED,
}

/**
 * El límite de ingresos y cuánto se lleva.
 *
 * @property limit el monto y los meses que escribió la persona.
 * @property window los meses que suma, del primer día del más viejo al último del mes en curso.
 * @property accumulated lo vendido sin impuesto, menos lo devuelto, en [window], o `null` si una
 *   suma no cabe en `Long`.
 * @property used `accumulated × 10000 / límite` en puntos básicos, redondeado, o `null` sin lo
 *   acumulado. Con 8200000 frente a 10000000, 8200.
 * @property level el nivel de aviso al que llegó [used].
 * @property since el primer día con ventas si cae dentro de [window]: la suma usa lo que existe y
 *   la pantalla dice desde cuándo. `null` si la historia cubre toda la ventana.
 */
data class RevenueLimitView(
    val limit: RevenueLimit,
    val window: ReportPeriod.Range,
    val accumulated: Money?,
    val used: BasisPoints?,
    val level: LimitLevel,
    val since: BusinessDate?,
)

/**
 * El último aviso de límite que salió como notificación, para avisar cada nivel una sola vez por
 * ventana. Si cambia el límite, los meses o el mes en que empieza la ventana, es otra ventana.
 */
data class LimitNoticeMark(
    val windowStart: BusinessDate,
    val amount: Money,
    val months: Int,
    val level: LimitLevel,
)
