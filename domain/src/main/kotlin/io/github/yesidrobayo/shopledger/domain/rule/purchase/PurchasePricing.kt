package io.github.yesidrobayo.shopledger.domain.rule.purchase

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseCostChange
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineLot
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRejection
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.sum
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.PurchaseLineTax
import io.github.yesidrobayo.shopledger.domain.rule.TaxCalculator
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.catalog.StockModeRules
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitConversion
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitConversionResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotCodeCheck
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotRules

/**
 * Lo que el cálculo de una compra necesita de un producto: el producto leído, con su saldo y su
 * costo, si su unidad base admite fracciones, y la tasa que paga con su nombre, ya resuelta.
 * [purchaseUnits] son las otras unidades en que se compra, por id: sus conversiones de compra.
 */
data class PurchaseSubject(
    val product: Product,
    val allowsFraction: Boolean,
    val taxRate: BasisPoints,
    val taxName: String?,
    val purchaseUnits: Map<Long, PurchaseUnit> = emptyMap(),
)

/**
 * Una unidad de compra de un producto que no es la base: [factorMilli] milésimas de la unidad base
 * por una unidad entera, y si admite fracciones.
 */
data class PurchaseUnit(val factorMilli: Long, val allowsFraction: Boolean)

/**
 * Una línea de compra calculada. [enteredQuantity] va en la unidad [unitId], con [unitFactor]
 * milésimas de la base por unidad entera, y [quantity] en la unidad base, la del libro. [amounts]
 * es lo que escribió la persona con el valor deducido, por unidad entera de [unitId]; [split] el
 * impuesto y lo que entra al costo; [movementCost] el costo por unidad base que congela la fila del
 * libro; y [before] y [after] el costo del producto antes y después de esta línea.
 */
data class PricedPurchaseLine(
    val subject: PurchaseSubject,
    val unitId: Long,
    val unitFactor: QuantityMilli,
    val enteredQuantity: QuantityMilli,
    val quantity: QuantityMilli,
    val amounts: PurchaseLineAmounts,
    val split: PurchaseLineTax,
    val movementCost: Money,
    val before: CostState,
    val after: CostState,
)

/** Las sumas de la cabecera de una compra: neto, impuesto, total pagado y lo que entra al costo. */
data class PurchaseTotals(val net: Money, val tax: Money, val total: Money, val cost: Money)

/** Resultado de [PurchasePricing.price]. */
sealed interface PurchasePricingResult {
    /**
     * Todas las líneas se pueden registrar. [costChanges] va una vez por producto, en el orden de su
     * primera línea, con el costo antes de la compra y después de todas sus líneas.
     */
    data class Priced(
        val lines: List<PricedPurchaseLine>,
        val totals: PurchaseTotals,
        val costChanges: List<PurchaseCostChange>,
    ) : PurchasePricingResult

    /** La línea número [index], desde cero, no se puede registrar, por [reason]. */
    data class LineRejected(val index: Int, val reason: PurchaseLineRejection) :
        PurchasePricingResult

    /** La suma de las líneas pasa del total máximo de un documento. */
    data object TotalTooLarge : PurchasePricingResult
}

/**
 * El cálculo completo de una compra antes de guardarla: el mismo para la pantalla, que muestra el
 * total y el costo que dejará, y para el caso de uso que la registra. Así lo que la persona ve es
 * lo que se guarda.
 *
 * Cada línea deduce su costo unitario o su total, separa el impuesto y entra al costo promedio con
 * lo que costó, sin el impuesto si se recupera. Un producto repetido calcula su segunda línea con el
 * costo que dejó la primera. Nada se escribe.
 */
object PurchasePricing {
    /**
     * La tasa que paga [product] en una compra: la suya, o la del negocio si no tiene. Sin impuesto
     * en el negocio, ninguna.
     */
    fun taxRateIdOf(product: Product, taxes: TaxSettings): Long? = when (taxes.mode) {
        TaxMode.NONE -> null
        TaxMode.INCLUDED, TaxMode.ADDED -> product.details.taxRateId ?: taxes.defaultTaxRateId
    }

