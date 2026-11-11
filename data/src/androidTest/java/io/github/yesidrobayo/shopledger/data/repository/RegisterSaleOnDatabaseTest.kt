package io.github.yesidrobayo.shopledger.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Una venta confirmada sobre la base real, con sus guardas: lo que el caso de uso calcula en
 * memoria debe ser exactamente lo que las guardas aceptan, y dejar en cero filas cada consulta de
 * invariantes, también con impuesto en cada modo. Además mide cuánto tarda confirmar una venta de
 * diez líneas con impuesto.
 *
 * Los productos y montos son inventados. Solo registra conteos y duraciones.
 */
@RunWith(AndroidJUnit4::class)
internal class RegisterSaleOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val salePayments by lazy { RoomSalePaymentRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val taxRates by lazy { RoomTaxRateRepository(database) }
    private val taxSettings by lazy { RoomTaxSettingsRepository(database, settings) }
    private val sales by lazy { RoomSaleRepository(database) }

    private val names = PaymentMethodNames { key -> "Initial ${key.lowercase()}" }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            payments = salePayments,
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = names,
            taxRates = taxRates,
            clock = Clock { AT },
        )
    }

    private suspend fun cashMethodId(): Long =
        count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")

    /**
     * Un producto con libro, [units] unidades de inventario inicial a 1.200 y precio 3.500. Con
     * [taxRateId] paga esa tasa; sin ella, la tasa por defecto del negocio.
     */
    private suspend fun trackedProduct(name: String, units: Long, taxRateId: Long? = null): Long =
        commit {
            val details = productDetails(name).copy(taxRateId = taxRateId)
            val id = products.insert(newProduct(name, details).copy(salePrice = Money(3_500)))
            ledger.append(openingRow(id, units))
            id
        }

    /** Crea una tasa inventada y devuelve su id. */
    private suspend fun rate(name: String, bp: Long): Long = commit {
        taxRates.insert(TaxRateDetails(name, TextNormalizer.normalize(name), BasisPoints(bp)), AT)
    }

    private suspend fun useTaxes(mode: TaxMode, defaultTaxRateId: Long?) = commit {
        taxSettings.update(TaxSettings(mode, defaultTaxRateId, purchaseTaxRecoverable = false), AT)
    }

    @Test
    fun aSaleWithoutEnoughStockPassesEveryGuardAndInvariant() = runBlocking {
        val soap = trackedProduct("Jabón inventado", units = 1)
        val service =
            commit {
                products.insert(
                    newProduct(
                        "Servicio inventado",
                        productDetails("Servicio inventado", ProductStockMode.UNTRACKED),
                    ),
                )
            }
        val before = changeSeq()

        val result =
            registerSale(
                SaleRequest(
                    lines =
                        listOf(
                            SaleLineRequest(soap, QuantityMilli(3_000)),
                            SaleLineRequest(
                                service,
                                QuantityMilli(1_000),
                                discount = LineDiscount.Amount(Money(500)),
                            ),
                        ),
                    paymentMethodId = cashMethodId(),
                ),
            )

        val saleId = (result as RegisterSaleResult.Registered).saleId
        assertEquals(before + 1, changeSeq())
        // Vender sin stock deja el saldo negativo, sin bloquear nada.
        val soapAfter = requireNotNull(products.findById(soap))
        assertEquals(QuantityMilli(-2_000), soapAfter.onHand)
        assertEquals(Money.ZERO, soapAfter.stockValue)
        val movements = ledger.findBySale(saleId)
        assertEquals(listOf(StockMovementType.SALE), movements.map { it.movement.type })
        // Una unidad se lleva su valor, y las dos que faltan salen al costo promedio.
        assertEquals(Money(-3_600), movements.single().totalCost)
        val payment = salePayments.findBySale(saleId).single().content
        assertEquals("Initial cash", payment.methodNameSnapshot)
        assertEquals(PaymentMethodKind.CASH, payment.methodKind)
        assertEquals(Money(10_500 + 2_000), payment.amount)
        assertInvariantsHold()
    }

    @Test
    fun taxedSalesInEveryModePassEveryGuardAndInvariant() = runBlocking {
        val general = rate("Tasa general inventada", 2_100)
        val reduced = rate("Tasa reducida inventada", 500)
        val soap = trackedProduct("Jabón inventado", units = 10, taxRateId = general)
        val tea = trackedProduct("Té inventado", units = 10)
        // Neto, impuesto, total y tasa de cada línea, escritos a mano: 2 jabones a 3.500 son
        // 7.000, y un té de 3.500 con 500 de descuento son 3.000.
        val expected =
            mapOf(
                TaxMode.NONE to
                    listOf(listOf(7_000L, 0L, 7_000L, 0L), listOf(3_000L, 0L, 3_000L, 0L)),
                TaxMode.INCLUDED to
                    listOf(
                        listOf(5_785L, 1_215L, 7_000L, 2_100L),
                        listOf(2_857L, 143L, 3_000L, 500L),
                    ),
                TaxMode.ADDED to
                    listOf(
                        listOf(7_000L, 1_470L, 8_470L, 2_100L),
                        listOf(3_000L, 150L, 3_150L, 500L),
                    ),
            )

        for ((mode, lines) in expected) {
            useTaxes(mode, defaultTaxRateId = reduced)
            val result =
                registerSale(
                    SaleRequest(
                        listOf(
                            SaleLineRequest(soap, QuantityMilli(2_000)),
                            SaleLineRequest(
                                tea,
                                QuantityMilli(1_000),
                                discount = LineDiscount.Amount(Money(500)),
                            ),
                        ),
                        cashMethodId(),
                    ),
                )

            val saleId = (result as RegisterSaleResult.Registered).saleId
            val stored = sales.findLines(saleId).map { it.content }
            assertEquals(
                mode.name,
                lines,
                stored.map {
                    listOf(
                        it.netAmount.minor,
                        it.taxAmount.minor,
                        it.lineTotal.minor,
                        it.taxRate.bp,
                    )
                },
            )
            val sale = requireNotNull(sales.findById(saleId)).content
            assertEquals(Money(lines.sumOf { it[2] }), sale.total)
            assertEquals(Money(lines.sumOf { it[1] }), sale.taxTotal)
            assertEquals(sale.total, salePayments.findBySale(saleId).single().content.amount)
        }
        assertInvariantsHold()
    }

    @Test
    fun aTenLineSaleIsMeasured() = runBlocking {
        // Con impuesto sumado y dos tasas, el camino más largo de una línea.
        val general = rate("Tasa general inventada", 2_100)
        useTaxes(TaxMode.ADDED, defaultTaxRateId = rate("Tasa reducida inventada", 500))
        val ids =
            List(LINES) { index ->
                trackedProduct(
                    "Producto inventado $index",
                    units = 1_000,
                    taxRateId = general.takeIf { index % 2 == 0 },
                )
            }
        val request =
            SaleRequest(ids.map { SaleLineRequest(it, QuantityMilli(1_000)) }, cashMethodId())

        // La primera venta calienta la caché de páginas y el código: no cuenta.
        assertTrue(registerSale(request) is RegisterSaleResult.Registered)
        val times =
            List(ROUNDS) {
                val mark = TimeSource.Monotonic.markNow()
                val result = registerSale(request)
                val elapsed = mark.elapsedNow().inWholeMilliseconds
                assertTrue(result is RegisterSaleResult.Registered)
                elapsed
            }
        val sorted = times.sorted()
        Log.i(
            TAG,
            "venta de $LINES líneas: mediana ${sorted[sorted.size / 2]} ms, " +
                "máximo ${sorted.last()} ms, en $ROUNDS ventas",
        )
        assertInvariantsHold()
        // El tope del requisito se mide en el teléfono de referencia; aquí se anota la medida, y
        // solo se exige que no esté lejos de él.
        val median = sorted[sorted.size / 2]
        assertTrue("Mediana $median ms", median <= EMULATOR_CEILING_MS)
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }

    private companion object {
        const val TAG = "RegisterSaleTiming"
        const val LINES = 10
        const val ROUNDS = 15

        /** El tope del requisito para confirmar una venta, con margen para un emulador. */
        const val EMULATOR_CEILING_MS = 600L
    }
}
