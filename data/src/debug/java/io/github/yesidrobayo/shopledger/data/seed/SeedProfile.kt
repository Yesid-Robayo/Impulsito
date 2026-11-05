package io.github.yesidrobayo.shopledger.data.seed

/**
 * El tamaño del negocio inventado.
 *
 * - [SMALL] cabe en una prueba rápida: un mes, unas doscientas ventas.
 * - [TYPICAL] es un año de un negocio pequeño que usa la app todos los días.
 * - [VOLUME] es el volumen de referencia de los requisitos de rendimiento: cinco años, 5.000
 *   productos y 100.000 ventas, con los clientes, los pagos y los libros que ese volumen suma.
 *
 * Las cifras son las que se buscan: las ventas, las compras, los gastos y los cambios de precio
 * salen exactos; las líneas, los movimientos y los libros salen cerca, porque dependen del azar de
 * cada venta. Las cifras de `SMALL` y `TYPICAL` que el requisito no fija siguen la proporción de
 * `VOLUME`.
 *
 * @property months meses de historia, que terminan el mismo día fijo en todos los perfiles.
 * @property comboLineShareBp parte de las líneas de venta que son combos, en puntos básicos.
 * @property serviceLineShareBp parte de las líneas de venta que son servicios sin stock.
 * @property heavyProductShareBp parte de las líneas con stock que se lleva un solo producto, para
 *   tener uno con unos 50.000 movimientos. Cero: ninguno destaca así.
 */
enum class SeedProfile(
    val products: Int,
    val archivedProducts: Int,
    val categories: Int,
    val suppliers: Int,
    val customers: Int,
    val mediaAssets: Int,
    val sales: Int,
    val purchases: Int,
    val purchaseLines: Int,
    val expenses: Int,
    val priceChanges: Int,
    val adjustments: Int,
    val months: Int,
    val comboLineShareBp: Int,
    val serviceLineShareBp: Int,
    val heavyProductShareBp: Int,
) {
    SMALL(
        products = 20,
        archivedProducts = 2,
        categories = 4,
        suppliers = 3,
        customers = 10,
        mediaAssets = 4,
        sales = 200,
        purchases = 20,
        purchaseLines = 60,
        expenses = 30,
        priceChanges = 10,
        adjustments = 20,
        months = 1,
        comboLineShareBp = 400,
        serviceLineShareBp = 200,
        heavyProductShareBp = 0,
    ),
    TYPICAL(
        products = 300,
        archivedProducts = 30,
        categories = 12,
        suppliers = 20,
        customers = 400,
        mediaAssets = 60,
        sales = 10_000,
        purchases = 1_000,
        purchaseLines = 3_000,
        expenses = 1_500,
        priceChanges = 600,
        adjustments = 500,
        months = 12,
        comboLineShareBp = 400,
        serviceLineShareBp = 200,
        heavyProductShareBp = 0,
    ),
    VOLUME(
        products = 5_000,
        archivedProducts = 500,
        categories = 40,
        suppliers = 300,
        customers = 2_000,
        mediaAssets = 1_000,
        sales = 100_000,
        purchases = 10_000,
        purchaseLines = 50_000,
        expenses = 20_000,
        priceChanges = 45_000,
        adjustments = 20_000,
        months = 60,
        comboLineShareBp = 400,
        serviceLineShareBp = 200,
        heavyProductShareBp = 2_350,
    ),
    ;

    /** Combos del catálogo: uno de cada cien productos, y al menos dos. */
    val combos: Int get() = maxOf(MIN_SPECIAL, products / COMBO_EVERY)

    /** Servicios sin stock del catálogo: uno de cada cincuenta productos, y al menos dos. */
    val services: Int get() = maxOf(MIN_SPECIAL, products / SERVICE_EVERY)

    private companion object {
        const val MIN_SPECIAL = 2
        const val COMBO_EVERY = 100
        const val SERVICE_EVERY = 50
    }
}
