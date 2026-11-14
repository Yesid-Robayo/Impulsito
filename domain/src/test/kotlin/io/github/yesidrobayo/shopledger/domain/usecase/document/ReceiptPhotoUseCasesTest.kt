package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.AttachmentTarget
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentAttachment
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptPhoto
import io.github.yesidrobayo.shopledger.domain.port.DocumentAttachmentRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.testing.FakeMediaAssetRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Las fotos de comprobantes: se traen al tamaño de un comprobante, se unen solo a un documento
 * activo en una sola transacción, la misma foto dos veces es una, y solo se quitan por decisión de
 * la persona, también en un documento anulado. Imágenes inventadas.
 */
class ReceiptPhotoUseCasesTest {
    private val database = InMemoryDatabase()
    private val media = FakeMediaAssetRepository(database)
    private val attachments = FakeAttachments(media)
    private val runner = AttachmentRunner(database, attachments)
    private val clock = FixedClock(NOW)
    private val attach = AttachReceiptPhotoUseCase(runner, attachments, media, clock)
    private val remove = RemoveReceiptPhotoUseCase(runner, attachments, media, clock)
    private val observe = ObserveReceiptPhotosUseCase(attachments, media)

    init {
        attachments.documents[EXPENSE] = DocumentStatus.ACTIVE
        attachments.documents[PURCHASE] = DocumentStatus.ACTIVE
        attachments.documents[VOIDED] = DocumentStatus.VOIDED
    }

    @Test
    fun `the photo is imported at the size of a receipt`() = runTest {
        val kinds = mutableListOf<MediaKind>()
        val import = ImportReceiptPhotoUseCase { _, kind ->
            kinds += kind
            ImageImport.Unreadable
        }

        assertEquals(ImageImport.Unreadable, import("content://example/1"))
        assertEquals(listOf(MediaKind.DOCUMENT_ATTACHMENT), kinds)
    }

    @Test
    fun `attaching records the image and the attachment in one transaction`() = runTest {
        val result = attach(EXPENSE, IMAGE)

        val id = (result as AttachReceiptResult.Attached).attachmentId
        assertEquals(listOf(ReceiptPhoto(id, IMAGE)), observe(EXPENSE).first())
        val row = database.tables.value.mediaAssets.values.single()
        assertEquals(MediaKind.DOCUMENT_ATTACHMENT, row.kind)
        assertEquals(1, runner.inner.commits)
        assertEquals(emptyList<ReceiptPhoto>(), observe(PURCHASE).first())
    }

    @Test
    fun `the same photo twice in the same document is one`() = runTest {
        val first = attach(PURCHASE, IMAGE)
        val second = attach(PURCHASE, IMAGE)

        assertEquals(first, second)
        assertEquals(1, attachments.rows.value.size)
        // En otro documento, la misma imagen se vuelve a usar sin otra fila de imagen.
        attach(EXPENSE, IMAGE)
        assertEquals(2, attachments.rows.value.size)
        assertEquals(1, database.tables.value.mediaAssets.size)
    }

    @Test
    fun `a voided or missing document does not take a new photo`() = runTest {
        assertEquals(AttachReceiptResult.DocumentUnavailable, attach(VOIDED, IMAGE))
        assertEquals(
            AttachReceiptResult.DocumentUnavailable,
            attach(AttachmentTarget.Expense(99L), IMAGE),
        )
        assertEquals(0, attachments.rows.value.size)
        assertEquals(0, database.tables.value.mediaAssets.size)
    }

    @Test
    fun `a failure while attaching leaves nothing`() = runTest {
        attachments.failNextInsert = true

        assertEquals(AttachReceiptResult.Failed(StorageFailure.DISK_FULL), attach(EXPENSE, IMAGE))

        assertEquals(0, attachments.rows.value.size)
        assertEquals(0, database.tables.value.mediaAssets.size)
    }

    @Test
    fun `removing the last use marks the image as unused, even on a voided document`() = runTest {
        val id = (attach(EXPENSE, IMAGE) as AttachReceiptResult.Attached).attachmentId
        // Anular el gasto no quita la foto: sigue ahí hasta que la persona la quite.
        attachments.documents[EXPENSE] = DocumentStatus.VOIDED
        assertEquals(1, observe(EXPENSE).first().size)

        assertEquals(RemoveReceiptResult.Removed, remove(id))

        assertEquals(emptyList<ReceiptPhoto>(), observe(EXPENSE).first())
        assertEquals(NOW, database.tables.value.mediaAssets.values.single().orphanedAt)
        assertEquals(RemoveReceiptResult.NotFound, remove(id))
    }

