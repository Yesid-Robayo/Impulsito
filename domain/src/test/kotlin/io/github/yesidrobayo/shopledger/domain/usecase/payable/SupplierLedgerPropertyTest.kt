package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.TODAY
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.atUnitCost
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleTables
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba de propiedades del libro del proveedor: secuencias al azar, con semilla fija, de compras
 * pagadas completas, a plazo y con lo pagado fuera de rango, sin proveedor, con vencimientos
 * anteriores a la compra, pagos parciales y de más, anulaciones de compras y de pagos, también
 * repetidas, y fallas inyectadas en cualquier escritura.
 *
 * Tras cada operación comprueba, contra un oráculo llevado aquí y no con el código que prueba: que
 * el saldo de cada proveedor es la suma de su libro; que cada fila guarda el saldo de la anterior
 * más su monto; que el libro solo creció; que cada compensación anula una sola fila, del mismo
 * proveedor y con el monto opuesto; que el saldo es lo que deben las compras a plazo vigentes,
 * calculado con los totales escritos por la prueba; y que un rechazo o una falla no dejaron rastro.
 *
 * Al final cuenta cuántas veces pasó por cada camino, para que un cambio del generador no deje la
 * prueba en verde sin probar nada. Si falla, el mensaje trae la semilla y el paso.
 */
class SupplierLedgerPropertyTest {
    @Test
    fun `random sequences keep each supplier balance equal to its ledger`() = runTest {
        val stats = Stats()
        repeat(SEQUENCES) { index -> runSequence(BASE_SEED + index, stats) }

        val paths = mapOf(
            "compras a plazo" to stats.credit,
            "compras pagadas completas" to stats.paidInFull,
            "anulaciones con cargo" to stats.voidedCredit,
            "anulaciones repetidas" to stats.repeatedVoids,
            "rechazos sin proveedor" to stats.withoutSupplier,
            "rechazos por lo pagado" to stats.outOfRange,
            "rechazos por el vencimiento" to stats.dueBefore,
            "fallas inyectadas" to stats.failures,
            "pagos" to stats.payments,
            "saldos a favor" to stats.inFavor,
            "pagos anulados" to stats.voidedPayments,
            "pagos anulados de nuevo" to stats.repeatedPaymentVoids,
        )
        for ((path, count) in paths) assertTrue("Ningún caso de: $path", count > 0)
    }

    private suspend fun runSequence(seed: Long, stats: Stats) {
        val random = Random(seed)
        val world = SaleWorld().apply {
            addProduct(product(1))
            addProduct(product(2))
            SUPPLIERS.forEach { id -> addSupplier(id, termsDays = if (id == 1L) null else 15) }
        }
        // Lo que debería deber cada compra a plazo vigente, según lo que escribió la prueba.
        val owed = HashMap<Long, Pair<Long, Long>>()
        // Lo pagado en cada pago vigente, por su fila: proveedor y monto.
        val payments = HashMap<Long, Pair<Long, Long>>()
        repeat(random.nextInt(MIN_OPERATIONS, MAX_OPERATIONS + 1)) { step ->
            val before = world.tables
            val inject = random.nextInt(100) < FAILURE_PERCENT
            world.failAtWrite = if (inject) random.nextInt(1, MAX_FAILED_WRITE + 1) else null
            val choice = random.nextInt(100)
            val changed = when {
                choice < PAY_PERCENT -> payRandom(world, random, payments, stats)

                choice < PAY_PERCENT + VOID_PAYMENT_PERCENT ->
                    voidPaymentRandom(world, random, payments, stats)

                (random.nextInt(100) < VOID_PERCENT && owed.isNotEmpty()) ||
                    random.nextInt(100) < REPEATED_VOID_PERCENT ->
                    voidRandom(world, random, owed, stats)

                else -> registerRandom(world, random, owed, stats)
            }
            world.failAtWrite = null
            val where = "semilla $seed, paso $step"
            if (!changed) assertEquals(where, before, world.tables)
            checkLedger(where, before, world.tables, owed, payments)
            for (id in SUPPLIERS) {
                if (world.tables.suppliers.getValue(id).balance < Money.ZERO) stats.inFavor++
            }
        }
    }

