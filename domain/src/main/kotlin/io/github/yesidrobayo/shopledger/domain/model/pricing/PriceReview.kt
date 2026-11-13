package io.github.yesidrobayo.shopledger.domain.model.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product

/** Por qué un producto pide revisar su precio. El orden es el que se muestra. */
enum class PriceReviewReason {
    /** Una compra le subió el costo y nadie revisó el precio todavía. */
    NEW_COST,

    /** El margen de su precio está por debajo del margen objetivo que le toca. */
    LOW_MARGIN,

    /** Su costo tiene más días que los que la persona fijó para un costo viejo. */
    OLD_COST,
}

/**
 * Un producto de `Revisar`, con todos sus motivos en [reasons], nunca vacía y en el orden de
 * [PriceReviewReason].
 *
 * [margin] es el margen de su precio de venta sin impuesto, el mismo de su detalle, y [target] el
 * objetivo que le toca: el suyo, el de su categoría o el del negocio. [costSince] es la fecha de
 * negocio del costo viejo, y solo tiene valor con [PriceReviewReason.OLD_COST].
 */
data class PriceReview(
    val product: Product,
    val reasons: List<PriceReviewReason>,
    val margin: Margin,
    val target: BasisPoints,
    val costSince: BusinessDate?,
)

/**
 * Con qué se mide si un costo es viejo: [today], la fecha de negocio de hoy en [zone], y [days],
 * los días tras los que un costo se considera viejo. Cero no mide nada.
 */
data class StaleCostCheck(val today: BusinessDate, val zone: BusinessTimeZone, val days: Int)
