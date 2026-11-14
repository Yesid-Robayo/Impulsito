package io.github.yesidrobayo.shopledger.ui.stock

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductPricing
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObservePricingContextUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.AdjustStockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult
import io.github.yesidrobayo.shopledger.format.TEST_CURRENCY
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recorre el inventario con veinte productos inventados, en la base de la app y con los casos de
 * uso que usan sus pantallas: crear con stock inicial, tres compras a distinto costo, ventas, un
 * conteo que baja y otro que sube, una compra equivocada que se anula con parte ya vendida, y dos
 * cambios de precio.
 *
 * Todo valor esperado está escrito a mano en las tablas de abajo: salen de los ejemplos de las
 * reglas de negocio y de un cálculo aparte con sus mismas fórmulas, nunca del código que se
 * prueba. Comprueba, por producto:
 * - la cantidad, el valor del stock y el costo promedio, con el promedio ponderado móvil y sus
 *   casos borde: stock en cero, negativo, sin costo, compra gratis, y la anulación que reproduce el
 *   libro;
 * - el margen sobre el precio, el de la lista y el del detalle, y el de cada venta: ganancia sobre
 *   lo vendido sin impuesto;
 * - la alerta de stock bajo: se crea una sola vez al cruzar el mínimo, sigue siendo la misma
 *   mientras el stock no suba por encima, y al subir se rearma y vuelve a avisar en la siguiente
 *   caída;
 * - que una venta pasada conserva su precio y su costo tras cambiar el precio o anular la compra
 *   que lo fijó.
 *
 * Escribe productos, compras y ventas de verdad en la base de la app, así que solo corre si se pide
 * con el argumento `inventoryTwentyProducts=true` del runner, y con la app recién limpiada: un
 * nombre repetido hace fallar la prueba. No abre la actividad, para que ningún aviso se entregue y
 * cada alerta siga pendiente donde se cuenta. Registra solo conteos y tiempos.
 */