    @Test
    fun `an image still used elsewhere is not marked as unused`() = runTest {
        val id = (attach(EXPENSE, IMAGE) as AttachReceiptResult.Attached).attachmentId
        attach(PURCHASE, IMAGE)

        remove(id)

        assertNull(database.tables.value.mediaAssets.values.single().orphanedAt)
    }

    @Test
    fun `a failure while removing keeps the photo`() = runTest {
        val id = (attach(EXPENSE, IMAGE) as AttachReceiptResult.Attached).attachmentId
        attachments.failNextDelete = true

        assertEquals(RemoveReceiptResult.Failed(StorageFailure.IO_ERROR), remove(id))

        assertEquals(1, observe(EXPENSE).first().size)
    }

    @Test
    fun `a photo without its image row says it is unavailable`() = runTest {
        attachments.rows.value = listOf(DocumentAttachment(5L, EXPENSE, 77L, null, NOW))

        assertEquals(listOf(ReceiptPhoto(5L, null)), observe(EXPENSE).first())
    }

    /** Las fotos en memoria, con los documentos y su estado, y una falla inyectable. */
    private class FakeAttachments(private val media: FakeMediaAssetRepository) :
        DocumentAttachmentRepository {
        val rows = MutableStateFlow(emptyList<DocumentAttachment>())
        val documents = mutableMapOf<AttachmentTarget, DocumentStatus>()
        var failNextInsert = false
        var failNextDelete = false
        private var nextId = 1L

        override fun observe(target: AttachmentTarget): Flow<List<DocumentAttachment>> =
            rows.map { all -> all.filter { it.target == target } }

        override suspend fun findAll(target: AttachmentTarget): List<DocumentAttachment> =
            rows.value.filter { it.target == target }

        override suspend fun findById(id: Long): DocumentAttachment? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun findDocumentStatus(target: AttachmentTarget): DocumentStatus? =
            documents[target]

        override suspend fun insert(
            target: AttachmentTarget,
            mediaAssetId: Long,
            createdAt: Instant,
        ): Long {
            if (failNextInsert) {
                failNextInsert = false
                throw StorageFailureException(StorageFailure.DISK_FULL)
            }
            val id = nextId++
            rows.value = rows.value + DocumentAttachment(id, target, mediaAssetId, null, createdAt)
            syncReferences()
            return id
        }

        override suspend fun delete(id: Long) {
            if (failNextDelete) {
                failNextDelete = false
                throw StorageFailureException(StorageFailure.IO_ERROR)
            }
            rows.value = rows.value.filterNot { it.id == id }
            syncReferences()
        }

        /** Como la base: una imagen que alguna foto usa está en uso. */
        fun syncReferences() {
            media.otherReferences.clear()
            media.otherReferences += rows.value.map { it.mediaAssetId }
        }
    }

    /** La transacción de la base en memoria, que además repone las fotos si se deshace. */
    private class AttachmentRunner(
        database: InMemoryDatabase,
        private val attachments: FakeAttachments,
    ) : TransactionRunner {
        val inner = FakeTransactionRunner(database)

        override suspend fun <T> write(block: suspend () -> TxResult<T>): T {
            val before = attachments.rows.value
            return try {
                inner.write {
                    block().also { if (it is TxResult.Rollback) restore(before) }
                }
            } catch (failure: Throwable) {
                restore(before)
                throw failure
            }
        }

        override suspend fun <T> read(block: suspend () -> T): T = inner.read(block)

        private fun restore(rows: List<DocumentAttachment>) {
            attachments.rows.value = rows
            attachments.syncReferences()
        }
    }

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_793_000_000_000)
        val EXPENSE = AttachmentTarget.Expense(1L)
        val PURCHASE = AttachmentTarget.Purchase(2L)
        val VOIDED = AttachmentTarget.Expense(3L)
        val IMAGE = StoredImage("a".repeat(64), "image/webp", 1_600, 1_200, 250_000)
    }
}