    /**
     * Calcula [lines] en orden con los productos de [subjects], por id. Un producto que falta en
     * [subjects] no existe.
     */
    fun price(
        lines: List<PurchaseLineRequest>,
        subjects: Map<Long, PurchaseSubject>,
        taxes: TaxSettings,
    ): PurchasePricingResult {
        val costBefore = LinkedHashMap<Long, Money>()
        val costAfter = HashMap<Long, CostState>()
        val priced = ArrayList<PricedPurchaseLine>(lines.size)
        val newCodes = HashSet<Pair<Long, String>>()
        for ((index, line) in lines.withIndex()) {
            val subject = subjects[line.productId]
                ?: return rejected(index, PurchaseLineRejection.PRODUCT_NOT_FOUND)
            val product = subject.product
            lotRejection(line, product, newCodes)?.let { return rejected(index, it) }
            val before = costAfter[product.id]
                ?: CostState(product.onHand, product.averageCost, product.stockValue)
            val result = priceLine(line, subject, before, taxes)
            val ready = when (result) {
                is LineResult.Ready -> result.line
                is LineResult.Rejected -> return rejected(index, result.reason)
            }
            costBefore.putIfAbsent(product.id, product.averageCost)
            costAfter[product.id] = ready.after
            priced += ready
        }
        val totals = totalsOf(priced) ?: return PurchasePricingResult.TotalTooLarge
        val changes = costBefore.map { (productId, before) ->
            PurchaseCostChange(
                productId,
                before,
                costAfter.getValue(productId).averageCost,
            )
        }
        return PurchasePricingResult.Priced(priced, totals, changes)
    }

    private fun priceLine(
        line: PurchaseLineRequest,
        subject: PurchaseSubject,
        before: CostState,
        taxes: TaxSettings,
    ): LineResult {
        productRejection(subject.product)?.let { return LineResult.Rejected(it) }
        val baseUnitId = subject.product.details.baseUnitId
        val unitId = line.unitId ?: baseUnitId
        val unit = if (unitId == baseUnitId) {
            PurchaseUnit(UnitConversion.BASE_UNIT_FACTOR_MILLI, subject.allowsFraction)
        } else {
            subject.purchaseUnits[unitId]
                ?: return LineResult.Rejected(PurchaseLineRejection.UNIT_UNAVAILABLE)
        }
        quantityRejection(line.quantity, unit.allowsFraction)?.let {
            return LineResult.Rejected(it)
        }
        // Un factor imposible no viene de la app; si llega, la unidad no sirve para comprar.
        if (!UnitConversion.isValidFactor(unit.factorMilli)) {
            return LineResult.Rejected(PurchaseLineRejection.UNIT_UNAVAILABLE)
        }
        // La cantidad escrita ya se revisó: solo la convertida puede pasar del tope o ser cero.
        val converted = UnitConversion.toBaseQuantity(line.quantity, unit.factorMilli)
        val baseQuantity = (converted as? UnitConversionResult.Converted)?.quantity
            ?: return LineResult.Rejected(PurchaseLineRejection.INVALID_QUANTITY)
        val amounts = when (val result = amountsOf(line)) {
            is AmountsResult.Ready -> result.amounts
            is AmountsResult.Rejected -> return LineResult.Rejected(result.reason)
        }
        val split = TaxCalculator.purchaseLine(
            amounts.written,
            taxes.mode,
            subject.taxRate,
            taxes.purchaseTaxRecoverable,
        ).valueOrNull() ?: return LineResult.Rejected(PurchaseLineRejection.LINE_TOTAL_TOO_LARGE)
        // La fila del libro congela el costo de una unidad base con lo que entró al stock.
        val movementCost = PurchaseLineRules.costPerUnit(baseQuantity, split.costAmount)
            .valueOrNull()
            ?: return LineResult.Rejected(PurchaseLineRejection.UNIT_COST_TOO_LARGE)
        val after = entryAfter(before, baseQuantity, split.costAmount)
            ?: return LineResult.Rejected(PurchaseLineRejection.STOCK_ABOVE_LIMIT)
        return LineResult.Ready(
            PricedPurchaseLine(
                subject = subject,
                unitId = unitId,
                unitFactor = QuantityMilli(unit.factorMilli),
                enteredQuantity = line.quantity,
                quantity = baseQuantity,
                amounts = amounts,
                split = split,
                movementCost = movementCost,
                before = before,
                after = after,
            ),
        )
    }

    private fun productRejection(product: Product): PurchaseLineRejection? = when {
        product.isArchived -> PurchaseLineRejection.PRODUCT_ARCHIVED
        !StockModeRules.hasLedger(product.details.stockMode) -> PurchaseLineRejection.NOT_STOCKED
        else -> null
    }