@RunWith(AndroidJUnit4::class)
class InventoryTwentyProductsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ShopLedgerApp
    private lateinit var ports: DataPorts
    private lateinit var ids: List<Long>
    private var checks = 0

    @Test
    fun twentyProductsKeepCostMarginAndAlerts() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true")
        val started = TimeSource.Monotonic.markNow()
        ports = (app.container.data.open() as DataAccess.Ready).ports
        confirmCurrency()
        // Los valores esperados son sin impuesto: con otro modo, el neto de la venta cambia.
        assertEquals(
            "La prueba espera precios sin impuesto",
            TaxMode.NONE,
            ports.taxSettings.get().mode,
        )

        val useCases = UseCases.of(ports, app)
        ids = SEED.map { create(useCases, it) }

        // Al crearse, los que nacen sin stock ya están bajos: alerta activa, pero callada.
        for (n in listOf(1, 7, 11, 20)) {
            assertAlert(n, active = LowStockNotificationStatus.SUPPRESSED, pending = 0)
        }

        // Compra 1: primeras compras, una por el total, dos gratis.
        buy(
            useCases,
            line(1, 10, 12_000),
            line(5, 5, 5_000),
            line(6, 2, 0),
            line(7, 3, 0),
            line(13, 4, 4_000),
            line(20, 6, 6_000),
        )
        assertCost(1, onHand = 10_000, value = 12_000, average = 1200)
        // Stock positivo sin costo: el valor cero cuenta como costo desconocido.
        assertCost(5, onHand = 10_000, value = 10_000, average = 1000)
        // Compra gratis: baja el promedio.
        assertCost(6, onHand = 12_000, value = 10_000, average = 833)
        // Compra gratis con el stock en cero.
        assertCost(7, onHand = 3_000, value = 0, average = 0)
        for (n in listOf(1, 7, 20)) assertNoAlert(n)

        // Compra 2: la segunda del primero, y una por un total sin unitario exacto.
        buy(useCases, line(1, 5, 7_500), line(8, 3, 25_000), line(20, 4, 5_200))
        assertCost(1, onHand = 15_000, value = 19_500, average = 1300)
        assertCost(8, onHand = 5_000, value = 41_000, average = 8200)
        assertCost(20, onHand = 10_000, value = 11_200, average = 1120)

        // Venta 1: diez líneas; tres dejan el stock negativo y una lo deja en cero.
        val firstSale = sell(
            useCases,
            1 to 8, 2 to 5, 3 to 5, 4 to 6, 14 to 14, 15 to 23, 16 to 2, 17 to 1, 18 to 1, 20 to 3,
        )
        assertSale(firstSale, netTotal = 86_050, totalCost = 62_660, marginBp = 2718, FIRST_SALE)
        assertCost(1, onHand = 7_000, value = 9_100, average = 1300)
        assertCost(2, onHand = 0, value = 0, average = 1300)
        assertCost(3, onHand = -2_000, value = 0, average = 1300)
        assertCost(4, onHand = -5_000, value = 0, average = 1000)
        assertAlert(2, active = LowStockNotificationStatus.PENDING, pending = 1)
        assertAlert(3, active = LowStockNotificationStatus.PENDING, pending = 1)
        assertAlert(4, active = LowStockNotificationStatus.PENDING, pending = 1)
        // Cruza de largo, de 20 a −3 con mínimo 5: una sola alerta.
        assertAlert(15, active = LowStockNotificationStatus.PENDING, pending = 1)
        assertNoAlert(14)

        // Compra 3: sobre stock en cero, negativo, que no cubre el negativo, y una más del primero.
        buy(
            useCases,
            line(1, 4, 5_800),
            line(2, 5, 8_000),
            line(3, 10, 15_000),
            line(4, 2, 3_000),
            line(20, 3, 4_001),
        )
        assertCost(1, onHand = 11_000, value = 14_900, average = 1355)
        assertCost(2, onHand = 5_000, value = 8_000, average = 1600)
        assertCost(3, onHand = 8_000, value = 12_000, average = 1500)
        assertCost(4, onHand = -3_000, value = 0, average = 1500)
        // Subir por encima del mínimo rearma; seguir bajo no.
        assertAlert(2, active = null, pending = 1)
        assertAlert(3, active = null, pending = 1)
        assertAlert(4, active = LowStockNotificationStatus.PENDING, pending = 1)

        // Conteos: de 12 a 10, justo al mínimo, y de 7 a 8.
        adjust(useCases, StockAdjustmentRequest.Count(ids[11], QuantityMilli(10_000)))
        adjust(useCases, StockAdjustmentRequest.Count(ids[12], QuantityMilli(8_000)))
        assertCost(12, onHand = 10_000, value = 13_550, average = 1355)
        assertCost(13, onHand = 8_000, value = 8_003, average = 1000)
        assertLastMovement(12, StockMovementType.ADJUSTMENT, quantity = -2_000, totalCost = -2_710)
        assertLastMovement(13, StockMovementType.ADJUSTMENT, quantity = 1_000, totalCost = 1_000)
        assertAlert(12, active = LowStockNotificationStatus.PENDING, pending = 1)

        // Compra 4, equivocada: una unidad a 50000 en vez de 5000, y diez de otro producto.
        val wrongPurchase =
            buy(useCases, line(9, 1, 50_000), line(10, 1, 50_000), line(11, 10, 10_000))
        assertCost(9, onHand = 11_000, value = 60_000, average = 5455)
        assertCost(10, onHand = 11_000, value = 60_000, average = 5455)
        assertCost(11, onHand = 10_000, value = 10_000, average = 1000)
        assertAlert(11, active = null, pending = 0)

        // Venta 2, con el costo inflado, antes de notar el error.
        val secondSale = sell(useCases, 10 to 9, 11 to 10)
        assertSale(secondSale, netTotal = 67_000, totalCost = 59_091, marginBp = 1180, SECOND_SALE)
        assertCost(10, onHand = 2_000, value = 10_909, average = 5455)
        assertAlert(11, active = LowStockNotificationStatus.PENDING, pending = 1)

        // Anular la compra 4 reproduce el libro sin ella.
        assertEquals(
            VoidPurchaseResult.Voided,
            useCases.voidPurchase(wrongPurchase, VoidReason.ENTRY_ERROR, null),
        )
        assertCost(9, onHand = 10_000, value = 10_000, average = 1000)
        assertCost(10, onHand = 1_000, value = 0, average = 1000)
        // Sin movimientos que fijen el costo, vuelve al costo inicial.
        assertCost(11, onHand = -10_000, value = 0, average = 900)
        // La anulación también se evalúa: de 2 a 1 con mínimo 1 cruza.
        assertAlert(10, active = LowStockNotificationStatus.PENDING, pending = 1)
        assertAlert(11, active = LowStockNotificationStatus.PENDING, pending = 1)
        val voided = ports.stockLedger.findByPurchase(wrongPurchase)
        assertEquals(3, voided.count { it.movement.type == StockMovementType.PURCHASE })
        assertEquals(3, voided.count { it.movement.type == StockMovementType.PURCHASE_VOID })
        // La venta hecha con el costo inflado lo conserva.
        assertSale(secondSale, netTotal = 67_000, totalCost = 59_091, marginBp = 1180, SECOND_SALE)

        // Cambios de precio: uno sube y otro baja por debajo del costo.
        val raised = useCases.changePrice(ids[15], Money(3_500))
        assertTrue("No subió: $raised", raised is ChangePriceResult.Changed)
        val lowered = useCases.changePrice(ids[16], Money(1_000))
        assertTrue("No bajó: $lowered", lowered is ChangePriceResult.Changed)
        val thirdSale = sell(useCases, 16 to 1)
        assertSale(thirdSale, netTotal = 3_500, totalCost = 2_000, marginBp = 4286, THIRD_SALE)
        // La venta 1 sigue con los precios de antes.
        assertSale(firstSale, netTotal = 86_050, totalCost = 62_660, marginBp = 2718, FIRST_SALE)

        // La alerta del producto 14, paso a paso: 6 unidades, mínimo 5.
        val first = run {
            sell(useCases, 14 to 1)
            assertCost(14, onHand = 5_000, value = 3_500, average = 700)
            assertAlert(14, active = LowStockNotificationStatus.PENDING, pending = 1)
        }
        sell(useCases, 14 to 2)
        assertCost(14, onHand = 3_000, value = 2_100, average = 700)
        val stillLow = assertAlert(14, LowStockNotificationStatus.PENDING, pending = 1)
        assertEquals("Seguir bajando no crea otra alerta", first, stillLow)
        buy(useCases, line(14, 2, 1_500))
        assertCost(14, onHand = 5_000, value = 3_600, average = 720)
        val atMinimum = assertAlert(14, LowStockNotificationStatus.PENDING, pending = 1)
        assertEquals("Llegar justo al mínimo no rearma", first, atMinimum)
        buy(useCases, line(14, 1, 780))
        assertCost(14, onHand = 6_000, value = 4_380, average = 730)
        assertAlert(14, active = null, pending = 1)
        sell(useCases, 14 to 1)
        val second = assertAlert(14, active = LowStockNotificationStatus.PENDING, pending = 2)
        assertNotEquals("La nueva caída crea otra alerta", first, second)

        assertFinalState()
        Log.i(
            TAG,
            "${SEED.size} productos, $checks comprobaciones, " +
                "${started.elapsedNow().inWholeMilliseconds} ms",
        )
    }

    /** El estado final de los veinte, contra la tabla escrita a mano. */
    private suspend fun assertFinalState() {
        val context = ObservePricingContextUseCase(
            ports.settings,
            ports.categories,
            ports.taxRates,
        )()
            .first()
        for (expected in FINAL) {
            val n = expected.n
            assertCost(n, expected.onHand, expected.value, expected.average)
            val product = product(n)
            // El margen de la lista: el costo de catálogo redondeado a la unidad menor.
            val listCost = MarginCalculator
                .catalogUnitCost(product.onHand, product.stockValue, product.averageCost)
                .valueOrNull()
            val listMargin = MarginCalculator.margin(product.salePrice, requireNotNull(listCost))
            assertEquals(
                "Margen de lista del producto $n",
                margin(expected.listMarginBp),
                listMargin,
            )
            // El margen del detalle: el costo sin redondear.
            val detailMargin = ProductPricing.of(product, context).marginOf(product.salePrice)
            assertEquals(
                "Margen del detalle del producto $n",
                margin(expected.detailMarginBp),
                detailMargin,
            )
            // El saldo es la suma del libro, que solo creció.
            val ledger = ports.stockLedger.findByProduct(ids[n - 1])
            assertEquals(
                "Saldo y libro del producto $n",
                product.onHand.milli,
                ledger.sumOf { it.movement.quantity.milli },
            )
            assertAlert(n, expected.activeAlert, expected.pendingAlerts)
            checks += 3
        }
    }

    private suspend fun confirmCurrency() {
        val settings = ports.settings
        val regional = settings.get().regional
        if (regional.currency.code.isConfirmed) return
        val confirmed =
            SaveRegionalSettingsUseCase(ports.transactions, settings, app.container.clock)(
                RegionalSettingsInput(
                    currencyCode = TEST_CURRENCY,
                    currencyDecimals = TEST_DECIMALS,
                    currencySymbol = null,
                    cashRounding = Money.ZERO,
                    formatLocaleTag = regional.formatLocaleTag,
                    businessTimeZoneId = regional.businessTimeZoneId,
                    weekStartDay = regional.weekStartDay,
                    csvSeparator = regional.csvSeparator,
                ),
            )
        assertEquals(SaveRegionalSettingsResult.Saved, confirmed)
    }

    private suspend fun create(useCases: UseCases, seed: Seed): Long {
        val form = ProductForm(
            name = seed.name,
            salePrice = Money(seed.price),
            cost = Money(seed.cost),
            minStock = QuantityMilli(seed.minUnits * ONE_UNIT),
        )
        val result = useCases.create(form, QuantityMilli(seed.openingUnits * ONE_UNIT))
        check(result is SaveProductResult.Saved) {
            "No se pudo crear un producto de prueba: limpia la app antes de correr ($result)"
        }
        return result.productId
    }

    private fun line(n: Int, units: Long, total: Long) = PurchaseLineRequest(
        productId = ids[n - 1],
        quantity = QuantityMilli(units * ONE_UNIT),
        amount = PurchaseLineAmount.LineTotal(Money(total)),
    )

    private suspend fun buy(useCases: UseCases, vararg lines: PurchaseLineRequest): Long {
        val result = useCases.purchase(PurchaseRequest(lines = lines.toList()))
        check(result is RegisterPurchaseResult.Registered) { "La compra no se registró: $result" }
        return result.purchaseId
    }

    private suspend fun sell(useCases: UseCases, vararg lines: Pair<Int, Long>): Long {
        val request = SaleRequest(
            lines = lines.map { (n, units) ->
                SaleLineRequest(ids[n - 1], QuantityMilli(units * ONE_UNIT))
            },
            paymentMethodId = useCases.paymentMethodId,
        )
        val result = useCases.sale(request)
        check(result is RegisterSaleResult.Registered) { "La venta no se registró: $result" }
        return result.saleId
    }

    private suspend fun adjust(useCases: UseCases, request: StockAdjustmentRequest) {
        val result = useCases.adjust(request)
        check(result is StockAdjustmentResult.Adjusted) { "El ajuste no se registró: $result" }
    }

    private suspend fun product(n: Int): Product =
        requireNotNull(ports.products.findById(ids[n - 1])) { "Falta el producto $n" }

    private suspend fun assertCost(n: Int, onHand: Long, value: Long, average: Long) {
        val product = product(n)
        assertEquals("Stock del producto $n", onHand, product.onHand.milli)
        assertEquals("Valor del stock del producto $n", value, product.stockValue.minor)
        assertEquals("Costo promedio del producto $n", average, product.averageCost.minor)
        checks += 3
    }

    private suspend fun assertLastMovement(
        n: Int,
        type: StockMovementType,
        quantity: Long,
        totalCost: Long,
    ) {
        val last = ports.stockLedger.findByProduct(ids[n - 1]).maxBy { it.movement.id }
        assertEquals("Tipo del último movimiento del producto $n", type, last.movement.type)
        assertEquals("Cantidad del ajuste del producto $n", quantity, last.movement.quantity.milli)
        assertEquals("Costo del ajuste del producto $n", totalCost, last.totalCost.minor)
        checks += 3
    }

    /**
     * La alerta activa del producto [n], con su estado de aviso, o ninguna si [active] es `null`, y
     * cuántas alertas suyas esperan aviso, resueltas o no. Devuelve el id de la activa.
     */
    private suspend fun assertAlert(
        n: Int,
        active: LowStockNotificationStatus?,
        pending: Int,
    ): Long? {
        val id = ids[n - 1]
        val current = ports.lowStockAlerts.findActiveByProducts(listOf(id))
        assertTrue("Más de una alerta activa del producto $n", current.size <= 1)
        val alert = current.singleOrNull()
        assertEquals("Alerta activa del producto $n", active, alert?.notificationStatus)
        val waiting = ports.lowStockAlerts.findPendingNotifications().count { it.productId == id }
        assertEquals("Alertas por avisar del producto $n", pending, waiting)
        checks += 2
        return alert?.id
    }

    private suspend fun assertNoAlert(n: Int) {
        assertNull(
            "El producto $n no debería tener alerta activa",
            ports.lowStockAlerts.findActiveByProducts(listOf(ids[n - 1])).singleOrNull(),
        )
        checks++
    }

    /** La venta [saleId] y sus líneas, contra lo escrito a mano en [lines]. */
    private suspend fun assertSale(
        saleId: Long,
        netTotal: Long,
        totalCost: Long,
        marginBp: Long,
        lines: List<ExpectedLine>,
    ) {
        val sale = requireNotNull(ports.sales.findById(saleId)) { "Falta la venta" }
        assertEquals("Neto de la venta", netTotal, sale.content.netTotal.minor)
        assertEquals("Costo de la venta", totalCost, sale.content.totalCost.minor)
        assertEquals(
            "Margen de la venta",
            margin(marginBp),
            MarginCalculator.margin(sale.content.netTotal, sale.content.totalCost),
        )
        val stored = ports.sales.findLines(saleId).associateBy { it.content.productId }
        assertEquals("Líneas de la venta", lines.size, stored.size)
        for (expected in lines) {
            val line: SaleLine = requireNotNull(stored[ids[expected.n - 1]]) {
                "Falta la línea del producto ${expected.n}"
            }
            val content = line.content
            assertEquals(
                "Precio de la línea ${expected.n}",
                expected.unitPrice,
                content.unitPrice.minor,
            )
            assertEquals(
                "Costo unitario de la línea ${expected.n}",
                expected.unitCost,
                content.unitCost.minor,
            )
            assertEquals(
                "Costo de la línea ${expected.n}",
                expected.lineCost,
                content.lineCost.minor,
            )
            assertEquals("Neto de la línea ${expected.n}", expected.net, content.netAmount.minor)
            checks += 4
        }
        checks += 3
    }

    private fun margin(bp: Long?): Margin =
        if (bp == null) Margin.NoData else Margin.Defined(BasisPoints(bp))

    /** Los casos de uso tal como los arman las pantallas de producto, compra, venta y stock. */
    private class UseCases(
        val create: CreateProductUseCase,
        val purchase: RegisterPurchaseUseCase,
        val voidPurchase: VoidPurchaseUseCase,
        val sale: RegisterSaleUseCase,
        val adjust: AdjustStockUseCase,
        val changePrice: ChangePriceUseCase,
        val paymentMethodId: Long,
    ) {
        companion object {
            suspend fun of(ports: DataPorts, app: ShopLedgerApp): UseCases {
                val container = app.container
                val clock = container.clock
                val calendar = BusinessCalendarSource(ports.settings)
                val currencyLock = CurrencyLock(ports.settings)
                val method = requireNotNull(
                    ports.paymentMethods.findActive().minByOrNull { it.sortOrder },
                ) { "La base no trae ningún método de pago activo" }
                return UseCases(
                    create = CreateProductUseCase(
                        ports.transactions, ports.products, ports.categories, ports.suppliers,
                        ports.units, ports.systemRows, ports.priceHistory, ports.stockLedger,
                        ports.lowStockAlerts, calendar, currencyLock, clock, ports.mediaAssets,
                    ),
                    purchase = RegisterPurchaseUseCase(
                        transactions = ports.transactions,
                        settings = ports.settings,
                        products = ports.products,
                        purchases = ports.purchases,
                        suppliers = ports.suppliers,
                        units = ports.units,
                        systemRows = ports.systemRows,
                        ledger = ports.stockLedger,
                        taxRates = ports.taxRates,
                        clock = clock,
                        productUnits = ports.productUnits,
                        alerts = ports.lowStockAlerts,
                        categories = ports.categories,
                    ),
                    voidPurchase = VoidPurchaseUseCase(
                        transactions = ports.transactions,
                        settings = ports.settings,
                        products = ports.products,
                        purchases = ports.purchases,
                        ledger = ports.stockLedger,
                        clock = clock,
                        alerts = ports.lowStockAlerts,
                    ),
                    sale = RegisterSaleUseCase(
                        transactions = ports.transactions,
                        settings = ports.settings,
                        products = ports.products,
                        sales = ports.sales,
                        payments = ports.salePayments,
                        units = ports.units,
                        systemRows = ports.systemRows,
                        ledger = ports.stockLedger,
                        methodNames = container.paymentMethodNames,
                        taxRates = ports.taxRates,
                        clock = clock,
                        heldSales = ports.heldSales,
                        productUnits = ports.productUnits,
                        alerts = ports.lowStockAlerts,
                    ),
                    adjust = AdjustStockUseCase(
                        ports.transactions, ports.products, ports.units, ports.systemRows,
                        ports.stockLedger, ports.lowStockAlerts, calendar, currencyLock, clock,
                        ports.stockLots,
                    ),
                    changePrice = ChangePriceUseCase(
                        ports.transactions,
                        ports.products,
                        ports.systemRows,
                        ports.priceHistory,
                        calendar,
                        currencyLock,
                        clock,
                    ),
                    paymentMethodId = method.id,
                )
            }
        }
    }

    /** Un producto inventado: precio y costo en unidades menores, y el resto en unidades. */
    private data class Seed(
        val name: String,
        val price: Long,
        val cost: Long,
        val openingUnits: Long,
        val minUnits: Long,
    )

    /** Una línea de venta esperada del producto [n]. */
    private data class ExpectedLine(
        val n: Int,
        val unitPrice: Long,
        val unitCost: Long,
        val lineCost: Long,
        val net: Long,
    )

    /**
     * El estado final esperado del producto [n]: cantidad en milésimas, valor y costo promedio en
     * unidades menores, márgenes en puntos básicos (`null` es sin margen) y alertas.
     */
    private data class Final(
        val n: Int,
        val onHand: Long,
        val value: Long,
        val average: Long,
        val listMarginBp: Long?,
        val detailMarginBp: Long?,
        val activeAlert: LowStockNotificationStatus?,
        val pendingAlerts: Int,
    )

    private companion object {
        const val ARGUMENT = "inventoryTwentyProducts"
        const val TAG = "InventoryTwentyProducts"
        const val TEST_DECIMALS = 2
        const val ONE_UNIT = 1_000L

        /**
         * Veinte productos de papelería inventados. Cada uno repite un ejemplo de las reglas de
         * costo, margen o alerta: la secuencia del promedio ponderado (1), stock en cero con costo
         * anterior (2), negativo (3), compra que no cubre el negativo (4), stock sin costo (5),
         * compras gratis (6 y 7), total sin unitario exacto (8), compra equivocada a tiempo (9),
         * con parte vendida (10) y con todo vendido (11), conteo que baja (12) y que sube (13),
         * alerta paso a paso (14), cruce de largo (15), cambio de precio (16 y 17), margen del 20 %
         * (18), sin precio (19) y tres compras a distinto costo (20).
         */
        val SEED = listOf(
            Seed("Cuaderno rayado", price = 1_500, cost = 0, openingUnits = 0, minUnits = 2),
            Seed("Lápiz negro", price = 2_000, cost = 1_300, openingUnits = 5, minUnits = 0),
            Seed("Borrador blanco", price = 1_800, cost = 1_300, openingUnits = 3, minUnits = 1),
            Seed("Tijeras escolares", price = 1_400, cost = 1_000, openingUnits = 1, minUnits = 0),
            Seed("Pegante en barra", price = 1_250, cost = 0, openingUnits = 5, minUnits = 0),
            Seed("Regla de treinta", price = 1_000, cost = 1_000, openingUnits = 10, minUnits = 3),
            Seed("Marcador azul", price = 500, cost = 0, openingUnits = 0, minUnits = 0),
            Seed("Cinta adhesiva", price = 10_000, cost = 8_000, openingUnits = 2, minUnits = 1),
            Seed("Sobre de manila", price = 1_500, cost = 1_000, openingUnits = 10, minUnits = 0),
            Seed("Carpeta plástica", price = 6_000, cost = 1_000, openingUnits = 10, minUnits = 1),
            Seed("Resma carta", price = 1_300, cost = 900, openingUnits = 0, minUnits = 0),
            Seed("Caja de colores", price = 1_700, cost = 1_355, openingUnits = 12, minUnits = 10),
            Seed("Sacapuntas", price = 1_300, cost = 1_001, openingUnits = 3, minUnits = 2),
            Seed("Block de dibujo", price = 1_000, cost = 700, openingUnits = 20, minUnits = 5),
            Seed("Témpera roja", price = 450, cost = 300, openingUnits = 20, minUnits = 5),
            Seed("Pincel plano", price = 3_000, cost = 2_000, openingUnits = 10, minUnits = 0),
            Seed("Compás escolar", price = 1_500, cost = 1_200, openingUnits = 6, minUnits = 1),
            Seed("Calculadora", price = 10_000, cost = 8_000, openingUnits = 5, minUnits = 0),
            Seed("Agenda pequeña", price = 0, cost = 1_200, openingUnits = 4, minUnits = 0),
            Seed("Clips metálicos", price = 1_600, cost = 0, openingUnits = 0, minUnits = 2),
        )

        val FIRST_SALE = listOf(
            ExpectedLine(1, unitPrice = 1_500, unitCost = 1_300, lineCost = 10_400, net = 12_000),
            ExpectedLine(2, unitPrice = 2_000, unitCost = 1_300, lineCost = 6_500, net = 10_000),
            ExpectedLine(3, unitPrice = 1_800, unitCost = 1_300, lineCost = 6_500, net = 9_000),
            ExpectedLine(4, unitPrice = 1_400, unitCost = 1_000, lineCost = 6_000, net = 8_400),
            ExpectedLine(14, unitPrice = 1_000, unitCost = 700, lineCost = 9_800, net = 14_000),
            ExpectedLine(15, unitPrice = 450, unitCost = 300, lineCost = 6_900, net = 10_350),
            ExpectedLine(16, unitPrice = 3_000, unitCost = 2_000, lineCost = 4_000, net = 6_000),
            ExpectedLine(17, unitPrice = 1_500, unitCost = 1_200, lineCost = 1_200, net = 1_500),
            ExpectedLine(18, unitPrice = 10_000, unitCost = 8_000, lineCost = 8_000, net = 10_000),
            ExpectedLine(20, unitPrice = 1_600, unitCost = 1_120, lineCost = 3_360, net = 4_800),
        )

        val SECOND_SALE = listOf(
            ExpectedLine(10, unitPrice = 6_000, unitCost = 5_455, lineCost = 49_091, net = 54_000),
            ExpectedLine(11, unitPrice = 1_300, unitCost = 1_000, lineCost = 10_000, net = 13_000),
        )

        val THIRD_SALE = listOf(
            ExpectedLine(16, unitPrice = 3_500, unitCost = 2_000, lineCost = 2_000, net = 3_500),
        )

        private val PENDING = LowStockNotificationStatus.PENDING

        val FINAL = listOf(
            Final(1, 11_000, 14_900, 1_355, 967, 970, null, 0),
            Final(2, 5_000, 8_000, 1_600, 2_000, 2_000, null, 1),
            Final(3, 8_000, 12_000, 1_500, 1_667, 1_667, null, 1),
            Final(4, -3_000, 0, 1_500, -714, -714, PENDING, 1),
            Final(5, 10_000, 10_000, 1_000, 2_000, 2_000, null, 0),
            Final(6, 12_000, 10_000, 833, 1_670, 1_667, null, 0),
            // Sin costo conocido: la lista mide contra cero y lo marca; el detalle no da margen.
            Final(7, 3_000, 0, 0, 10_000, null, null, 0),
            Final(8, 5_000, 41_000, 8_200, 1_800, 1_800, null, 0),
            Final(9, 10_000, 10_000, 1_000, 3_333, 3_333, null, 0),
            Final(10, 1_000, 0, 1_000, 8_333, 8_333, PENDING, 1),
            Final(11, -10_000, 0, 900, 3_077, 3_077, PENDING, 1),
            Final(12, 10_000, 13_550, 1_355, 2_029, 2_029, PENDING, 1),
            Final(13, 8_000, 8_003, 1_000, 2_308, 2_305, null, 0),
            Final(14, 5_000, 3_650, 730, 2_700, 2_700, PENDING, 2),
            Final(15, -3_000, 0, 300, 3_333, 3_333, PENDING, 1),
            Final(16, 7_000, 14_000, 2_000, 4_286, 4_286, null, 0),
            Final(17, 5_000, 6_000, 1_200, -2_000, -2_000, null, 0),
            Final(18, 4_000, 32_000, 8_000, 2_000, 2_000, null, 0),
            Final(19, 4_000, 4_800, 1_200, null, null, null, 0),
            Final(20, 10_000, 11_841, 1_184, 2_600, 2_599, null, 0),
        )
    }
}
