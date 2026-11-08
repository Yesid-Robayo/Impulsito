package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodInputError
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.testing.FakePaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.testing.Tables
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Crear, editar, ordenar, archivar, reactivar y eliminar métodos de pago, sobre los seis iniciales.
 *
 * Cada caso de uso abre una sola transacción. Un rechazo no deja nada ni cuenta un cambio, y una
 * falla en cualquiera de sus escrituras tampoco. Los nombres y las comisiones son inventados, y
 * ninguno es una marca.
 */
class PaymentMethodUseCasesTest {
    private val seededAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val now = Instant.fromEpochMilliseconds(1_700_000_600_000L)
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val methods = FakePaymentMethodRepository(database)
    private val clock = FixedClock(now)

    private val create = CreatePaymentMethodUseCase(transactions, methods, clock)
    private val edit = EditPaymentMethodUseCase(transactions, methods, clock)
    private val reorder = ReorderPaymentMethodsUseCase(transactions, methods, clock)
    private val archive = ArchivePaymentMethodUseCase(transactions, methods, clock)
    private val reactivate = ReactivatePaymentMethodUseCase(transactions, methods, clock)
    private val delete = DeletePaymentMethodUseCase(transactions, methods)
    private val observe = ObservePaymentMethodsUseCase(methods)
    private val options = LoadPaymentMethodOptionsUseCase(transactions, methods)

    private val wallet = PaymentMethodDraft(
        name = "Billetera del barrio",
        kind = PaymentMethodKind.WALLET,
        feeRate = BasisPoints(150),
        feeFixed = Money.ZERO,
        settlementDays = 1,
        priceAdjustment = BasisPoints.ZERO,
        moneyAccountId = null,
        instructionsText = null,
    )

    @Before
    fun seed() {
        methods.seedInitialMethods(seededAt)
    }

