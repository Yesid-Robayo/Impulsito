package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.TransferRequest
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.testing.Tables
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Prueba de propiedades del libro de dinero: trescientas secuencias al azar, con semilla fija, de
 * cuentas en la moneda del negocio y en otra, saldos iniciales, entradas y salidas de cada origen,
 * anulaciones, también repetidas, montos que desbordan, transacciones deshechas y fallas a mitad.
 *
 * Tras cada paso comprueba, con oráculos escritos aquí y no con el código que prueba: que el libro
 * solo creció; que el saldo de cada cuenta es la suma de sus movimientos; que cada saldo guardado en
 * un movimiento es el anterior de su cuenta más su monto, en el orden del libro; que un rechazo no
 * dejó rastro; y que el dinero disponible suma solo las cuentas vigentes en la moneda del negocio,
 * nunca una de otra moneda.
 *
 * Al final cuenta cuántas veces pasó por cada camino. Si falla, el mensaje trae la semilla y el paso.
 */
class MoneyLedgerPropertyTest {
    private class Stats {
        var appended = 0
        var voids = 0
        var repeatedVoids = 0
        var documentVoids = 0
        var overflows = 0
        var rollbacks = 0
        var failures = 0
        var negativeBalances = 0
        var otherCurrencyAccounts = 0
        var archived = 0
        var transfers = 0
        var transferVoids = 0
    }

    @Test
    fun `random sequences keep every balance equal to its ledger and never mix currencies`() =
        runTest {
            val stats = Stats()
            repeat(SEQUENCES) { index -> runSequence(BASE_SEED + index, stats) }

            val paths = mapOf(
                "movimientos escritos" to stats.appended,
                "anulaciones" to stats.voids,
                "anulaciones repetidas" to stats.repeatedVoids,
                "anulaciones de un documento" to stats.documentVoids,
                "desbordes" to stats.overflows,
                "transacciones deshechas" to stats.rollbacks,
                "fallas a mitad" to stats.failures,
                "saldos negativos" to stats.negativeBalances,
                "cuentas en otra moneda" to stats.otherCurrencyAccounts,
                "cuentas archivadas" to stats.archived,
                "transferencias" to stats.transfers,
                "transferencias anuladas" to stats.transferVoids,
            )
            for ((path, count) in paths) assertTrue("Ningún caso de: $path", count > 0)
        }

    private suspend fun runSequence(seed: Long, stats: Stats) {
        val random = Random(seed)
        val world = MoneyWorld()
        world.settings.lockCurrency(MoneyWorld.NOW)
        repeat(random.nextInt(1, 4)) { index ->
            val foreign = random.nextInt(3) == 0
            if (foreign) stats.otherCurrencyAccounts++
            world.save()(
                null,
                MoneyAccountDraft(
                    name = "Cuenta $index",
                    kind = MoneyAccountKind.entries.random(random),
                    currency = if (foreign) MoneyWorld.OTHER else null,
                    openingBalance = Money(random.nextLong(-100_000, 100_000)),
                ),
            )
        }
        repeat(random.nextInt(MIN_STEPS, MAX_STEPS)) { step ->
            val before = world.database.tables.value
            try {
                step(world, random, stats)
            } catch (failure: AssertionError) {
                throw AssertionError("Semilla $seed, paso $step: ${failure.message}", failure)
            }
            checkLedger(world, before, seed, step)
        }
    }

