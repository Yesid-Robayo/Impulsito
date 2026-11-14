package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.data.local.purchase
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.AttachmentTarget
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptPhoto
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.document.AttachReceiptPhotoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.AttachReceiptResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveReceiptPhotosUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.RemoveReceiptPhotoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.RemoveReceiptResult
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las fotos de comprobantes en una base real: se unen a un gasto o una compra activos con su
 * imagen en una sola transacción, anular el documento las conserva, y quitarlas marca la imagen
 * como sin uso solo si nada más la usa. Imágenes inventadas, sin archivo: la base no guarda rutas.
 */
@RunWith(AndroidJUnit4::class)
internal class ReceiptPhotosRepositoryTest : RepositoryTest() {
    private val attachments by lazy { RoomDocumentAttachmentRepository(database) }
    private val media by lazy { RoomMediaAssetRepository(database) }
    private val attach by lazy {
        AttachReceiptPhotoUseCase(runner, attachments, media, Clock { AT })
    }
    private val remove by lazy {
        RemoveReceiptPhotoUseCase(runner, attachments, media, Clock { AT })
    }
    private val observe by lazy { ObserveReceiptPhotosUseCase(attachments, media) }

    @Test
    fun aPhotoStaysWithItsExpenseAfterVoidingAndIsRemovedOnlyOnRequest() = runBlocking {
        val categoryId = database.expenseCategoryDao().insert(expenseCategory("Servicios"))
        val expenseId = database.expenseDao().insert(expense(categoryId))
        val target = AttachmentTarget.Expense(expenseId)
        val before = changeSeq()

        val attached = attach(target, IMAGE) as AttachReceiptResult.Attached

        assertEquals(before + 1, changeSeq())
        assertEquals(listOf(ReceiptPhoto(attached.attachmentId, IMAGE)), observe(target).first())
        val mediaRow = requireNotNull(database.mediaAssetDao().findBySha256(IMAGE.sha256))
        assertEquals("DOCUMENT_ATTACHMENT", mediaRow.kind)

        // Anular el gasto no toca su foto.
        database.expenseDao().markVoided(expenseId, LATER, "ENTRY_ERROR", null)
        assertEquals(1, observe(target).first().size)
        assertEquals(AttachReceiptResult.DocumentUnavailable, attach(target, OTHER_IMAGE))

        assertEquals(RemoveReceiptResult.Removed, remove(attached.attachmentId))

        assertEquals(emptyList<ReceiptPhoto>(), observe(target).first())
        assertEquals(
            AT.toEpochMilliseconds(),
            database.mediaAssetDao().findById(mediaRow.id)?.orphanedAt,
        )
    }

    @Test
    fun theSameImageOnAPurchaseAndAnExpenseIsOneFileAndStaysInUse() = runBlocking {
        val purchaseId = database.purchaseDao().insert(purchase(supplierId = null))
        val categoryId = database.expenseCategoryDao().insert(expenseCategory("Papelería"))
        val expenseId = database.expenseDao().insert(expense(categoryId))

        val onPurchase =
            attach(AttachmentTarget.Purchase(purchaseId), IMAGE) as AttachReceiptResult.Attached
        assertEquals(onPurchase, attach(AttachmentTarget.Purchase(purchaseId), IMAGE))
        attach(AttachmentTarget.Expense(expenseId), IMAGE)

        assertEquals(1, observe(AttachmentTarget.Purchase(purchaseId)).first().size)
        remove(onPurchase.attachmentId)

        val mediaRow = requireNotNull(database.mediaAssetDao().findBySha256(IMAGE.sha256))
        assertNull(mediaRow.orphanedAt)
        assertEquals(RemoveReceiptResult.NotFound, remove(onPurchase.attachmentId))
    }

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_793_000_000_000)
        val IMAGE = StoredImage("a".repeat(64), "image/webp", 1_600, 1_200, 250_000)
        val OTHER_IMAGE = StoredImage("b".repeat(64), "image/webp", 1_200, 1_600, 200_000)
    }
}
