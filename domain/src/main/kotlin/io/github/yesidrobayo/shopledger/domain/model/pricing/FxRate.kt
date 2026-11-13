package io.github.yesidrobayo.shopledger.domain.model.pricing

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import kotlin.time.Instant

/**
 * Una cotización que escribió la persona para su moneda de referencia [currency]. [rateMicro] son
 * unidades enteras de la moneda del negocio por una unidad entera de la otra, por 1.000.000: con
 * una cotización de 4150,50 vale 4150500000. Nunca se descarga ni se recalcula sola, y guardarla no
 * cambia ningún precio.
 */
data class NewFxRate(
    val currency: CurrencyCode,
    val rateMicro: Long,
    val note: String?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
)

/** Una cotización guardada. Es un libro: nunca se edita ni se borra, la nueva es otra fila. */
data class FxRate(val id: Long, val content: NewFxRate)
