package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.payment.InstructionsImageChange
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.payment.CreatePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.DeletePaymentMethodResult
import io.github.yesidrobayo.shopledger.domain.usecase.payment.DeletePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SavePaymentMethodResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las filas de las imágenes sobre la base real: una imagen por contenido, su uso por un método de
 * pago y la marca de sin uso. Las sumas y medidas son inventadas.
 */
@RunWith(AndroidJUnit4::class)
internal class MediaAssetRepositoryTest : RepositoryTest() {
    private val media by lazy { RoomMediaAssetRepository(database) }
    private val methods by lazy { RoomPaymentMethodRepository(database) }
    private val clock = Clock { LATER_AT }
    private val qr = StoredImage("ab".repeat(32), "image/webp", 640, 640, 30_000)

    @Test
    fun anImageIsFoundByItsContentAndCannotRepeat() = runBlocking {
        val id = commit { media.insert(qr, MediaKind.PAYMENT_QR, AT) }

        assertEquals(id, media.findIdBySha256(qr.sha256))
        assertNull(media.findIdBySha256("cd".repeat(32)))
        assertFalse(media.isReferenced(id))
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf { commit { media.insert(qr, MediaKind.PRODUCT_PHOTO, AT) } },
        )
        commit { media.setOrphanedAt(id, LATER_AT) }
        assertEquals(
            LATER_AT.toEpochMilliseconds(),
            database.mediaAssetDao().findById(id)?.orphanedAt,
        )
    }

    @Test
    fun aMethodWithTheImageUsesItAndDeletingItReleasesIt() = runBlocking {
        val create = CreatePaymentMethodUseCase(runner, methods, media, clock)
        val delete = DeletePaymentMethodUseCase(runner, methods, media, clock)
        val draft =
            PaymentMethodDraft(
                name = "Transferencia del barrio",
                kind = PaymentMethodKind.TRANSFER,
                feeRate = BasisPoints.ZERO,
                feeFixed = Money.ZERO,
                settlementDays = 0,
                priceAdjustment = BasisPoints.ZERO,
                moneyAccountId = null,
                instructionsText = null,
                image = InstructionsImageChange.Use(qr),
            )

        val saved = create(draft) as SavePaymentMethodResult.Saved
        val imageId = requireNotNull(media.findIdBySha256(qr.sha256))
        assertEquals(imageId, methods.findById(saved.id)?.details?.instructionsMediaId)
        assertTrue(media.isReferenced(imageId))

        assertEquals(DeletePaymentMethodResult.Deleted, delete(saved.id))

        assertFalse(media.isReferenced(imageId))
        assertEquals(
            LATER_AT.toEpochMilliseconds(),
            database.mediaAssetDao().findById(imageId)?.orphanedAt,
        )
    }
}
