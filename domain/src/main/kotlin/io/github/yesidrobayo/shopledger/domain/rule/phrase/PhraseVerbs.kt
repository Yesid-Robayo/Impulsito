package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason

/**
 * Qué dice un verbo, o una frase que hace de verbo, sobre la operación.
 *
 * Casi todos fijan la operación ([intent]). Los de pago no alcanzan: "pagué" puede ser una compra,
 * un gasto o un pago a proveedor según lo que acompañe. Esos se suman a la operación de otro verbo
 * si es una de las que admiten en [joins] ("vendí 3 panes y me pagó con tarjeta" es una venta), y
 * si van solos se resuelven con [steps], en orden; si ninguno se cumple, quedan en [intent]. Un
 * verbo sin [intent], como "saqué", solo acompaña a otro: solo no dice qué pasó, y la frase se pide
 * de nuevo.
 *
 * Los [subordinate], como "el precio del", solo valen si no hay otro verbo: "nuevo producto vela,
 * precio 15000" crea un producto, no cambia un precio.
 */
internal enum class VerbKind(
    val intent: PhraseIntent?,
    val reason: StockAdjustmentReason? = null,
    val subordinate: Boolean = false,
    val joins: Set<PhraseIntent> = emptySet(),
    val steps: List<WeakStep> = emptyList(),
) {
    SALE(PhraseIntent.SALE),

    /**
     * "vendo", "lo vendo": en presente, con cantidad es lo que pasó ("vendo 3 panes"); sin
     * cantidad y con precio, el precio de venta ("el pan lo vendo a 600").
     */
    SELL_NOW(PhraseIntent.SALE, steps = listOf(WeakStep.PRICE_ONLY)),

    /**
     * "cobré": una venta si nombra productos ("le cobré a Carlos un corte"); a un cliente y sin
     * productos, lo que abonó ("cobré a doña Rosa 20 mil de lo que debía").
     */
    COLLECT(PhraseIntent.SALE, steps = listOf(WeakStep.CUSTOMER_WITHOUT_GOODS)),
    PURCHASE(PhraseIntent.PURCHASE),
    PRODUCTION(PhraseIntent.PRODUCTION),
    COUNT(PhraseIntent.STOCK_ADJUSTMENT, StockAdjustmentReason.COUNT_CORRECTION),
    DAMAGED(PhraseIntent.STOCK_ADJUSTMENT, StockAdjustmentReason.DAMAGED),
    EXPIRED(PhraseIntent.STOCK_ADJUSTMENT, StockAdjustmentReason.EXPIRED),
    LOST(PhraseIntent.STOCK_ADJUSTMENT, StockAdjustmentReason.LOST_OR_STOLEN),
    OWN_USE(PhraseIntent.STOCK_ADJUSTMENT, StockAdjustmentReason.OWN_USE),
    GIFT(PhraseIntent.STOCK_ADJUSTMENT, StockAdjustmentReason.GIFT_OR_SAMPLE),

    /** Presenta el producto de la receta: "para hacer", "receta del". */
    RECIPE_FOR(PhraseIntent.RECIPE),

    /** Presenta los insumos: "lleva", "uso", "le echo". */
    RECIPE_USES(PhraseIntent.RECIPE),

    /** Lo que rinde una receta: "con 1 kilo de cera me salen 8 velas". */
    RECIPE_YIELDS(PhraseIntent.RECIPE),

    NEW_PRODUCT(PhraseIntent.NEW_PRODUCT),
    COST(PhraseIntent.COST_CORRECTION, subordinate = true),
    PRICE(PhraseIntent.PRICE_CHANGE, subordinate = true),

    /**
     * Alguien me pagó: un abono de cliente, o el pago de una venta. Dicho de una categoría y sin
     * cliente ("the water bill got paid"), es un gasto.
     */
    PAY_IN(
        PhraseIntent.CUSTOMER_PAYMENT,
        joins = setOf(PhraseIntent.SALE),
        steps = listOf(WeakStep.SALE_PRODUCTS, WeakStep.LONE_CATEGORY),
    ),

    /**
     * "le di", "dei": con otro verbo de pago, lo que se le pagó ("le pagué a Don Ramiro, le di
     * 100"); con productos, un regalo; con una categoría, un gasto ("le di 20 mil al mensajero").
     * Solo con un monto no dice qué pasó.
     */
    GAVE(
        null,
        reason = StockAdjustmentReason.GIFT_OR_SAMPLE,
        joins = setOf(
            PhraseIntent.SUPPLIER_PAYMENT,
            PhraseIntent.CUSTOMER_PAYMENT,
            PhraseIntent.EXPENSE,
            PhraseIntent.STOCK_ADJUSTMENT,
        ),
        steps = listOf(WeakStep.GIFTED_PRODUCTS, WeakStep.CATEGORY),
    ),

    /** Yo pagué o gasté: una compra, un gasto o un pago a proveedor. */
    PAY_OUT(
        PhraseIntent.EXPENSE,
        joins = setOf(PhraseIntent.PURCHASE),
        steps = listOf(WeakStep.CATEGORY, WeakStep.PURCHASE_PRODUCTS, WeakStep.SUPPLIER),
    ),

    /** Un pago sin persona clara, como "pagó" o "abono": decide quién se nombra. */
    PAY_ANY(
        PhraseIntent.EXPENSE,
        joins = setOf(PhraseIntent.SALE, PhraseIntent.PURCHASE),
        steps = listOf(
            WeakStep.CATEGORY,
            WeakStep.PURCHASE_PRODUCTS,
            WeakStep.CUSTOMER,
            WeakStep.SUPPLIER,
        ),
    ),

    /** Recibí: una compra si nombra productos, un abono si nombra a un cliente. */
    RECEIVE(
        null,
        joins = setOf(PhraseIntent.PURCHASE),
        steps = listOf(WeakStep.PURCHASE_PRODUCTS, WeakStep.CUSTOMER),
    ),

    /** "se llevó", "took": una venta si nombra a un cliente; si no, acompaña a otro verbo. */
    TOOK(
        null,
        joins = setOf(PhraseIntent.SALE, PhraseIntent.STOCK_ADJUSTMENT),
        steps = listOf(WeakStep.BUYER),
    ),

    /**
     * "saqué", "usé": algo salió, pero el motivo lo dice otro verbo ("para la casa", "del horno").
     */
    TAKE(null, joins = setOf(PhraseIntent.STOCK_ADJUSTMENT, PhraseIntent.PRODUCTION)),

    /** Yo aboné a una deuda: un pago a proveedor, o una compra si nombra productos. */
    SETTLE_OUT(
        PhraseIntent.SUPPLIER_PAYMENT,
        joins = setOf(PhraseIntent.PURCHASE),
        steps = listOf(WeakStep.PURCHASE_PRODUCTS),
    ),
}