    private suspend fun step(world: MoneyWorld, random: Random, stats: Stats) {
        val tables = world.database.tables.value
        val accountId = tables.moneyAccounts.keys.random(random)
        when (random.nextInt(12)) {
            in 0..3 -> {
                val result =
                    write(world) {
                        world.writer.append(randomDraft(accountId, random), TIME, MoneyWorld.NOW)
                    }
                if (result is MoneyAppendResult.Appended) stats.appended++
            }

            4 -> {
                val target = tables.moneyMovements.values.randomOrNull(random) ?: return
                when (world.void()(target.id)) {
                    is VoidMoneyMovementResult.Voided -> stats.voids++
                    VoidMoneyMovementResult.AlreadyVoided -> stats.repeatedVoids++
                    VoidMoneyMovementResult.FromDocument -> stats.documentVoids++
                    else -> Unit
                }
            }

            5 -> {
                val huge = if (random.nextBoolean()) Long.MAX_VALUE else -Long.MAX_VALUE
                val draft =
                    MoneyMovementDraft(
                        accountId,
                        MoneyMovementType.OPENING_BALANCE,
                        Money(huge),
                        MoneyOrigin.None,
                    )
                val first = write(world) { world.writer.append(draft, TIME, MoneyWorld.NOW) }
                val second = write(world) { world.writer.append(draft, TIME, MoneyWorld.NOW) }
                if (first == MoneyAppendResult.Overflow ||
                    second == MoneyAppendResult.Overflow
                ) {
                    stats.overflows++
                }
                // Vuelve a un saldo pequeño anulando lo que entró: anulado, ya no se anula otra vez.
                for (result in listOf(second, first)) {
                    if (result is MoneyAppendResult.Appended) {
                        val movement = requireNotNull(world.ledger.findById(result.movementId))
                        write(world) { world.writer.void(movement, TIME, MoneyWorld.NOW) }
                    }
                }
            }

            6 -> {
                world.transactions.write {
                    world.writer.append(randomDraft(accountId, random), TIME, MoneyWorld.NOW)
                    TxResult.Rollback(Unit)
                }
                stats.rollbacks++
            }

            7 -> {
                world.database.failAtWrite = 2
                try {
                    write(world) {
                        world.writer.append(randomDraft(accountId, random), TIME, MoneyWorld.NOW)
                    }
                    fail("La falla inyectada no llegó")
                } catch (_: InjectedFailure) {
                    stats.failures++
                } finally {
                    world.database.failAtWrite = null
                }
            }

            8 -> if (world.archive()(accountId) ==
                MoneyAccountLifecycleResult.Done
            ) {
                stats.archived++
            }

            9 -> world.reactivate()(accountId)

            else -> transferStep(world, random, stats, accountId)
        }
        if (world.database.tables.value.moneyAccounts.values.any { it.balance < Money.ZERO }) {
            stats.negativeBalances++
        }
    }

    /** Una transferencia al azar, a veces entre monedas, o la anulación de una que ya existe. */
    private suspend fun transferStep(world: MoneyWorld, random: Random, stats: Stats, from: Long) {
        val tables = world.database.tables.value
        val part = tables.moneyMovements.values.filter { it.draft.origin is MoneyOrigin.Transfer }
        if (part.isNotEmpty() && random.nextInt(3) == 0) {
            if (world.void()(
                    part.random(random).id,
                ) is VoidMoneyMovementResult.Voided
            ) {
                stats.transferVoids++
            }
            return
        }
        val to = tables.moneyAccounts.keys.random(random)
        val result =
            TransferMoneyUseCase(
                world.transactions,
                world.settings,
                world.accounts,
                world.writer,
                world.clock,
            )(
                TransferRequest(
                    from,
                    to,
                    Money(random.nextLong(1, 500_000)),
                    fxRateMicro = random.nextLong(1, 10_000_000_000),
                ),
            )
        if (result is TransferMoneyResult.Transferred) stats.transfers++
    }

    private suspend fun write(
        world: MoneyWorld,
        block: suspend () -> MoneyAppendResult,
    ): MoneyAppendResult = world.transactions.write {
        when (val result = block()) {
            is MoneyAppendResult.Appended -> TxResult.Commit(result)
            else -> TxResult.Rollback(result)
        }
    }

    private fun randomDraft(accountId: Long, random: Random): MoneyMovementDraft {
        val type = TYPES.random(random)
        val magnitude = random.nextLong(1, 1_000_000)
        val amount = when (type) {
            MoneyMovementType.OPENING_BALANCE, MoneyMovementType.CASH_DIFFERENCE ->
                if (random.nextBoolean()) magnitude else -magnitude

            else -> if (type.allows(magnitude)) magnitude else -magnitude
        }
        val documentId = random.nextLong(1, 50)
        val origin = when (type) {
            MoneyMovementType.SALE_RECEIPT, MoneyMovementType.FEE -> MoneyOrigin.SalePayment(
                documentId,
            )

            MoneyMovementType.CUSTOMER_PAYMENT -> MoneyOrigin.CustomerPayment(documentId)

            MoneyMovementType.SUPPLIER_PAYMENT -> MoneyOrigin.SupplierPayment(documentId)

            MoneyMovementType.EXPENSE_PAYMENT -> MoneyOrigin.Expense(documentId)

            MoneyMovementType.PURCHASE_PAYMENT -> MoneyOrigin.Purchase(documentId)

            MoneyMovementType.REFUND -> MoneyOrigin.SaleReturn(documentId)

            MoneyMovementType.SUPPLIER_REFUND -> MoneyOrigin.PurchaseReturn(documentId)

            else -> MoneyOrigin.None
        }
        val session = if (type == MoneyMovementType.CASH_DIFFERENCE) documentId else null
        return MoneyMovementDraft(accountId, type, Money(amount), origin, cashSessionId = session)
    }

