package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode

/**
 * La venta completa que recorre cada fila de la matriz en el dispositivo, por todos los módulos:
 * dos líneas, cada una con su descuento, un descuento de toda la venta, una tasa de impuesto, dos
 * pagos y el redondeo del efectivo. Después, una compra y un gasto del mismo día.
 *
 * Todos los montos van en unidad menor y son los mismos enteros en todas las filas: los decimales
 * de la moneda solo cambian cómo se escriben. Cada resultado está calculado a mano en los
 * comentarios, con el redondeo al entero más cercano de la regla de dinero. Los productos y los
 * montos son inventados.
 *
 * La comparten la prueba de los módulos sobre la base, en `:data`, la del comprobante dibujado, en
 * `:app`, y la de la venta en `check`, que confirma estas cifras con las reglas del dominio.
 */
object RegionalMatrixSale {
    /** La única tasa del negocio, la que usa por defecto: 19 %. */
    const val TAX_RATE_BP: Long = 1_900L

    /** El aceite: 10 unidades a un costo de 7.000, que se venden a 12.100. */
    const val OIL_PRICE: Long = 12_100L
    const val OIL_COST: Long = 7_000L
    const val OIL_STOCK_MILLI: Long = 10_000L

    /** Se venden 2 aceites con 10 % de descuento: 24.200 menos 2.420 dejan 21.780. */
    const val OIL_SOLD_MILLI: Long = 2_000L
    const val OIL_DISCOUNT_BP: Long = 1_000L
    const val OIL_BASE: Long = 21_780L

    /** El queso, por kilogramo: 5 kg a un costo de 1.200 el kilo, que se venden a 2.000. */
    const val CHEESE_PRICE: Long = 2_000L
    const val CHEESE_COST: Long = 1_200L
    const val CHEESE_STOCK_MILLI: Long = 5_000L

    /** Se venden 1,5 kg con 300 de descuento: 3.000 menos 300 dejan 2.700. */
    const val CHEESE_SOLD_MILLI: Long = 1_500L
    const val CHEESE_DISCOUNT: Long = 300L
    const val CHEESE_BASE: Long = 2_700L

    /** Lo que cobran las dos líneas tras su descuento: 21.780 + 2.700. */
    const val SUBTOTAL: Long = 24_480L

    /**
     * El descuento de toda la venta, 500, repartido por la base de cada línea: el queso lleva
     * 500 × 2.700 / 24.480 = 55,1, que redondea a 55, y el aceite, la línea mayor, el resto, 445.
     */
    const val ORDER_ADJUSTMENT: Long = -500L
    const val OIL_SHARE: Long = -445L
    const val CHEESE_SHARE: Long = -55L

    /**
     * El costo de lo vendido: 2 de 10 aceites que valen 70.000 son 14.000, y 1,5 de 5 kg que valen
     * 6.000 son 1.800.
     */
    const val SALE_COST: Long = 15_800L

    /** Lo que paga la tarjeta. El resto va en efectivo, que es lo único que se redondea. */
    const val CARD_PAYMENT: Long = 10_008L

    /** La compra del mismo día: 5 aceites a 7.000, con el impuesto fuera del costo. */
    const val PURCHASE_MILLI: Long = 5_000L
    const val PURCHASE_UNIT_COST: Long = 7_000L

    /** El gasto del mismo día, que sale de la caja. */
    const val EXPENSE: Long = 4_500L

    /** El producto que entra por un CSV: 12 unidades a un costo de 2.000, que se vende a 3.500. */
    const val IMPORTED_PRICE: Long = 3_500L
    const val IMPORTED_COST: Long = 2_000L
    const val IMPORTED_MILLI: Long = 12_000L

    /** Lo que deja la venta con el modo de impuesto de una fila. */
    fun saleFor(mode: TaxMode): MatrixSale = SALES.getValue(mode)

    /** Lo que deja la compra con el modo de impuesto de una fila. */
    fun purchaseFor(mode: TaxMode): MatrixPurchase = PURCHASES.getValue(mode)

    /**
     * El redondeo de la parte en efectivo, [cashPart], con el paso [step] de una fila. Solo están
     * las combinaciones que da la matriz, escritas a mano; otra es un error de la prueba.
     */
    fun cashRoundingOf(cashPart: Long, step: Long): Long =
        requireNotNull(CASH_ROUNDING[cashPart to step]) {
            "Falta el redondeo escrito a mano de $cashPart con paso $step"
        }