/** Un hecho de la frase que decide la operación de un verbo de pago. */
internal enum class WeakStep(val intent: PhraseIntent) {
    /** Nombra una categoría de gasto, como el arriendo o la luz. */
    CATEGORY(PhraseIntent.EXPENSE),

    /** Nombra un producto del catálogo que se compra. */
    PURCHASE_PRODUCTS(PhraseIntent.PURCHASE),

    /** Nombra un producto del catálogo. */
    SALE_PRODUCTS(PhraseIntent.SALE),

    /** Nombra a un cliente. */
    CUSTOMER(PhraseIntent.CUSTOMER_PAYMENT),

    /** Nombra a un proveedor. */
    SUPPLIER(PhraseIntent.SUPPLIER_PAYMENT),

    /** Nombra a un cliente que se llevó algo, o a alguien sin decir quién: "a customer took". */
    BUYER(PhraseIntent.SALE),

    /** Dice un precio y ninguna cantidad: "el pan lo vendo a 600". */
    PRICE_ONLY(PhraseIntent.PRICE_CHANGE),

    /** Nombra un producto del catálogo que se dio. */
    GIFTED_PRODUCTS(PhraseIntent.STOCK_ADJUSTMENT),

    /** Nombra una categoría de gasto y a ningún cliente. */
    LONE_CATEGORY(PhraseIntent.EXPENSE),

    /** Nombra a un cliente y ningún producto del catálogo. */
    CUSTOMER_WITHOUT_GOODS(PhraseIntent.CUSTOMER_PAYMENT),
}

/**
 * Qué dice un marcador de monto: el precio de una unidad ("a 500"), el total de la línea ("por
 * 1500") o lo que costó ("que me costaron 4 mil").
 */
internal enum class AmountKind {
    UNIT,
    LINE,

    /** Lo que costó la línea, dicho con una oración aparte: "que me costaron 4 mil". */
    COST,
}
