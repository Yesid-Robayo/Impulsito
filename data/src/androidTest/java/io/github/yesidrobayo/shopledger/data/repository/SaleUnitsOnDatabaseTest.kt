package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCart
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCorrections
import io.github.yesidrobayo.shopledger.domain.usecase.sale.LineRejection
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleDetailUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vender por caja sobre Room, con sus guardas: la línea guarda la unidad, el factor y la cantidad
 * escrita, el libro sale en la unidad base, y cada consulta de invariantes queda en cero, así que
 * el saldo del producto sigue siendo la suma de su libro. Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleUnitsOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val productUnits by lazy { RoomProductUnitRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val register by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = RoomSaleRepository(database),
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
            productUnits = productUnits,
        )
    }

    private suspend fun unitId(key: String): Long =
        count("SELECT id FROM units WHERE system_key = '$key'")

    private suspend fun sell(vararg lines: SaleLineRequest): RegisterSaleResult {
        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        return register(SaleRequest(lines.toList(), cash))
    }

    @Test
    fun threeBoxesPassEveryGuardAndLeaveTheLedgerInUnits() = runBlocking {
        val box = unitId("BOX")
        val id = commit {
            val id = products.insert(newProduct("Jabón inventado"))
            ledger.append(openingRow(id, units = 100))
            productUnits.insert(id, ConversionDetails(box, 24_000, null, null, true, true), AT)
            id
        }

        val result = sell(SaleLineRequest(id, QuantityMilli(3_000), unitId = box))

        val saleId = (result as RegisterSaleResult.Registered).saleId
        val stored = RoomSaleRepository(database).findLines(saleId).single().content
        assertEquals(box, stored.unitId)
        assertEquals(QuantityMilli(24_000), stored.unitFactor)
        assertEquals(QuantityMilli(3_000), stored.enteredQuantity)
        assertEquals(QuantityMilli(72_000), stored.quantity)
        // La caja sin precio propio vale 24 veces el precio del producto.
        assertEquals(Money(24 * 2_500L), stored.unitPrice)
        val rows = ledger.findBySale(saleId)
        assertEquals(listOf(StockMovementType.SALE), rows.map { it.movement.type })
        assertEquals(QuantityMilli(-72_000), rows.single().movement.quantity)
        assertEquals(QuantityMilli(28_000), requireNotNull(products.findById(id)).onHand)
        assertInvariantsHold()
    }

    @Test
    fun aSaleByTheBoxVoidedAndCorrectedChargesTheSameFigures() = runBlocking {
        val box = unitId("BOX")
        val id = commit {
            val id = products.insert(newProduct("Jabón inventado"))
            ledger.append(openingRow(id, units = 100))
            productUnits.insert(id, ConversionDetails(box, 24_000, null, null, true, true), AT)
            id
        }
        val sales = RoomSaleRepository(database)
        val payments = RoomSalePaymentRepository(database)
        // Dos cajas a mano a 50000 la caja, con 1000 de descuento.
        val first = sell(
            SaleLineRequest(
                id,
                QuantityMilli(2_000),
                Money(50_000),
                LineDiscount.Amount(Money(1_000)),
                unitId = box,
            ),
        )
        val saleId = (first as RegisterSaleResult.Registered).saleId
        val void = VoidSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            ledger = ledger,
            clock = Clock { AT },
        )
        assertEquals(VoidSaleResult.Voided, void(saleId, VoidReason.ENTRY_ERROR, null))

        val detail = ObserveSaleDetailUseCase(sales, payments, products)(saleId).first()
        val correction = SaleCorrections.of(requireNotNull(detail))
        val second = sell(*SaleCart.normalize(correction.lines).toTypedArray())

        val newId = (second as RegisterSaleResult.Registered).saleId
        val original = sales.findLines(saleId).single().content
        val corrected = sales.findLines(newId).single().content
        assertEquals(box, corrected.unitId)
        assertEquals(QuantityMilli(2_000), corrected.enteredQuantity)
        assertEquals(QuantityMilli(48_000), corrected.quantity)
        assertEquals(Money(50_000), corrected.unitPrice)
        assertEquals(original.lineTotal, corrected.lineTotal)
        assertEquals(
            requireNotNull(sales.findById(saleId)).content.total,
            requireNotNull(sales.findById(newId)).content.total,
        )
        // 100 al abrir, 48 que salen, vuelven al anular y salen otra vez con la corrección.
        assertEquals(QuantityMilli(52_000), requireNotNull(products.findById(id)).onHand)
        assertInvariantsHold()
    }

    @Test
    fun aUnitTheProductDoesNotSellIsRejected() = runBlocking {
        val id = commit { products.insert(newProduct("Jabón inventado")) }

        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.UNIT_UNAVAILABLE),
            sell(SaleLineRequest(id, QuantityMilli(1_000), unitId = unitId("BOX"))),
        )
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