    /** Registra una compra al azar. Devuelve si debía cambiar algo. */
    private suspend fun registerRandom(
        world: SaleWorld,
        random: Random,
        owed: MutableMap<Long, Pair<Long, Long>>,
        stats: Stats,
    ): Boolean {
        val costs = List(random.nextInt(1, 3)) { random.nextLong(1, 100_000) }
        val total = costs.sum()
        val supplier = if (random.nextInt(100) < 15) null else SUPPLIERS.random(random)
        val paid: Long? = when (random.nextInt(100)) {
            in 0 until 20 -> null
            in 20 until 30 -> total
            in 30 until 38 -> if (random.nextBoolean()) total + 1 else -1
            else -> random.nextLong(0, total)
        }
        val due: BusinessDate? = if (random.nextBoolean()) {
            null
        } else {
            TODAY.plusDays(
                random.nextInt(-3, 60),
            )
        }
        val request = PurchaseRequest(
            lines = costs.mapIndexed { index, cost -> atUnitCost(1L + index, 1_000, cost) },
            supplierId = supplier,
            amountPaid = paid?.let(::Money),
            dueDate = due,
        )
        val result = try {
            world.registerPurchase()(request)
        } catch (failure: InjectedSaleFailure) {
            stats.failures++
            return false
        }
        val credit = total - (paid ?: total)
        return when (result) {
            is RegisterPurchaseResult.Registered -> {
                if (credit > 0) {
                    stats.credit++
                    owed[result.purchaseId] = checkNotNull(supplier) to credit
                } else {
                    stats.paidInFull++
                }
                true
            }

            RegisterPurchaseResult.SupplierRequiredForCredit -> {
                assertTrue(supplier == null && credit > 0)
                stats.withoutSupplier++
                false
            }

            RegisterPurchaseResult.AmountPaidOutOfRange -> {
                assertTrue(paid != null && (paid < 0 || paid > total))
                stats.outOfRange++
                false
            }

            RegisterPurchaseResult.DueDateBeforePurchase -> {
                assertTrue(due != null && due < TODAY && credit > 0)
                stats.dueBefore++
                false
            }

            else -> error("Resultado inesperado: $result")
        }
    }

    /** Paga a un proveedor al azar, a veces más de lo que se le debe. */
    private suspend fun payRandom(
        world: SaleWorld,
        random: Random,
        payments: MutableMap<Long, Pair<Long, Long>>,
        stats: Stats,
    ): Boolean {
        val supplier = SUPPLIERS.random(random)
        val amount = random.nextLong(1, 60_000)
        val result = try {
            PaySupplierUseCase(
                world.transactions,
                world.settings,
                world.supplierRepository,
                world.supplierLedger,
                world.clock,
            )(SupplierPaymentRequest(supplier, Money(amount)))
        } catch (failure: InjectedSaleFailure) {
            stats.failures++
            return false
        }
        val entryId = (result as PaySupplierResult.Paid).entryId
        payments[entryId] = supplier to amount
        stats.payments++
        return true
    }

    /** Anula un pago al azar, quizá ya anulado. */
    private suspend fun voidPaymentRandom(
        world: SaleWorld,
        random: Random,
        payments: MutableMap<Long, Pair<Long, Long>>,
        stats: Stats,
    ): Boolean {
        val ids = world.tables.supplierEntries.values
            .filter { it.content.type == SupplierLedgerEntryType.PAYMENT }
            .map { it.id }
        if (ids.isEmpty()) return false
        val id = ids.random(random)
        val result = try {
            VoidSupplierPaymentUseCase(
                world.transactions,
                world.settings,
                world.supplierLedger,
                world.clock,
            )(id)
        } catch (failure: InjectedSaleFailure) {
            stats.failures++
            return false
        }
        return when (result) {
            VoidSupplierPaymentResult.Voided -> {
                checkNotNull(payments.remove(id))
                stats.voidedPayments++
                true
            }

            VoidSupplierPaymentResult.AlreadyVoided -> {
                stats.repeatedPaymentVoids++
                false
            }

            else -> error("Resultado inesperado: $result")
        }
    }