    @Test
    fun `the six initial methods are generic, ordered and without names or fees`() = runTest {
        val active = observe(ArchiveFilter.ACTIVE).first()

        assertEquals(SeedTable.PAYMENT_METHODS.systemKeys, active.map { it.systemKey })
        assertEquals(
            SeedTable.PAYMENT_METHODS.systemKeys,
            active.map { it.details.kind.name },
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), active.map { it.sortOrder })
        assertEquals(setOf(null), active.map { it.details.name }.toSet())
        assertEquals(setOf(BasisPoints.ZERO), active.map { it.details.feeRate }.toSet())
        assertEquals(emptyList<PaymentMethodRecord>(), observe(ArchiveFilter.ARCHIVED).first())
    }

    @Test
    fun `each initial method is edited, archived and reactivated without a brand`() = runTest {
        // Con todos vigentes, cada inicial se edita sin tocar su nombre traducido, se archiva y
        // vuelve: ninguno depende de una marca ni de su nombre.
        for (method in observe(ArchiveFilter.ACTIVE).first()) {
            val kind = method.details.kind
            val draft = wallet.copy(name = null, kind = kind, priceAdjustment = BasisPoints(100))

            assertEquals(SavePaymentMethodResult.Saved(method.id), edit(method.id, draft))
            val edited = requireNotNull(methods.findById(method.id))
            assertNull(edited.details.name)
            assertEquals(method.systemKey, edited.systemKey)
            assertEquals(kind, edited.details.kind)

            assertEquals(ArchivePaymentMethodResult.Archived, archive(method.id))
            assertEquals(ReactivatePaymentMethodResult.Reactivated, reactivate(method.id))
        }
        assertEquals(6 * 3, transactions.commits)
        assertEquals(0, transactions.rollbacks)
    }

    @Test
    fun `a new method goes last, after the archived ones too, and counts as a change`() = runTest {
        archive(6L)
        val before = database.tables.value.changeSeq

        val result = create(wallet)

        assertEquals(SavePaymentMethodResult.Saved(7L), result)
        val created = requireNotNull(methods.findById(7L))
        assertEquals(7L, created.sortOrder)
        assertNull(created.systemKey)
        assertEquals("billetera del barrio", created.details.normalizedName)
        assertEquals(now, created.createdAt)
        assertEquals(before + 1, database.tables.value.changeSeq)
        assertEquals(
            PaymentMethodKind.WALLET,
            observe(ArchiveFilter.ACTIVE).first().last().details.kind,
        )
    }

    @Test
    fun `the first method of an empty list goes first`() = runTest {
        for (id in 1L..6L) database.forget(id)

        assertEquals(SavePaymentMethodResult.Saved(7L), create(wallet))
        assertEquals(1L, methods.findById(7L)?.sortOrder)
    }

    @Test
    fun `a name of another active method is refused, but not one of an archived method`() =
        runTest {
            create(wallet)
            val taken = create(wallet.copy(name = "  BILLETERA del Barrio "))
            assertEquals(invalid(PaymentMethodInputError.NAME_TAKEN), taken)

            archive(7L)
            assertEquals(SavePaymentMethodResult.Saved(8L), create(wallet))
            // El archivado no puede volver mientras otro vigente tenga su nombre.
            assertEquals(ReactivatePaymentMethodResult.NameTaken, reactivate(7L))
            assertEquals(true, methods.findById(7L)?.isArchived)
        }

    @Test
    fun `field errors and a taken name come together, and nothing is written`() = runTest {
        create(wallet)
        val before = database.tables.value

        val result = create(wallet.copy(feeRate = BasisPoints(-1), moneyAccountId = 9L))

        assertEquals(
            SavePaymentMethodResult.Invalid(
                setOf(
                    PaymentMethodInputError.FEE_RATE_OUT_OF_RANGE,
                    PaymentMethodInputError.NAME_TAKEN,
                ),
            ),
            result,
        )
        assertEquals(before, database.tables.value)
    }

    @Test
    fun `a new money account must be active, and the current one can stay`() = runTest {
        methods.activeMoneyAccounts += 4L
        assertEquals(
            invalid(PaymentMethodInputError.MONEY_ACCOUNT_UNAVAILABLE),
            create(wallet.copy(moneyAccountId = 5L)),
        )
        assertEquals(SavePaymentMethodResult.Saved(7L), create(wallet.copy(moneyAccountId = 4L)))

        // La cuenta se archivó después: el método se sigue editando con ella.
        methods.activeMoneyAccounts -= 4L
        val renamed = wallet.copy(name = "Billetera", moneyAccountId = 4L)
        assertEquals(SavePaymentMethodResult.Saved(7L), edit(7L, renamed))
        assertEquals(
            invalid(PaymentMethodInputError.MONEY_ACCOUNT_UNAVAILABLE),
            edit(7L, renamed.copy(moneyAccountId = 5L)),
        )
    }

    @Test
    fun `editing keeps the key, the order, the archive and the instructions image`() = runTest {
        val card = requireNotNull(methods.findById(2L))
        database.put(card.copy(details = card.details.copy(instructionsMediaId = 40L)))

        val result =
            edit(2L, wallet.copy(name = "Tarjeta del local", kind = PaymentMethodKind.CARD))

        assertEquals(SavePaymentMethodResult.Saved(2L), result)
        val edited = requireNotNull(methods.findById(2L))
        assertEquals("CARD", edited.systemKey)
        assertEquals("Tarjeta del local", edited.details.name)
        assertEquals(40L, edited.details.instructionsMediaId)
        assertEquals(2L, edited.sortOrder)
        assertEquals(seededAt, edited.createdAt)
        assertEquals(now, edited.updatedAt)
    }

    @Test
    fun `saving without changes writes nothing and counts nothing`() = runTest {
        val cash = requireNotNull(methods.findById(1L))
        val same = PaymentMethodDraft(
            name = null,
            kind = cash.details.kind,
            feeRate = cash.details.feeRate,
            feeFixed = cash.details.feeFixed,
            settlementDays = cash.details.settlementDays,
            priceAdjustment = cash.details.priceAdjustment,
            moneyAccountId = null,
            instructionsText = "   ",
        )
        val before = database.tables.value

        assertEquals(SavePaymentMethodResult.Saved(1L), edit(1L, same))

        assertEquals(before, database.tables.value)
        assertEquals(1, transactions.rollbacks)
    }

    @Test
    fun `a method of the person cannot lose its name, and an initial one can be renamed`() =
        runTest {
            create(wallet)
            assertEquals(
                invalid(PaymentMethodInputError.NAME_BLANK),
                edit(7L, wallet.copy(name = null)),
            )
            assertEquals(SavePaymentMethodResult.Saved(1L), edit(1L, wallet.copy(name = "Caja")))
            assertEquals("Caja", methods.findById(1L)?.details?.name)
        }

    @Test
    fun `an archived method is edited without comparing its name`() = runTest {
        create(wallet)
        archive(7L)
        create(wallet)

        val result = edit(7L, wallet.copy(feeRate = BasisPoints(175)))

        assertEquals(SavePaymentMethodResult.Saved(7L), result)
        assertEquals(BasisPoints(175), methods.findById(7L)?.details?.feeRate)
    }

    @Test
    fun `the kind of a method with sales is locked, the rest is still editable`() = runTest {
        methods.methodsWithSales += 3L
        val transfer = wallet.copy(
            name = "Transferencia del banco",
            kind = PaymentMethodKind.TRANSFER,
        )

        assertEquals(
            invalid(PaymentMethodInputError.KIND_LOCKED),
            edit(3L, transfer.copy(kind = PaymentMethodKind.CARD)),
        )
        assertEquals(SavePaymentMethodResult.Saved(3L), edit(3L, transfer))
        // Sin ventas, el tipo sí cambia.
        assertEquals(
            SavePaymentMethodResult.Saved(4L),
            edit(4L, wallet.copy(name = null, kind = PaymentMethodKind.OTHER)),
        )
        assertEquals(PaymentMethodKind.OTHER, methods.findById(4L)?.details?.kind)
    }

    @Test
    fun `a missing method is reported, and nothing is written`() = runTest {
        val before = database.tables.value

        assertEquals(SavePaymentMethodResult.NotFound, edit(99L, wallet))
        assertEquals(ArchivePaymentMethodResult.NotFound, archive(99L))
        assertEquals(ReactivatePaymentMethodResult.NotFound, reactivate(99L))
        assertEquals(DeletePaymentMethodResult.NotFound, delete(99L))
        assertNull(options(99L))

        assertEquals(before, database.tables.value)
        assertEquals(0, transactions.commits)
    }

    @Test
    fun `there is always one active method`() = runTest {
        for (id in 1L..5L) assertEquals(ArchivePaymentMethodResult.Archived, archive(id))

        assertEquals(ArchivePaymentMethodResult.LastActive, archive(6L))
        assertEquals(DeletePaymentMethodResult.LastActive, delete(6L))
        assertEquals(true, options(6L)?.isLastActive)
        // Un archivado sin ventas sí se elimina, aunque quede un solo vigente.
        assertEquals(DeletePaymentMethodResult.Deleted, delete(1L))
        assertEquals(listOf(6L), observe(ArchiveFilter.ACTIVE).first().map { it.id })
    }

    @Test
    fun `archiving twice and reactivating an active method change nothing`() = runTest {
        archive(2L)
        val before = database.tables.value

        assertEquals(ArchivePaymentMethodResult.AlreadyArchived, archive(2L))
        assertEquals(ReactivatePaymentMethodResult.NotArchived, reactivate(1L))

        assertEquals(before, database.tables.value)
    }

    @Test
    fun `archiving keeps the method, its order and its sales, and reactivating brings it back`() =
        runTest {
            methods.methodsWithSales += 2L

            assertEquals(ArchivePaymentMethodResult.Archived, archive(2L))

            val archived = observe(ArchiveFilter.ARCHIVED).first().single()
            assertEquals(now, archived.archivedAt)
            assertEquals(2L, archived.sortOrder)
            assertEquals(
                listOf(1L, 3L, 4L, 5L, 6L),
                observe(ArchiveFilter.ACTIVE).first().map {
                    it.id
                },
            )
            assertEquals(ReactivatePaymentMethodResult.Reactivated, reactivate(2L))
            assertEquals(
                listOf(1L, 2L, 3L, 4L, 5L, 6L),
                observe(ArchiveFilter.ACTIVE).first().map {
                    it.id
                },
            )
        }

    @Test
    fun `a method with sales is never deleted, even when archived`() = runTest {
        methods.methodsWithSales += 4L
        archive(4L)
        val before = database.tables.value

        assertEquals(DeletePaymentMethodResult.HasSalePayments, delete(4L))
        assertEquals(before, database.tables.value)
        assertEquals(DeletePaymentMethodResult.Deleted, delete(5L))
        assertNull(methods.findById(5L))
    }

    @Test
    fun `the options say what the screen offers`() = runTest {
        methods.methodsWithSales += 1L

        val cash = requireNotNull(options(1L))
        val card = requireNotNull(options(2L))

        assertEquals(6, cash.activeCount)
        assertEquals(true, cash.isKindLocked)
        assertEquals(false, cash.canDelete)
        assertEquals(true, card.canDelete)
        assertEquals(2, transactions.readCalls)
    }

    @Test
    fun `reordering writes only the moved methods, all in one transaction`() = runTest {
        val result = reorder(listOf(3L, 1L, 2L, 4L, 5L, 6L))

        assertEquals(ReorderPaymentMethodsResult.Reordered, result)
        assertEquals(
            listOf(3L, 1L, 2L, 4L, 5L, 6L),
            observe(ArchiveFilter.ACTIVE).first().map {
                it.id
            },
        )
        assertEquals(3, database.lastTransactionWrites)
        assertEquals(1, transactions.writeCalls)
        assertEquals(now, methods.findById(3L)?.updatedAt)
        assertEquals(seededAt, methods.findById(4L)?.updatedAt)
    }

    @Test
    fun `reordering skips the archived ones and keeps their place`() = runTest {
        archive(1L)

        assertEquals(ReorderPaymentMethodsResult.Reordered, reorder(listOf(6L, 2L, 3L, 4L, 5L)))

        assertEquals(1L, methods.findById(1L)?.sortOrder)
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L),
            listOf(6L, 2L, 3L, 4L, 5L).map {
                methods.findById(it)?.sortOrder
            },
        )
    }

    @Test
    fun `the same order writes nothing`() = runTest {
        val before = database.tables.value

        assertEquals(ReorderPaymentMethodsResult.Unchanged, reorder(listOf(1L, 2L, 3L, 4L, 5L, 6L)))

        assertEquals(before, database.tables.value)
    }

    @Test
    fun `an order that is not exactly the active methods is refused`() = runTest {
        val before = database.tables.value
        val stale = listOf(
            listOf(1L, 2L, 3L, 4L, 5L),
            listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L),
            listOf(1L, 1L, 3L, 4L, 5L, 6L),
            listOf(1L, 2L, 3L, 4L, 5L, 9L),
        )

        for (ids in stale) assertEquals(ReorderPaymentMethodsResult.ListChanged, reorder(ids))

        assertEquals(before, database.tables.value)
        assertEquals(0, transactions.commits)
    }

    @Test
    fun `a failure at any write leaves nothing`() = runTest {
        val operations: Map<String, suspend () -> Any> = mapOf(
            "crear" to { create(wallet) },
            "editar" to { edit(2L, wallet) },
            "ordenar" to { reorder(listOf(6L, 5L, 4L, 3L, 2L, 1L)) },
            "archivar" to { archive(2L) },
            "eliminar" to { delete(2L) },
        )
        for ((name, operation) in operations) {
            var write = 1
            while (true) {
                val before = database.tables.value
                database.failAtWrite = write
                val failed = runCatchingInjected { operation() }
                database.failAtWrite = null
                if (!failed) {
                    // Terminó antes de llegar a esa escritura: ya se probaron todas. Se deshace a
                    // mano para la operación siguiente.
                    restore(before)
                    break
                }
                assertEquals("$name, escritura $write", before, database.tables.value)
                write++
            }
        }
    }

    @Test
    fun `a failure while reactivating leaves the method archived`() = runTest {
        archive(2L)
        val before = database.tables.value
        database.failAtWrite = 1

        assertEquals(true, runCatchingInjected { reactivate(2L) })

        assertEquals(before, database.tables.value)
    }

    @Test
    fun `a storage failure becomes the result of each use case`() = runTest {
        database.failAtWrite = 1
        database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }
        val full = StorageFailure.DISK_FULL

        assertEquals(SavePaymentMethodResult.StorageFailed(full), create(wallet))
        assertEquals(SavePaymentMethodResult.StorageFailed(full), edit(2L, wallet))
        assertEquals(
            ReorderPaymentMethodsResult.StorageFailed(full),
            reorder(listOf(2L, 1L, 3L, 4L, 5L, 6L)),
        )
        assertEquals(ArchivePaymentMethodResult.StorageFailed(full), archive(2L))
        assertEquals(DeletePaymentMethodResult.StorageFailed(full), delete(2L))
        database.failAtWrite = null
        archive(3L)
        database.failAtWrite = 1
        assertEquals(ReactivatePaymentMethodResult.StorageFailed(full), reactivate(3L))
    }

    @Test
    fun `a restore in progress refuses every write before it starts`() = runTest {
        transactions.restoreInProgress = true
        val before = database.tables.value
        val restoring = StorageFailure.RESTORE_IN_PROGRESS

        assertEquals(SavePaymentMethodResult.StorageFailed(restoring), create(wallet))
        assertEquals(ArchivePaymentMethodResult.StorageFailed(restoring), archive(2L))
        assertEquals(ReactivatePaymentMethodResult.StorageFailed(restoring), reactivate(2L))
        assertEquals(DeletePaymentMethodResult.StorageFailed(restoring), delete(2L))
        assertEquals(ReorderPaymentMethodsResult.StorageFailed(restoring), reorder(emptyList()))

        assertEquals(before, database.tables.value)
    }

    private fun invalid(error: PaymentMethodInputError) =
        SavePaymentMethodResult.Invalid(setOf(error))

    /** Corre [block] y dice si terminó con la falla inyectada. */
    private suspend fun runCatchingInjected(block: suspend () -> Any): Boolean = try {
        block()
        false
    } catch (expected: InjectedFailure) {
        true
    }

    /** Vuelve la base a [tables] con una transacción que no cuenta como cambio. */
    private fun restore(tables: Tables) {
        database.begin()
        database.write { tables }
        database.commit(counted = false)
    }

    private fun InMemoryDatabase.put(method: PaymentMethodRecord) {
        begin()
        write { it.copy(paymentMethods = it.paymentMethods + (method.id to method)) }
        commit(counted = false)
    }

    private fun InMemoryDatabase.forget(id: Long) {
        begin()
        write { it.copy(paymentMethods = it.paymentMethods - id) }
        commit(counted = false)
    }
}
