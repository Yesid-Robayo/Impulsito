package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.PriceSuggestion
import io.github.yesidrobayo.shopledger.domain.rule.ceilToStep
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv

/** Por qué un producto queda fuera de una actualización masiva. */
enum class BulkExclusion {
    /** Sin precio: subir un porcentaje de cero no da un precio. */
    NO_PRICE,

    /** El precio nuevo pasaría del tope de dinero por unidad. */
    ABOVE_LIMIT,

    /** El precio nuevo sería cero o menos: cero significa `Sin precio`, no una rebaja. */
    WOULD_BE_ZERO,

    /** Margen objetivo sin costo conocido: no hay de dónde calcular el precio. */
    NO_COST,

    /** Repreciar desde la cotización sin precio de referencia, o sin moneda de referencia. */
    NO_REFERENCE_PRICE,

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
 * De qué parte cada modo de la actualización masiva para un precio: el precio de ahora en esa
 * lista, el costo con que se mide el margen nuevo (nulo si no se conoce), la base del margen
 * objetivo con el impuesto que el precio lleva dentro, y el precio de referencia.
 */
data class BulkSubject(
    val price: Money,
    val cost: Money?,
    val suggestionBase: ExactUnitCost = ExactUnitCost.perUnit(Money.ZERO),
    val includedTaxRate: BasisPoints = BasisPoints.ZERO,
    val referencePrice: Money? = null,
)

/** Los decimales de la moneda del libro y de la de referencia, para repreciar desde la cotización. */
data class ReferenceScale(val currencyDecimals: Int, val referenceDecimals: Int)

/**
 * La actualización masiva: el precio nuevo de cada producto, con su redondeo, y lo que conviene
 * revisar antes de aplicarla.
 *
 * | Modo | `N` |
 * |---|---|
 * | Porcentaje | `roundDiv(precio × (10000 + porcentaje), 10000)` |
 * | Monto | `precio + monto` |
 * | Margen objetivo | `ceilDiv(base × 10000, 10000 − margen)`, con la base y el impuesto del sugerido |
 * | Cotización | `mulDivRound(referencia × 10^decimales, cotización, 10^decimales de referencia × 10^6)` |
 *
 * Después, `ceilToStep(N, paso)`. Con `3500` y +250, `3750`; con base `1500` y 30 %, `2143`; con
 * `1250` de referencia de dos decimales, cotización `4150,50` y libro sin decimales, `51881`.
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

    /**
     * Si [adjustment] se puede aplicar: un porcentaje o un monto distinto de cero y dentro de sus
     * topes, un margen objetivo de 0 a 99 % y una cotización positiva dentro del tope de su columna.
     */
    fun isValid(adjustment: BulkPriceAdjustment): Boolean = when (adjustment) {
        is BulkPriceAdjustment.Percent -> isValidPercent(adjustment.percent)

        is BulkPriceAdjustment.Amount ->
            adjustment.delta != Money.ZERO &&
                Limit.UNIT_MONEY.allows(adjustment.delta.minor) &&
                Limit.UNIT_MONEY.allows(-adjustment.delta.minor)

        is BulkPriceAdjustment.TargetMargin ->
            adjustment.margin >= BasisPoints.ZERO &&
                adjustment.margin <= BasisPoints.MAX_TARGET_MARGIN

        is BulkPriceAdjustment.ReferenceRate ->
            adjustment.fxRateMicro > 0L &&
                Limit.DOCUMENT_TOTAL.allows(adjustment.fxRateMicro)
    }

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
        return outcomeOf(BulkSubject(price, cost), BulkPriceAdjustment.Percent(percent), step, null)
    }

    /**
     * Lo que pasa con [subject] al aplicar [adjustment] con el paso [step], que con cero es sin
     * redondeo. [scale] son los decimales para repreciar desde la cotización; sin ellos, no hay
     * moneda de referencia y ese modo deja fuera cada producto.
     */
    fun outcomeOf(
        subject: BulkSubject,
        adjustment: BulkPriceAdjustment,
        step: Money,
        scale: ReferenceScale?,
    ): BulkPriceOutcome {
        require(isValid(adjustment)) { "Cálculo fuera de rango" }
        require(step >= Money.ZERO) { "Paso negativo" }
        val price = subject.price
        if (price <= Money.ZERO) return BulkPriceOutcome.Excluded(BulkExclusion.NO_PRICE)
        val target =
            when (adjustment) {
                // Un precio que ni siquiera cabe al multiplicarlo está, con más razón, sobre el tope.
                is BulkPriceAdjustment.Percent ->
                    mulDivRound(price.minor, BASIS + adjustment.percent.bp, BASIS).valueOrNull()
                        ?: return BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT)

                // Los dos caben en el tope por unidad: la suma no desborda.
                is BulkPriceAdjustment.Amount -> price.minor + adjustment.delta.minor

                is BulkPriceAdjustment.TargetMargin -> {
                    val suggestion = MarginCalculator.suggestedPrice(
                        subject.suggestionBase,
                        adjustment.margin,
                        subject.includedTaxRate,
                        Money.ZERO,
                    )
                    if (suggestion == PriceSuggestion.NoCost) {
                        return BulkPriceOutcome.Excluded(BulkExclusion.NO_COST)
                    }
                    val suggested = suggestion as? PriceSuggestion.Suggested
                        ?: return BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT)
                    suggested.price.minor
                }

                is BulkPriceAdjustment.ReferenceRate -> {
                    val reference = subject.referencePrice?.takeIf { it > Money.ZERO }
                    if (reference == null || scale == null) {
                        return BulkPriceOutcome.Excluded(BulkExclusion.NO_REFERENCE_PRICE)
                    }
                    fromReference(reference, adjustment.fxRateMicro, scale)
                        ?: return BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT)
                }
            }
        if (target <= 0L) return BulkPriceOutcome.Excluded(BulkExclusion.WOULD_BE_ZERO)
        // El redondeo no desborda: con un precio menor que el paso da el paso, y con uno mayor da
        // menos que el doble del precio.
        val rounded = if (step == Money.ZERO) target else ceilToStep(target, step.minor)
        return when {
            !Limit.UNIT_MONEY.allows(rounded) ->
                BulkPriceOutcome.Excluded(BulkExclusion.ABOVE_LIMIT)

            rounded == price.minor -> BulkPriceOutcome.Same

            else -> {
                val newPrice = Money(rounded)
                val cost = subject.cost
                val margin =
                    if (cost == null) Margin.NoData else MarginCalculator.margin(newPrice, cost)
                val percent = (adjustment as? BulkPriceAdjustment.Percent)?.percent
                BulkPriceOutcome.Changes(
                    oldPrice = price,
                    newPrice = newPrice,
                    margin = margin,
                    negativeMargin = margin is Margin.Defined && margin.value < BasisPoints.ZERO,
                    // Solo un porcentaje pide una variación que se pueda comparar.
                    distorted = percent != null && isDistorted(price, newPrice, percent),
                )
            }
        }
    }

    /**
     * El precio en la moneda del libro de [reference], escrito en la de referencia, con la
     * cotización [rateMicro]: unidades enteras del libro por una entera de la referencia, por un
     * millón. `null` si no cabe.
     */
    private fun fromReference(reference: Money, rateMicro: Long, scale: ReferenceScale): Long? {
        val scaled = Math.multiplyExact(reference.minor, powerOfTen(scale.currencyDecimals))
        val divisor = Math.multiplyExact(powerOfTen(scale.referenceDecimals), MICRO)
        return mulDivRound(scaled, rateMicro, divisor).valueOrNull()
    }

    private fun powerOfTen(exponent: Int): Long {
        var result = 1L
        repeat(exponent) { result = Math.multiplyExact(result, TEN) }
        return result
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
    private const val MICRO = 1_000_000L
    private const val TEN = 10L
}
