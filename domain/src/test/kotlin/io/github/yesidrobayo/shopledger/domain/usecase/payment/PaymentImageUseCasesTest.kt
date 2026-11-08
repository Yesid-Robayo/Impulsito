package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.payment.InstructionsImageChange
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.testing.FakeMediaAssetRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * La imagen de cobro de un método, como su código QR: se anota al guardar, en la misma transacción
 * que el método, la misma imagen es una sola fila, y la que deja de usarse queda marcada para
 * borrarse más adelante.
 */
class PaymentImageUseCasesTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_600_000L)
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val methods = FakePaymentMethodRepository(database)
    private val media = FakeMediaAssetRepository(database)
    private val clock = FixedClock(now)
    private val create = CreatePaymentMethodUseCase(transactions, methods, media, clock)
    private val edit = EditPaymentMethodUseCase(transactions, methods, media, clock)
    private val delete = DeletePaymentMethodUseCase(transactions, methods, media, clock)

    private val qr = image('a')
    private val otherQr = image('b')
    private val draft = PaymentMethodDraft(
        name = "Transferencia del barrio",
        kind = PaymentMethodKind.TRANSFER,
        feeRate = BasisPoints.ZERO,
        feeFixed = Money.ZERO,
        settlementDays = 0,
        priceAdjustment = BasisPoints.ZERO,
        moneyAccountId = null,
        instructionsText = "Confirmar en la otra app",
    )

    @Before
    fun seed() {
        methods.seedInitialMethods(now)
    }

    @Test
    fun `a new method with an image records it in the same transaction`() = runTest {
        val result = create(draft.copy(image = InstructionsImageChange.Use(qr)))

        val id = (result as SavePaymentMethodResult.Saved).id
        val assetId = requireNotNull(methods.findById(id)?.details?.instructionsMediaId)
        val row = requireNotNull(database.tables.value.mediaAssets[assetId])
        assertEquals(qr, row.image)
        assertEquals(MediaKind.PAYMENT_QR, row.kind)
        assertNull(row.orphanedAt)
        assertEquals(1, transactions.commits)
    }

    @Test
    fun `the same image is a single row for two methods`() = runTest {
        create(draft.copy(image = InstructionsImageChange.Use(qr)))
        create(draft.copy(name = "Billetera", image = InstructionsImageChange.Use(qr)))

        assertEquals(1, database.tables.value.mediaAssets.size)
        assertEquals(
            setOf(1L),
            methods.findActive().mapNotNull { it.details.instructionsMediaId }.toSet(),
        )
    }

    @Test
    fun `replacing or removing an unused image marks it orphaned`() = runTest {
        create(draft.copy(image = InstructionsImageChange.Use(qr)))
        val methodId = 7L

        edit(methodId, draft.copy(image = InstructionsImageChange.Use(otherQr)))

        val first = requireNotNull(database.tables.value.mediaAssets[1L])
        assertEquals(now, first.orphanedAt)
        assertEquals(2L, methods.findById(methodId)?.details?.instructionsMediaId)

        edit(methodId, draft.copy(image = InstructionsImageChange.Remove))

        assertNull(methods.findById(methodId)?.details?.instructionsMediaId)
        assertEquals(now, database.tables.value.mediaAssets[2L]?.orphanedAt)
    }

    @Test
    fun `an image still used elsewhere is not orphaned, and a reused one comes back`() = runTest {
        create(draft.copy(image = InstructionsImageChange.Use(qr)))
        media.otherReferences += 1L

        edit(7L, draft.copy(image = InstructionsImageChange.Remove))
        assertNull(database.tables.value.mediaAssets[1L]?.orphanedAt)

        media.otherReferences -= 1L
        edit(7L, draft.copy(image = InstructionsImageChange.Use(otherQr)))
        edit(7L, draft.copy(image = InstructionsImageChange.Use(qr)))
        // La primera vuelve a usarse: deja de estar marcada.
        assertNull(database.tables.value.mediaAssets[1L]?.orphanedAt)
        assertEquals(now, database.tables.value.mediaAssets[2L]?.orphanedAt)
    }

    @Test
    fun `keeping the image writes nothing for it`() = runTest {
        create(draft.copy(image = InstructionsImageChange.Use(qr)))
        val before = database.tables.value

        assertEquals(SavePaymentMethodResult.Saved(7L), edit(7L, draft))

        assertEquals(before, database.tables.value)
    }

    @Test
    fun `deleting a method releases its image`() = runTest {
        create(draft.copy(image = InstructionsImageChange.Use(qr)))
        create(draft.copy(name = "Sin imagen"))

        assertEquals(DeletePaymentMethodResult.Deleted, delete(7L))
        assertEquals(DeletePaymentMethodResult.Deleted, delete(8L))

        assertEquals(now, database.tables.value.mediaAssets[1L]?.orphanedAt)
    }

    @Test
    fun `a failure while recording the image leaves nothing`() = runTest {
        val before = database.tables.value
        for (write in 1..3) {
            database.failAtWrite = write
            runCatchingAny { create(draft.copy(image = InstructionsImageChange.Use(qr))) }
            database.failAtWrite = null
            assertEquals("escritura $write", before, database.tables.value)
        }
    }

    @Test
    fun `the options and the list find each method image`() = runTest {
        create(draft.copy(image = InstructionsImageChange.Use(qr)))
        val options = LoadPaymentMethodOptionsUseCase(transactions, methods, media)
        val find = FindPaymentImagesUseCase(transactions, media)

        assertEquals(qr, options(7L)?.image)
        assertNull(options(1L)?.image)
        assertEquals(mapOf(7L to qr), find(methods.findActive()))
    }

    @Test
    fun `importing asks for a payment image`() = runTest {
        var asked: Pair<String, MediaKind>? = null
        val import = ImportPaymentImageUseCase { source, kind ->
            asked = source to kind
            ImageImport.Imported(qr)
        }

        assertEquals(ImageImport.Imported(qr), import("content://picker/1"))
        assertEquals("content://picker/1" to MediaKind.PAYMENT_QR, asked)
    }

    private suspend fun runCatchingAny(block: suspend () -> Unit) {
        try {
            block()
        } catch (expected: IllegalStateException) {
            // La falla inyectada: lo que importa es que no quede nada.
        }
    }

    private fun image(digit: Char) = StoredImage(
        sha256 = digit.toString().repeat(64),
        mimeType = "image/webp",
        widthPx = 640,
        heightPx = 480,
        sizeBytes = 20_000,
    )
}
