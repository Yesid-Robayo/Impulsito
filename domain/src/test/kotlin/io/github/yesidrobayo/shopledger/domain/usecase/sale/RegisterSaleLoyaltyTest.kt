package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.usecase.customer.LoyaltyEntryOutcome
import io.github.yesidrobayo.shopledger.domain.usecase.customer.LoyaltyLedgerWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ganar y canjear puntos al vender, y revertirlos al anular. Los puntos no son dinero: el canje es
 * un descuento que entra en el ajuste de la venta, nunca un pago, y todo va en la misma transacción
 * que la venta. Los ejemplos del libro de reglas van escritos a mano.
 */
class RegisterSaleLoyaltyTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(method(id = ON_ACCOUNT, kind = PaymentMethodKind.ON_ACCOUNT))
        addProduct(product(PRODUCT, salePrice = 12_750, stockMode = ProductStockMode.UNTRACKED))
        addCustomer(CUSTOMER)
    }

    private suspend fun program(enabled: Boolean = true) = world.settings.updateLoyalty(
        LoyaltySettings(enabled, amountPerPoint = Money(1_000), pointValue = Money(5)),
        SaleWorld.EPOCH,
    )

    /** Deja al cliente con [points] puntos, como si ya los hubiera ganado: una fila de ajuste. */
    private fun givePoints(points: Long) = world.arrange { tables ->
        val entry = LoyaltyLedgerEntry(
            id = 1L,
            customerId = CUSTOMER,
            type = LoyaltyEntryType.ADJUSTMENT,
            points = points,
            balanceAfter = points,
            source = LoyaltyEntrySource.None,
            note = "Inicial",
            occurredAt = SaleWorld.EPOCH,
            businessDate = DAY,
        )
        val customer = tables.customers.getValue(CUSTOMER).copy(pointsBalance = points)
        tables.copy(
            loyaltyEntries = tables.loyaltyEntries + (1L to entry),
            customers = tables.customers + (CUSTOMER to customer),
            lastIds = tables.lastIds + ("loyalty_ledger_entries" to 1L),
        )
    }

    private fun sale(
        price: Long = 12_750,
        redeem: Long = 0L,
        customerId: Long? = CUSTOMER,
        adjustment: Long = 0L,
    ) = SaleRequest(
        lines = listOf(SaleLineRequest(PRODUCT, QuantityMilli.ofUnits(1L), Money(price))),
        paymentMethodId = SaleWorld.CASH,
        orderAdjustment = Money(adjustment),
        customerId = customerId,
        pointsRedeemed = redeem,
    )

    private suspend fun register(request: SaleRequest): RegisterSaleResult.Registered =
        world.registerSale()(request) as RegisterSaleResult.Registered

    private suspend fun rejectionOf(request: SaleRequest): RegisterSaleResult {
        val before = world.tables.loyaltyEntries
        val result = world.registerSale()(request)
        assertEquals("Un rechazo no confirma nada", 0, world.commits)
        assertTrue(world.tables.sales.isEmpty())
        assertEquals(before, world.tables.loyaltyEntries)
        return result
    }

    private fun entries(): List<LoyaltyLedgerEntry> =
        world.tables.loyaltyEntries.values.sortedBy { it.id }

    private fun balance(): Long = world.tables.customers.getValue(CUSTOMER).pointsBalance

    /** El saldo es la suma del libro, y cada fila deja el saldo de la anterior más sus puntos. */
    private fun assertBookHolds() {
        val rows = entries().filter { it.customerId == CUSTOMER }
        assertEquals(rows.sumOf { it.points }, balance())
        var previous = 0L
        for (row in rows) {
            assertEquals("Cadena en la fila ${row.id}", previous + row.points, row.balanceAfter)
            previous = row.balanceAfter
        }
    }

    @Test
    fun theExampleOfTheBookEarnsTwelvePoints() = runTest {
        program()

        val registered = register(sale())

        assertEquals(12L, registered.pointsEarned)
        val earn = entries().single()
        assertEquals(LoyaltyEntryType.EARN, earn.type)
        assertEquals(12L, earn.points)
        assertEquals(12L, earn.balanceAfter)
        assertEquals(LoyaltyEntrySource.Sale(registered.saleId), earn.source)
        val stored = world.tables.sales.getValue(registered.saleId).content
        assertEquals(stored.businessDate, earn.businessDate)
        assertEquals(12L, balance())
        assertBookHolds()
    }

    @Test
    fun redeemingAHundredPointsLowersTheOrderAdjustmentByFiveHundred() = runTest {
        program()
        givePoints(150)

        val registered = register(sale(price = 13_250, redeem = 100))

        val stored = world.tables.sales.getValue(registered.saleId).content
        assertEquals(Money(-500), stored.orderAdjustment)
        assertEquals(Money(12_750), stored.netTotal)
        // Los puntos no son dinero: el pago cubre lo que queda, sin ningún pago por los puntos.
        val payment = world.tables.payments.values.single()
        assertEquals(Money(12_750), payment.content.amount)
        assertEquals(PaymentMethodKind.CASH, payment.content.methodKind)
        // Se gana sobre lo neto después del canje: 12750 da 12.
        assertEquals(12L, registered.pointsEarned)
        val rows = entries().drop(1)
        assertEquals(listOf(LoyaltyEntryType.REDEEM, LoyaltyEntryType.EARN), rows.map { it.type })
        assertEquals(listOf(-100L, 12L), rows.map { it.points })
        assertEquals(listOf(50L, 62L), rows.map { it.balanceAfter })
        assertEquals(62L, balance())
        assertBookHolds()
    }

    @Test
    fun voidingTheSaleOfTheBookBringsTheBalanceBackToOneHundredFifty() = runTest {
        program()
        givePoints(150)
        val saleId = register(sale(price = 13_250, redeem = 100)).saleId
        val ofSale = entries().filter { it.source == LoyaltyEntrySource.Sale(saleId) }

        val result = world.voidSale()(saleId, VoidReason.CUSTOMER_RETURN, null)

        assertEquals(VoidSaleResult.Voided, result)
        val voids = entries().filter { it.type == LoyaltyEntryType.ENTRY_VOID }
        assertEquals(
            ofSale.map { LoyaltyEntrySource.Reverses(it.id) },
            voids.map { it.source },
        )
        assertEquals(ofSale.map { -it.points }, voids.map { it.points })
        assertEquals(150L, balance())
        assertBookHolds()
    }

    @Test
    fun voidingASaleWhosePointsWereSpentLeavesTheBalanceNegative() = runTest {
        program()
        val first = register(sale()).saleId
        // Los 12 puntos ganados se canjean en otra venta, que no alcanza a ganar ninguno.
        register(sale(price = 1_000, redeem = 12))
        assertEquals(0L, balance())

        val result = world.voidSale()(first, VoidReason.CUSTOMER_RETURN, null)

        assertEquals(VoidSaleResult.Voided, result)
        assertEquals(-12L, balance())
        assertBookHolds()
    }

    @Test
    fun aRowAlreadyCompensatedIsNotCompensatedAgain() = runTest {
        program()
        val saleId = register(sale()).saleId
        val earn = entries().single()
        // Alguien ya compensó lo ganado, con un ajuste de la persona antes de anular.
        world.arrange { tables ->
            val void = earn.copy(
                id = 50L,
                type = LoyaltyEntryType.ENTRY_VOID,
                points = -12L,
                balanceAfter = 0L,
                source = LoyaltyEntrySource.Reverses(earn.id),
            )
            val customer = tables.customers.getValue(CUSTOMER).copy(pointsBalance = 0L)
            tables.copy(
                loyaltyEntries = tables.loyaltyEntries + (50L to void),
                customers = tables.customers + (CUSTOMER to customer),
                lastIds = tables.lastIds + ("loyalty_ledger_entries" to 50L),
            )
        }

        val result = world.voidSale()(saleId, VoidReason.CUSTOMER_RETURN, null)

        assertEquals(VoidSaleResult.Voided, result)
        assertEquals(1, entries().count { it.type == LoyaltyEntryType.ENTRY_VOID })
        assertEquals(0L, balance())
    }

    @Test
    fun aReversalAboveTheLimitLeavesTheSaleActive() = runTest {
        program()
        givePoints(150)
        val saleId = register(sale(price = 13_250, redeem = 100)).saleId
        // Después de la venta el cliente quedó casi en el tope: devolverle 100 lo pasaría.
        world.arrange { tables ->
            val top = SaleWorld.MAX_POINTS - 20L
            val adjustment = entries().last().copy(
                id = 60L,
                type = LoyaltyEntryType.ADJUSTMENT,
                points = top - 62L,
                balanceAfter = top,
                source = LoyaltyEntrySource.None,
            )
            val customer = tables.customers.getValue(CUSTOMER).copy(pointsBalance = top)
            tables.copy(
                loyaltyEntries = tables.loyaltyEntries + (60L to adjustment),
                customers = tables.customers + (CUSTOMER to customer),
                lastIds = tables.lastIds + ("loyalty_ledger_entries" to 60L),
            )
        }
        val before = world.tables

        val result = world.voidSale()(saleId, VoidReason.CUSTOMER_RETURN, null)

        assertEquals(VoidSaleResult.LedgerDamaged, result)
        assertEquals(before, world.tables)
    }

    @Test
    fun withTheProgramOffNothingIsEarned() = runTest {
        program(enabled = false)

        val registered = register(sale())

        assertEquals(0L, registered.pointsEarned)
        assertTrue(world.tables.loyaltyEntries.isEmpty())
    }

    @Test
    fun aSaleWithoutACustomerEarnsNothing() = runTest {
        program()

        val registered = register(sale(customerId = null))

        assertEquals(0L, registered.pointsEarned)
        assertTrue(world.tables.loyaltyEntries.isEmpty())
    }

    @Test
    fun aSaleBelowOnePointWritesNoRow() = runTest {
        program()

        val registered = register(sale(price = 999))

        assertEquals(0L, registered.pointsEarned)
        assertTrue(world.tables.loyaltyEntries.isEmpty())
    }

    @Test
    fun anActiveProgramWithoutItsAmountEarnsNothing() = runTest {
        world.settings.updateLoyalty(
            LoyaltySettings(true, amountPerPoint = null, pointValue = Money(5)),
            SaleWorld.EPOCH,
        )

        assertEquals(0L, register(sale()).pointsEarned)
        assertTrue(world.tables.loyaltyEntries.isEmpty())
        // Un canje aceptado se escribe igual, aunque no se gane nada.
        givePoints(150)
        assertEquals(0L, register(sale(redeem = 100)).pointsEarned)
        assertEquals(LoyaltyEntryType.REDEEM, entries().last().type)
        assertEquals(50L, balance())
    }

    @Test
    fun redeemingAllOfTheSaleEarnsNothingAndStillRedeems() = runTest {
        program()
        givePoints(3_000)

        // 2550 puntos descuentan los 12750 de la venta: lo neto queda en cero y no gana.
        val registered = register(sale(redeem = 2_550))

        assertEquals(0L, registered.pointsEarned)
        assertEquals(Money.ZERO, world.tables.sales.getValue(registered.saleId).content.netTotal)
        assertEquals(LoyaltyEntryType.REDEEM, entries().last().type)
        assertEquals(450L, balance())
        assertBookHolds()
    }

    @Test
    fun whatWouldPassTheLimitIsNotEarned() = runTest {
        program()
        givePoints(SaleWorld.MAX_POINTS - 5L)

        val registered = register(sale())

        assertEquals(5L, registered.pointsEarned)
        assertEquals(SaleWorld.MAX_POINTS, balance())
        assertBookHolds()
    }

    @Test
    fun atTheLimitTheSaleIsRegisteredWithoutEarning() = runTest {
        program()
        givePoints(SaleWorld.MAX_POINTS)

        val registered = register(sale())

        assertEquals(0L, registered.pointsEarned)
        assertEquals(1, world.tables.loyaltyEntries.size)
    }

    @Test
    fun redeemingMoreThanTheBalanceIsRejectedWithWhatIsAvailable() = runTest {
        program()
        givePoints(150)

        assertEquals(
            RegisterSaleResult.NotEnoughPoints(150),
            rejectionOf(sale(redeem = 151)),
        )
    }

    @Test
    fun redeemingWithoutACustomerOrWithTheProgramOffIsNotAvailable() = runTest {
        givePoints(150)
        program(enabled = false)
        assertEquals(
            RegisterSaleResult.PointsNotAvailable,
            rejectionOf(sale(redeem = 10)),
        )
        program()
        assertEquals(
            RegisterSaleResult.PointsNotAvailable,
            rejectionOf(sale(redeem = 10, customerId = null)),
        )
        assertEquals(
            RegisterSaleResult.PointsNotAvailable,
            rejectionOf(sale(redeem = -10)),
        )
        world.settings.updateLoyalty(
            LoyaltySettings(true, amountPerPoint = Money(1_000), pointValue = null),
            SaleWorld.EPOCH,
        )
        assertEquals(
            RegisterSaleResult.PointsNotAvailable,
            rejectionOf(sale(redeem = 10)),
        )
    }

    @Test
    fun aDiscountAboveTheSaleIsRejected() = runTest {
        program()
        givePoints(10_000)

        // 2551 puntos descuentan 12755, cinco más que la venta.
        assertEquals(RegisterSaleResult.PointsAboveSale, rejectionOf(sale(redeem = 2_551)))
    }

    @Test
    fun aWrittenDiscountThatOnlyPassesWithThePointsIsRejectedForThePoints() = runTest {
        program()
        givePoints(10_000)

        assertEquals(
            RegisterSaleResult.PointsAboveSale,
            rejectionOf(sale(redeem = 1_000, adjustment = -10_000)),
        )
        // Un descuento escrito que ya se pasa solo conserva su propio motivo.
        assertEquals(
            RegisterSaleResult.OrderDiscountTooLarge,
            rejectionOf(sale(redeem = 1_000, adjustment = -20_000)),
        )
    }

    @Test
    fun aDiscountThatDoesNotFitADocumentIsAboveTheSale() = runTest {
        world.settings.updateLoyalty(
            LoyaltySettings(true, amountPerPoint = Money(1_000), pointValue = Money(1_000_000)),
            SaleWorld.EPOCH,
        )
        givePoints(SaleWorld.MAX_POINTS)

        assertEquals(
            RegisterSaleResult.PointsAboveSale,
            rejectionOf(sale(redeem = SaleWorld.MAX_POINTS)),
        )
    }

    @Test
    fun aCreditSaleWithPointsWritesBothBooksTogether() = runTest {
        program()
        givePoints(150)
        val request = sale(price = 13_250, redeem = 100).copy(
            payments = listOf(SalePaymentRequest(ON_ACCOUNT, Money(12_750))),
        )

        val registered = register(request)

        assertEquals(Money(12_750), world.tables.customers.getValue(CUSTOMER).balance)
        assertEquals(62L, balance())
        assertEquals(12L, registered.pointsEarned)
    }

    @Test
    fun aFailureAtAnyWriteLeavesNoSaleWithoutItsPoints() = runTest {
        program()
        givePoints(150)
        val request = sale(price = 13_250, redeem = 100)
        val before = world.tables
        // Cada escritura falla una vez: la cabecera, la línea, el pago, el canje, lo ganado y el
        // saldo de puntos del cliente. Ninguna deja una venta sin sus puntos, ni puntos sin venta.
        var failAt = 1
        while (true) {
            world.failAtWrite = failAt
            val outcome = runCatching { world.registerSale()(request) }
            if (outcome.isSuccess) break
            assertTrue("Escritura $failAt", outcome.exceptionOrNull() is InjectedSaleFailure)
            assertEquals("Escritura $failAt", before, world.tables)
            failAt++
        }
        assertTrue("Se probaron $failAt escrituras", failAt > MIN_WRITES)
        assertEquals(62L, balance())
        assertBookHolds()
    }

    @Test
    fun aFailureAtAnyWriteOfTheVoidLeavesThePointsAsTheyWere() = runTest {
        program()
        givePoints(150)
        val saleId = register(sale(price = 13_250, redeem = 100)).saleId
        val before = world.tables
        var failAt = 1
        while (true) {
            world.failAtWrite = failAt
            val outcome = runCatching {
                world.voidSale()(saleId, VoidReason.CUSTOMER_RETURN, null)
            }
            if (outcome.isSuccess) break
            assertEquals("Escritura $failAt", before, world.tables)
            failAt++
        }
        assertEquals(150L, balance())
        assertBookHolds()
    }

    @Test
    fun theWriterCompensatesOnlyAnExistingRowOfTheSameCustomerAndOnlyOnce() = runTest {
        program()
        val saleId = register(sale()).saleId
        val earn = entries().single()
        val writer = LoyaltyLedgerWriter(world.loyaltyLedger)
        val time = RecordTime(SaleWorld.EPOCH, DAY)

        assertEquals(
            LoyaltyEntryOutcome.EntryNotFound,
            writer.reverse(OTHER_CUSTOMER, earn.id, null, time),
        )
        assertEquals(LoyaltyEntryOutcome.EntryNotFound, writer.reverse(CUSTOMER, 999L, null, time))
        world.voidSale()(saleId, VoidReason.CUSTOMER_RETURN, null)
        val void = entries().last()
        assertEquals(
            LoyaltyEntryOutcome.NotReversible,
            writer.reverse(CUSTOMER, void.id, null, time),
        )
        assertEquals(
            LoyaltyEntryOutcome.AlreadyCompensated,
            writer.reverse(CUSTOMER, earn.id, null, time),
        )
    }

    private companion object {
        const val OTHER_CUSTOMER = 11L
        const val PRODUCT = 1L
        const val ON_ACCOUNT = 5L
        const val CUSTOMER = 10L
        const val MIN_WRITES = 8
        val DAY: BusinessDate = requireNotNull(BusinessDate.parse("2026-11-01"))
    }
}
