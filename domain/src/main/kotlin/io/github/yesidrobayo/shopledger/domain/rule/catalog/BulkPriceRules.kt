package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.ceilToStep
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv

/** Por qué un producto queda fuera de una actualización masiva. */
enum class BulkExclusion {
    /** Sin precio: subir un porcentaje de cero no da un precio. */
    NO_PRICE,

    /** El precio nuevo pasaría del tope de dinero por unidad. */
    ABOVE_LIMIT,

    /** El precio nuevo sería cero, que significa `Sin precio`, no una rebaja. */
    WOULD_BE_ZERO,

    /** Archivado: no se vende, y su precio no se toca. */
    ARCHIVED,
}

/** Lo que una actualización masiva hace con un producto. */
sealed interface BulkPriceOutcome {
    /** Cambia de [oldPrice] a [newPrice]. */
    data class Changes(
        val oldPrice: Money,
        val newPrice: Money,
        val margin: Margin,
        val negativeMargin: Boolean,
        val distorted: Boolean,
    ) : BulkPriceOutcome

    /** El precio nuevo es igual al de ahora: no se escribe nada. */
    data object Same : BulkPriceOutcome

    data class Excluded(val reason: BulkExclusion) : BulkPriceOutcome
}

/**
 * La actualización masiva por porcentaje: el precio nuevo de cada producto, con su redondeo, y
 * lo que conviene revisar antes de aplicarla.
 *
 * ```text
 * N = roundDiv(precio × (10000 + porcentaje), 10000)
 * nuevo = N sin paso, o ceilToStep(N, paso)
 * ```
 *
 * El redondeo va hacia arriba, para no quedar debajo de lo pedido; por eso un paso grande sobre un
 * precio chico puede alejar mucho la variación real de la pedida, y la vista previa la marca.
 */
object BulkPriceRules {
    /** Más de esta diferencia entre la variación real y la pedida se marca: 5 puntos. */
    val DISTORTION_TOLERANCE: BasisPoints = BasisPoints(500L)

    /** Si [percent] es un porcentaje válido: dentro de los topes y distinto de cero. */
    fun isValidPercent(percent: BasisPoints): Boolean = percent != BasisPoints.ZERO &&
        percent >= BasisPoints.MIN_BULK_PERCENT &&
        percent <= BasisPoints.MAX_BULK_PERCENT

    /**
     * Lo que pasa con un producto de precio [price] y costo de catálogo [cost] al aplicar [percent]
     * con el paso [step], que con cero es sin redondeo. [cost] nulo es un producto sin costo propio,
     * como uno por componentes: su margen no se calcula.
     */
    fun outcome(price: Money, cost: Money?, percent: BasisPoints, step: Money): BulkPriceOutcome {
        require(isValidPercent(percent)) { "Porcentaje fuera de rango" }
        require(step >= Money.ZERO) { "Paso negativo" }
        if (price <= Money.ZERO) return BulkPriceOutcome.Excluded(BulkExclusion.NO_PRICE)
        // Un precio que ni siquiera cabe al multiplicarlo está, con más razón, sobre el tope.
        val scaled = mulDivRound(price.minor, BASIS + percent.bp, BASIS).valueOrNull()
            ?: return BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT)
        // El redondeo no desborda: con un precio menor que el paso da el paso, y con uno mayor da
        // menos que el doble del precio.
        val rounded = if (step == Money.ZERO) scaled else ceilToStep(scaled, step.minor)
        return when {
            !Limit.UNIT_MONEY.allows(rounded) ->
                BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT)

            rounded == 0L -> BulkPriceOutcome.Excluded(BulkExclusion.WOULD_BE_ZERO)

            rounded == price.minor -> BulkPriceOutcome.Same

            else -> {
                val newPrice = Money(rounded)
                val margin =
                    if (cost == null) Margin.NoData else MarginCalculator.margin(newPrice, cost)
                BulkPriceOutcome.Changes(
                    oldPrice = price,
                    newPrice = newPrice,
                    margin = margin,
                    negativeMargin = margin is Margin.Defined && margin.value < BasisPoints.ZERO,
                    distorted = isDistorted(price, newPrice, percent),
                )
            }
        }
    }

    /**
     * Si la variación real de [old] a [new] se aparta de [percent] en más de
     * [DISTORTION_TOLERANCE], en puntos básicos y en enteros.
     */
    private fun isDistorted(old: Money, new: Money, percent: BasisPoints): Boolean {
        // El precio nuevo ya pasó el tope de dinero por unidad: por diez mil cabe en `Long`.
        val actual = roundDiv(Math.multiplyExact(new.minor, BASIS), old.minor)
        // El redondeo solo sube: la variación real nunca queda por debajo de la pedida.
        return actual - BASIS - percent.bp > DISTORTION_TOLERANCE.bp
    }

    private const val BASIS = 10_000L
}