    /** Los oráculos, escritos aquí: sumas y cadenas recorridas a mano. */
    private suspend fun checkLedger(world: MoneyWorld, before: Tables, seed: Long, step: Int) {
        val after = world.database.tables.value
        val where = "semilla $seed, paso $step"
        for ((id, movement) in before.moneyMovements) {
            assertEquals(
                "Un movimiento cambió o desapareció, $where",
                movement,
                after.moneyMovements[id],
            )
        }
        // Cada grupo de transferencia tiene exactamente una parte que sale y una que entra.
        val groups = after.moneyMovements.values
            .mapNotNull { movement ->
                (movement.draft.origin as? MoneyOrigin.Transfer)?.let {
                    it.groupId to
                        movement.type
                }
            }
            .groupBy({ it.first }, { it.second })
        for ((group, types) in groups) {
            assertEquals(
                "Transferencia incompleta $group, $where",
                listOf(MoneyMovementType.TRANSFER_OUT, MoneyMovementType.TRANSFER_IN).sorted(),
                types.sorted(),
            )
        }
        val running = mutableMapOf<Long, Long>()
        for (movement in after.moneyMovements.values.sortedBy { it.id }) {
            val balance = (running[movement.accountId] ?: 0L) + movement.amount.minor
            running[movement.accountId] = balance
            assertEquals(
                "Cadena rota en ${movement.id}, $where",
                balance,
                movement.balanceAfter.minor,
            )
        }
        for ((id, account) in after.moneyAccounts) {
            assertEquals(
                "Saldo distinto del libro en $id, $where",
                running[id] ?: 0L,
                account.balance.minor,
            )
        }
        if (after.moneyMovements.size == before.moneyMovements.size) {
            assertEquals(
                "Un rechazo dejó rastro, $where",
                before.moneyAccounts.mapValues { it.value.balance },
                after.moneyAccounts.mapValues { it.value.balance },
            )
        }
        // Con exactitud: una suma que desborda se muestra sin valor, no da la vuelta.
        val expected: Long? = try {
            after.moneyAccounts.values
                .filter {
                    !it.isArchived && it.currency.code == BOOK_CODE &&
                        it.currency.decimals == BOOK_DECIMALS
                }
                .fold(0L) { total, account -> Math.addExact(total, account.balance.minor) }
        } catch (_: ArithmeticException) {
            null
        }
        val overview = world.overview()().first()
        assertEquals("Disponible mal sumado, $where", expected, overview.available?.minor)
        assertTrue(
            "Una cuenta de otra moneda entró al total, $where",
            overview.inBookCurrency.none { it.currency != MoneyWorld.BOOK },
        )
    }

    private companion object {
        const val SEQUENCES = 300
        const val BASE_SEED = 20_261_112L
        const val MIN_STEPS = 10
        const val MAX_STEPS = 40
        val BOOK_CODE = MoneyWorld.BOOK.code
        val BOOK_DECIMALS = MoneyWorld.BOOK.decimals
        val TIME = RecordTime(MoneyWorld.NOW, requireNotNull(BusinessDate.parse("2026-03-15")))

        /** Los tipos que no son parte de una transferencia ni anulación: esos tienen su propio paso. */
        val TYPES = MoneyMovementType.entries.filter {
            it != MoneyMovementType.MOVEMENT_VOID &&
                it != MoneyMovementType.TRANSFER_OUT &&
                it != MoneyMovementType.TRANSFER_IN
        }
    }
}