    /**
     * Lo que se revisa del lote sin leer la base: solo un producto con lotes lo lleva, y un lote
     * nuevo tiene un código válido que la compra no crea dos veces. [newCodes] junta los códigos
     * nuevos de las líneas anteriores, por producto. Que el código no exista ya en el producto, y
     * que un lote elegido sea suyo, lo revisa el caso de uso.
     */
    private fun lotRejection(
        line: PurchaseLineRequest,
        product: Product,
        newCodes: MutableSet<Pair<Long, String>>,
    ): PurchaseLineRejection? {
        val lot = line.lot ?: return null
        if (!product.details.tracksLots) return PurchaseLineRejection.LOT_NOT_TRACKED
        return when (lot) {
            is PurchaseLineLot.Existing -> null

            is PurchaseLineLot.New -> when (val check = LotRules.checkCode(lot.code)) {
                is LotCodeCheck.Valid ->
                    if (newCodes.add(product.id to check.code)) {
                        null
                    } else {
                        PurchaseLineRejection.LOT_CODE_REPEATED
                    }

                LotCodeCheck.Blank, is LotCodeCheck.TooLong ->
                    PurchaseLineRejection.INVALID_LOT_CODE
            }
        }
    }

    private fun quantityRejection(
        quantity: QuantityMilli,
        allowsFraction: Boolean,
    ): PurchaseLineRejection? {
        if (!Limit.LINE_QUANTITY_MILLI.allows(quantity.milli)) {
            return PurchaseLineRejection.INVALID_QUANTITY
        }
        return when (UnitQuantityRule.check(quantity, allowsFraction)) {
            UnitQuantityCheck.Valid -> null
            UnitQuantityCheck.NotPositive -> PurchaseLineRejection.INVALID_QUANTITY
            UnitQuantityCheck.FractionNotAllowed -> PurchaseLineRejection.FRACTION_NOT_ALLOWED
        }
    }

    /** El costo unitario y el total de la línea, con la cantidad ya revisada. */
    private fun amountsOf(line: PurchaseLineRequest): AmountsResult {
        val written = when (val amount = line.amount) {
            is PurchaseLineAmount.UnitCost -> amount.cost
            is PurchaseLineAmount.LineTotal -> amount.total
        }
        if (written < Money.ZERO) {
            return AmountsResult.Rejected(PurchaseLineRejection.NEGATIVE_AMOUNT)
        }
        return when (val checked = PurchaseLineRules.check(line.quantity, line.amount)) {
            is PurchaseLineCheck.Ready -> AmountsResult.Ready(checked.amounts)

            PurchaseLineCheck.UnitCostTooLarge ->
                AmountsResult.Rejected(PurchaseLineRejection.UNIT_COST_TOO_LARGE)

            PurchaseLineCheck.LineTotalTooLarge ->
                AmountsResult.Rejected(PurchaseLineRejection.LINE_TOTAL_TOO_LARGE)
        }
    }

    /**
     * El estado del costo tras la entrada, o `null` si deja el stock, su valor o el costo promedio
     * por encima de sus topes: las cuentas que vienen después no los soportarían.
     */
    private fun entryAfter(before: CostState, quantity: QuantityMilli, total: Money): CostState? {
        val after = WeightedAverageCost.entry(before, quantity, total).valueOrNull() ?: return null
        return after.takeIf {
            Limit.ON_HAND_MILLI.allows(it.onHand.milli) &&
                Limit.STOCK_VALUE.allows(it.stockValue.minor) &&
                Limit.UNIT_MONEY.allows(it.averageCost.minor)
        }
    }

    /** Las sumas de la cabecera, o `null` si alguna pasa del total máximo de un documento. */
    private fun totalsOf(lines: List<PricedPurchaseLine>): PurchaseTotals? {
        // Cada línea cabe en el tope de un documento, y son a lo sumo 200: la suma cabe en Long.
        val total = lines.map { it.split.lineTotal }.sum()
        if (!Limit.DOCUMENT_TOTAL.allows(total.minor)) return null
        // El impuesto y el costo de cada línea nunca pasan de su total: sus sumas también caben.
        val tax = lines.map { it.split.taxAmount }.sum()
        val cost = lines.map { it.split.costAmount }.sum()
        return PurchaseTotals(total - tax, tax, total, cost)
    }

    private fun rejected(index: Int, reason: PurchaseLineRejection): PurchasePricingResult =
        PurchasePricingResult.LineRejected(index, reason)

    private sealed interface LineResult {
        class Ready(val line: PricedPurchaseLine) : LineResult

        class Rejected(val reason: PurchaseLineRejection) : LineResult
    }

    private sealed interface AmountsResult {
        class Ready(val amounts: PurchaseLineAmounts) : AmountsResult

        class Rejected(val reason: PurchaseLineRejection) : AmountsResult
    }
}
