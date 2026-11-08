package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaGuards
import io.github.yesidrobayo.shopledger.data.local.money.moneyAccount
import io.github.yesidrobayo.shopledger.data.local.payment.cardPayment
import io.github.yesidrobayo.shopledger.data.local.payment.paymentMethod
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ArchivePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ArchivePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.DeletePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.payment.DeletePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.EditPaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReactivatePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReactivatePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReorderPaymentMethodsResult
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReorderPaymentMethodsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SavePaymentMethodResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los métodos de pago sobre la base real: los seis iniciales funcionan con los casos de uso sin
 * nombre ni marca, cada campo vuelve como se guardó, un tipo desconocido se lee como otro medio, y
 * un método con ventas no se borra. Los nombres y las comisiones son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PaymentMethodRepositoryTest : RepositoryTest() {
    private val methods by lazy { RoomPaymentMethodRepository(database) }
    private val media by lazy { RoomMediaAssetRepository(database) }
    private val clock = Clock { LATER_AT }

    @Test
    fun theSixInitialMethodsWorkWithTheUseCasesWithoutBrands() = runBlocking {
        val edit = EditPaymentMethodUseCase(runner, methods, media, clock)
        val archive = ArchivePaymentMethodUseCase(runner, methods, clock)
        val reactivate = ReactivatePaymentMethodUseCase(runner, methods, clock)
        val reorder = ReorderPaymentMethodsUseCase(runner, methods, clock)
        val seeded = methods.observe(ArchiveFilter.ACTIVE).first()
        val before = changeSeq()
        assertEquals(SeedTable.PAYMENT_METHODS.systemKeys, seeded.map { it.systemKey })
        assertEquals(SeedTable.PAYMENT_METHODS.systemKeys, seeded.map { it.details.kind.name })
        assertEquals(setOf(null), seeded.map { it.details.name }.toSet())

        for (method in seeded) {
            // Sin tocar el nombre: sigue sin nombre propio, y se ve traducido.
            val draft = draft(name = null, kind = method.details.kind, adjustment = 100)
            assertEquals(SavePaymentMethodResult.Saved(method.id), edit(method.id, draft))
            assertNull(methods.findById(method.id)?.details?.name)
            assertEquals(ArchivePaymentMethodResult.Archived, archive(method.id))
            assertEquals(ReactivatePaymentMethodResult.Reactivated, reactivate(method.id))
        }
        val reversed = seeded.map { it.id }.reversed()
        assertEquals(ReorderPaymentMethodsResult.Reordered, reorder(reversed))

        assertEquals(reversed, methods.findActive().map { it.id })
        // Editar, archivar y reactivar cada uno, y un orden: cada uno cuenta para la copia.
        assertEquals(before + 3L * seeded.size + 1, changeSeq())
    }

    @Test
    fun everyFieldComesBackAndTheListsEmitEachChange() = runBlocking {
        val accountId = database.moneyAccountDao().insert(moneyAccount("Cuenta del local"))
        methods.observe(ArchiveFilter.ARCHIVED).test {
            assertEquals(emptyList<PaymentMethodRecord>(), awaitItem())

            val details = details("Vale de regalo", accountId = accountId)
            val id = commit { methods.insert(details, sortOrder = 9, createdAt = AT) }
            val created = PaymentMethodRecord(id, null, details, 9, null, AT, AT)
            assertEquals(created, methods.findById(id))
            assertEquals(created, methods.findActiveByNormalizedName("vale de regalo"))
            assertEquals(9L, methods.maxSortOrder())

            val changed = details.copy(kind = PaymentMethodKind.CARD, settlementDays = 3_650)
            commit { methods.update(id, changed, LATER_AT) }
            commit { methods.setSortOrder(id, 2, LATER_AT) }
            commit { methods.setArchivedAt(id, LATER_AT, LATER_AT) }

            val archived = PaymentMethodRecord(id, null, changed, 2, LATER_AT, AT, LATER_AT)
            awaitValue(listOf(archived))
            assertNull(methods.findActiveByNormalizedName("vale de regalo"))
            assertFalse(id in methods.findActive().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun anUnknownStoredKindIsReadAsOtherAndSavedAsOther() = runBlocking {
        // Una copia de una versión más nueva puede traer un medio que esta no conoce. Se simula sin
        // la guarda de inserción, que la restauración no vuelve a correr sobre filas guardadas.
        val insertGuard = SchemaGuards.named("guard_payment_methods_insert")
        execute("DROP TRIGGER ${insertGuard.name}")
        val id = database.paymentMethodDao().insert(paymentMethod("Cupón", kind = "VOUCHER"))
        execute(insertGuard.sql)

        val method = requireNotNull(methods.findById(id))
        assertEquals(PaymentMethodKind.OTHER, method.details.kind)
        val edit = EditPaymentMethodUseCase(runner, methods, media, clock)
        assertEquals(
            SavePaymentMethodResult.Saved(id),
            edit(id, draft("Cupón", PaymentMethodKind.OTHER, adjustment = 0, feeRate = 100)),
        )
        assertEquals("OTHER", database.paymentMethodDao().findById(id)?.kind)
    }

    @Test
    fun aMethodWithSalesIsNotDeletedButIsArchived() = runBlocking {
        val id = commit { methods.insert(details("Tarjeta del local"), 7, AT) }
        val saleId = database.saleDao().insert(sale(priceListId))
        assertFalse(methods.hasSalePayments(id))

        database.salePaymentDao().insert(cardPayment(saleId, id))

        assertTrue(methods.hasSalePayments(id))
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf { commit { methods.delete(id) } },
        )
        val delete = DeletePaymentMethodUseCase(runner, methods, media, clock)
        assertEquals(DeletePaymentMethodResult.HasSalePayments, delete(id))
        // Renombrarlo y archivarlo no cambia lo que su venta guardó del método.
        val edit = EditPaymentMethodUseCase(runner, methods, media, clock)
        val renamed = draft("Tarjeta nueva", PaymentMethodKind.WALLET, adjustment = 0)
        assertEquals(SavePaymentMethodResult.Saved(id), edit(id, renamed))
        val archive = ArchivePaymentMethodUseCase(runner, methods, clock)
        assertEquals(ArchivePaymentMethodResult.Archived, archive(id))
        assertEquals(LATER_AT, methods.findById(id)?.archivedAt)
        val payment = database.salePaymentDao().findBySale(saleId).single()
        assertEquals("Tarjeta", payment.methodNameSnapshot)
        assertEquals("CARD", payment.methodKindSnapshot)
    }

    @Test
    fun aMethodWithoutSalesIsDeleted() = runBlocking {
        val id = commit { methods.insert(details("Bono"), 7, AT) }

        assertEquals(
            DeletePaymentMethodResult.Deleted,
            DeletePaymentMethodUseCase(runner, methods, media, clock)(id),
        )

        assertNull(methods.findById(id))
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf { commit { methods.delete(id) } },
        )
    }

    @Test
    fun onlyAnExistingActiveAccountIsAvailable() = runBlocking {
        val active = database.moneyAccountDao().insert(moneyAccount("Banco del barrio"))
        val archived = database.moneyAccountDao().insert(moneyAccount("Cuenta vieja"))
        database.moneyAccountDao().setArchivedAt(archived, archivedAt = 5, updatedAt = 5)

        assertTrue(methods.isActiveMoneyAccount(active))
        assertFalse(methods.isActiveMoneyAccount(archived))
        assertFalse(methods.isActiveMoneyAccount(9_999))
    }

    @Test
    fun theDatabaseRefusesWhatTheUseCaseChecksBefore() = runBlocking {
        commit { methods.insert(details("Vale"), 7, AT) }

        // El nombre repetido entre vigentes y una fila que no existe salen como regla rota.
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf { commit { methods.insert(details(" VALE "), 8, AT) } },
        )
        for (write in listOf<suspend () -> Unit>(
            { methods.update(9_999, details("Otro"), AT) },
            { methods.setSortOrder(9_999, 1, AT) },
            { methods.setArchivedAt(9_999, AT, AT) },
        )) {
            assertEquals(
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf { commit { write() } },
            )
        }
    }

    private fun details(name: String, accountId: Long? = null) = PaymentMethodDetails(
        name = name.trim(),
        normalizedName = TextNormalizer.normalize(name),
        kind = PaymentMethodKind.WALLET,
        feeRate = BasisPoints(275),
        feeFixed = Money(15),
        settlementDays = 2,
        priceAdjustment = BasisPoints(-500),
        moneyAccountId = accountId,
        instructionsText = "Confirmar en la otra app antes de entregar",
        instructionsMediaId = null,
    )

    private fun draft(
        name: String?,
        kind: PaymentMethodKind,
        adjustment: Long,
        feeRate: Long = 0,
    ) = PaymentMethodDraft(
        name = name,
        kind = kind,
        feeRate = BasisPoints(feeRate),
        feeFixed = Money.ZERO,
        settlementDays = 0,
        priceAdjustment = BasisPoints(adjustment),
        moneyAccountId = null,
        instructionsText = null,
    )
}