    /** Anula una compra al azar, quizá ya anulada. Devuelve si debía cambiar algo. */
    private suspend fun voidRandom(
        world: SaleWorld,
        random: Random,
        owed: MutableMap<Long, Pair<Long, Long>>,
        stats: Stats,
    ): Boolean {
        val ids = world.tables.purchases.keys.toList()
        if (ids.isEmpty()) return false
        val id = ids.random(random)
        val result = try {
            world.voidPurchase()(id, VoidReason.ENTRY_ERROR, null)
        } catch (failure: InjectedSaleFailure) {
            stats.failures++
            return false
        }
        return when (result) {
            VoidPurchaseResult.Voided -> {
                if (owed.remove(id) != null) stats.voidedCredit++
                true
            }

            VoidPurchaseResult.AlreadyVoided -> {
                stats.repeatedVoids++
                false
            }

            else -> error("Resultado inesperado: $result")
        }
    }

    private fun checkLedger(
        where: String,
        before: SaleTables,
        after: SaleTables,
        owed: Map<Long, Pair<Long, Long>>,
        payments: Map<Long, Pair<Long, Long>>,
    ) {
        // El libro solo crece: ninguna fila anterior cambió ni desapareció.
        for ((id, entry) in before.supplierEntries) {
            assertEquals("$where: fila $id", entry, after.supplierEntries[id])
        }
        val entries = after.supplierEntries.values.sortedBy { it.id }
        for (supplierId in SUPPLIERS) {
            val own = entries.filter { it.content.supplierId == supplierId }
            var running = 0L
            for (entry in own) {
                running += entry.content.amount.minor
                assertEquals(
                    "$where: cadena de ${entry.id}",
                    running,
                    entry.content.balanceAfter.minor,
                )
            }
            assertEquals(
                "$where: saldo de $supplierId",
                running,
                after.suppliers.getValue(supplierId).balance.minor,
            )
            val expected = owed.values.filter { it.first == supplierId }.sumOf { it.second } -
                payments.values.filter { it.first == supplierId }.sumOf { it.second }
            assertEquals("$where: deuda vigente de $supplierId", expected, running)
        }
        checkCompensations(where, entries)
    }

    private fun checkCompensations(where: String, entries: List<SupplierLedgerEntry>) {
        val byId = entries.associateBy { it.id }
        val reversed = entries.mapNotNull { it.content.reversesEntryId }
        assertEquals("$where: una compensación por fila", reversed.size, reversed.toSet().size)
        for (entry in entries) {
            val reverses = entry.content.reversesEntryId
            assertEquals(entry.content.type == SupplierLedgerEntryType.ENTRY_VOID, reverses != null)
            if (reverses == null) continue
            val original = checkNotNull(byId[reverses]) { "$where: falta la fila anulada" }
            assertEquals(where, original.content.supplierId, entry.content.supplierId)
            assertEquals(where, -original.content.amount.minor, entry.content.amount.minor)
            assertTrue(where, original.id < entry.id)
        }
    }

    private class Stats {
        var credit = 0
        var paidInFull = 0
        var voidedCredit = 0
        var repeatedVoids = 0
        var withoutSupplier = 0
        var outOfRange = 0
        var dueBefore = 0
        var failures = 0
        var payments = 0
        var inFavor = 0
        var voidedPayments = 0
        var repeatedPaymentVoids = 0
    }

    private companion object {
        const val SEQUENCES = 300
        const val BASE_SEED = 20_261_112L
        const val MIN_OPERATIONS = 5
        const val MAX_OPERATIONS = 30
        const val FAILURE_PERCENT = 10
        const val MAX_FAILED_WRITE = 12
        const val VOID_PERCENT = 30
        const val REPEATED_VOID_PERCENT = 5
        const val PAY_PERCENT = 20
        const val VOID_PAYMENT_PERCENT = 8
        val SUPPLIERS = listOf(1L, 2L, 3L)
    }
}