    private val SALES = mapOf(
        // Sin impuesto, todo es neto: 21.780 − 445 = 21.335 y 2.700 − 55 = 2.645, 23.980.
        TaxMode.NONE to
            MatrixSale(
                lines = listOf(
                    MatrixSaleLine(21_335L, 0L, 21_335L),
                    MatrixSaleLine(2_645L, 0L, 2_645L),
                ),
                net = 23_980L,
                tax = 0L,
                total = 23_980L,
                // 8.180 × 10.000 / 23.980 = 3.411,2.
                marginBp = 3_411L,
            ),
        // Con el impuesto dentro, el neto sale hacia atrás: 21.335 / 1,19 = 17.928,6, que
        // redondea a 17.929, y 2.645 / 1,19 = 2.222,7, que redondea a 2.223. El total no cambia.
        TaxMode.INCLUDED to
            MatrixSale(
                lines = listOf(
                    MatrixSaleLine(17_929L, 3_406L, 21_335L),
                    MatrixSaleLine(2_223L, 422L, 2_645L),
                ),
                net = 20_152L,
                tax = 3_828L,
                total = 23_980L,
                // 4.352 × 10.000 / 20.152 = 2.159,6.
                marginBp = 2_160L,
            ),
        // Con el impuesto sumado: 21.335 × 0,19 = 4.053,65, que redondea a 4.054, y 2.645 × 0,19 =
        // 502,55, que redondea a 503.
        TaxMode.ADDED to
            MatrixSale(
                lines = listOf(
                    MatrixSaleLine(21_335L, 4_054L, 25_389L),
                    MatrixSaleLine(2_645L, 503L, 3_148L),
                ),
                net = 23_980L,
                tax = 4_557L,
                total = 28_537L,
                // El impuesto sumado no es de quien vende: el margen es el de sin impuesto.
                marginBp = 3_411L,
            ),
    )

    private val PURCHASES = mapOf(
        // 5 × 7.000 = 35.000, sin impuesto.
        TaxMode.NONE to MatrixPurchase(net = 35_000L, tax = 0L, total = 35_000L, cost = 35_000L),
        // 35.000 / 1,19 = 29.411,8, que redondea a 29.412. El impuesto se recupera: el costo es
        // el neto.
        TaxMode.INCLUDED to
            MatrixPurchase(net = 29_412L, tax = 5_588L, total = 35_000L, cost = 29_412L),
        // 35.000 × 0,19 = 6.650 encima.
        TaxMode.ADDED to
            MatrixPurchase(net = 35_000L, tax = 6_650L, total = 41_650L, cost = 35_000L),
    )

    // La parte en efectivo es el total menos la tarjeta: 23.980 − 10.008 = 13.972 sin impuesto o
    // con él dentro, y 28.537 − 10.008 = 18.529 con él sumado.
    private val CASH_ROUNDING = mapOf(
        (13_972L to 0L) to 0L,
        // 13.972 / 5 = 2.794,4: baja a 13.970.
        (13_972L to 5L) to -2L,
        // 13.972 / 50 = 279,44: baja a 13.950.
        (13_972L to 50L) to -22L,
        // 13.972 / 1.000 = 13,972: sube a 14.000.
        (13_972L to 1_000L) to 28L,
        (18_529L to 0L) to 0L,
    )
}

/** Neto, impuesto y total de una línea de la venta de la matriz, en unidad menor. */
data class MatrixSaleLine(val net: Long, val tax: Long, val total: Long)

/**
 * Lo que deja la venta de la matriz con un modo de impuesto, en unidad menor, y su margen en
 * puntos básicos.
 */
data class MatrixSale(
    val lines: List<MatrixSaleLine>,
    val net: Long,
    val tax: Long,
    val total: Long,
    val marginBp: Long,
) {
    /** Lo que se escribe en efectivo: el total menos la tarjeta, antes del redondeo. */
    val cashPart: Long get() = total - RegionalMatrixSale.CARD_PAYMENT

    /** La ganancia en ventas: el neto menos el costo de lo vendido. */
    val grossProfit: Long get() = net - RegionalMatrixSale.SALE_COST
}

/** Neto, impuesto, total y costo de la compra de la matriz, en unidad menor. */
data class MatrixPurchase(val net: Long, val tax: Long, val total: Long, val cost: Long)
