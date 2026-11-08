package io.github.yesidrobayo.shopledger.data.repository

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
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
 * invariantes. Además mide cuánto tarda confirmar una venta de diez líneas.
 *
 * Los productos y montos son inventados. Solo registra conteos y duraciones.
 */
@RunWith(AndroidJUnit4::class)
internal class RegisterSaleOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val salePayments by lazy { RoomSalePaymentRepository(database) }

    private val names = PaymentMethodNames { key -> "Initial ${key.lowercase()}" }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = RoomAppSettingsRepository(database, RestoreLock { false }),
            products = products,
            sales = RoomSaleRepository(database),
            payments = salePayments,
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = names,
            clock = Clock { AT },
        )
    }

    private suspend fun cashMethodId(): Long =
        count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")

    /** Un producto con libro y [units] unidades de inventario inicial a 1.200. */
    private suspend fun trackedProduct(name: String, units: Long): Long = commit {
        val id = products.insert(newProduct(name).copy(salePrice = Money(3_500)))
        ledger.append(openingRow(id, units))
        id
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
    fun aTenLineSaleIsMeasured() = runBlocking {
        val ids = List(LINES) { trackedProduct("Producto inventado $it", units = 1_000) }
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
