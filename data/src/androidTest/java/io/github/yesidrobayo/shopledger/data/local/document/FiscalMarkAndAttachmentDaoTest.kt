package io.github.yesidrobayo.shopledger.data.local.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.mediaAsset
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.purchase
import io.github.yesidrobayo.shopledger.data.local.sale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las marcas de las ventas y las fotos de comprobantes. Una venta tiene a lo sumo una marca, sin
 * marca está pendiente, y marcarla nunca cambia la venta. Una foto es de un solo documento.
 */
@RunWith(AndroidJUnit4::class)
internal class FiscalMarkAndAttachmentDaoTest : LedgerDatabaseTest() {
    private val marks get() = database.saleFiscalMarkDao()
    private val attachments get() = database.documentAttachmentDao()
    private var mediaId = 0L

    @Test
    fun aSaleWithoutMarkOrWithAPendingOneIsStillPending() = runBlocking {
        val unmarked = database.saleDao().insert(sale(priceListId, businessDate = "2026-01-10"))
        val pending = database.saleDao().insert(sale(priceListId, businessDate = "2026-01-20"))
        val recorded = database.saleDao().insert(sale(priceListId, businessDate = "2026-01-20"))
        val notNeeded = database.saleDao().insert(sale(priceListId, businessDate = "2026-01-05"))
        val voided = database.saleDao().insert(sale(priceListId, businessDate = "2026-01-06"))
        val outside = database.saleDao().insert(sale(priceListId, businessDate = "2026-02-01"))
        val sameDay = database.saleDao().insert(sale(priceListId, businessDate = "2026-01-20"))
        marks.insert(mark(pending, "PENDING"))
        marks.insert(mark(recorded, "RECORDED", recordedAt = LATER, reference = "A-0001"))
        marks.insert(mark(notNeeded, "NOT_NEEDED"))
        database.saleDao().markVoided(voided, LATER, "ENTRY_ERROR", null)

        assertEquals(
            listOf(
                PendingFiscalSale(sameDay, "2026-01-20", 11_900, null, null),
                PendingFiscalSale(pending, "2026-01-20", 11_900, "PENDING", null),
                PendingFiscalSale(unmarked, "2026-01-10", 11_900, null, null),
            ),
            marks.findPending("2026-01-01", "2026-01-31", limit = 10),
        )
        assertEquals(
            listOf(sameDay),
            marks.findPending("2026-01-01", "2026-01-31", limit = 1).map { it.saleId },
        )
        assertEquals(
            listOf(outside),
            marks.findPending("2026-02-01", "2026-02-01", limit = 10).map { it.saleId },
        )
    }

    @Test
    fun markingASaleNeverChangesTheSaleAndASaleHasOneMark() = runBlocking {
        val saleId = database.saleDao().insert(sale(priceListId))
        val before = database.saleDao().findById(saleId)

        val id = marks.insert(mark(saleId, "PENDING"))
        assertEquals(1, marks.update(saleId, "RECORDED", "A-0002", recordedAt = LATER, LATER))
        assertEquals(
            SaleFiscalMarkEntity(id, saleId, "RECORDED", "A-0002", LATER, LATER),
            marks.findBySale(saleId),
        )
        assertEquals(before, database.saleDao().findById(saleId))
        constraintFailure(UNIQUE) { marks.insert(mark(saleId, "PENDING")) }

        // Una venta marcada se anula igual, y su marca sigue cambiando.
        assertEquals(1, database.saleDao().markVoided(saleId, LATER, "UNDO", null))
        assertEquals(1, marks.update(saleId, "NOT_NEEDED", null, recordedAt = null, LATER))
        guardFailure("guard:sale_fiscal_marks") {
            marks.update(saleId, "RECORDED", null, recordedAt = null, LATER)
        }
        assertEquals(0, marks.update(MISSING_ID, "PENDING", null, recordedAt = null, LATER))
        assertNull(marks.findBySale(MISSING_ID))
        constraintFailure(FOREIGN_KEY) { marks.insert(mark(MISSING_ID, "PENDING")) }
    }

    @Test
    fun attachmentsFollowTheirDocumentInOrder() = runBlocking {
        val expenseId =
            database.expenseDao().insert(
                expense(database.expenseCategoryDao().insert(expenseCategory("Servicios"))),
            )
        val purchaseId = database.purchaseDao().insert(purchase(supplierId = null))
        mediaId = database.mediaAssetDao().insert(mediaAsset().copy(kind = "DOCUMENT_ATTACHMENT"))
        val first = attachments.insert(attachment(expenseId = expenseId, purchaseId = null))
        val ofPurchase = attachments.insert(attachment(expenseId = null, purchaseId = purchaseId))

        val ofExpense = Emissions(this, attachments.observeByExpense(expenseId).ids { it.id })
        try {
            ofExpense.awaitValue(listOf(first))
            val second = attachments.insert(attachment(expenseId = expenseId, purchaseId = null))
            ofExpense.awaitValue(listOf(first, second))

            assertEquals(1, attachments.delete(first))
            ofExpense.awaitValue(listOf(second))
        } finally {
            ofExpense.stop()
        }

        val ofPurchaseEmissions = Emissions(this, attachments.observeByPurchase(purchaseId))
        try {
            ofPurchaseEmissions.awaitValue(
                listOf(attachment(expenseId = null, purchaseId = purchaseId).copy(id = ofPurchase)),
            )
            assertEquals(1, attachments.updateCaption(ofPurchase, null))
            ofPurchaseEmissions.awaitValue(
                listOf(
                    attachment(expenseId = null, purchaseId = purchaseId)
                        .copy(id = ofPurchase, caption = null),
                ),
            )
        } finally {
            ofPurchaseEmissions.stop()
        }

        guardFailure("guard:document_attachments") {
            attachments.insert(attachment(expenseId = expenseId, purchaseId = purchaseId))
        }
        constraintFailure(FOREIGN_KEY) {
            attachments.insert(attachment(expenseId = MISSING_ID, purchaseId = null))
        }
        // La foto de un comprobante no se borra mientras el comprobante la use.
        constraintFailure(FOREIGN_KEY) { database.mediaAssetDao().delete(mediaId) }
        constraintFailure(FOREIGN_KEY) {
            attachments.insert(attachment(expenseId, purchaseId = null, media = MISSING_ID))
        }
        assertEquals(0, attachments.delete(MISSING_ID))
    }

    @Test
    fun keysAndIndexesAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf("sale_id -> sales RESTRICT"),
            database.foreignKeysOf("sale_fiscal_marks"),
        )
        assertEquals(
            listOf("unique index_sale_fiscal_marks_sale_id (sale_id)"),
            database.indexesOf("sale_fiscal_marks"),
        )
        assertEquals(
            listOf(
                "expense_id -> expenses RESTRICT",
                "media_asset_id -> media_assets RESTRICT",
                "purchase_id -> purchases RESTRICT",
            ),
            database.foreignKeysOf("document_attachments"),
        )
        assertEquals(
            listOf(
                "index_document_attachments_expense_id (expense_id)",
                "index_document_attachments_media_asset_id (media_asset_id)",
                "index_document_attachments_purchase_id (purchase_id)",
            ),
            database.indexesOf("document_attachments"),
        )
    }

    private fun mark(
        saleId: Long,
        status: String,
        recordedAt: Long? = null,
        reference: String? = null,
    ): SaleFiscalMarkEntity = SaleFiscalMarkEntity(
        saleId = saleId,
        status = status,
        externalReference = reference,
        recordedAt = recordedAt,
        updatedAt = CREATED_AT,
    )

    /** La foto de un comprobante, de [mediaId] salvo que se pida otra. */
    private fun attachment(
        expenseId: Long?,
        purchaseId: Long?,
        media: Long = mediaId,
    ): DocumentAttachmentEntity = DocumentAttachmentEntity(
        mediaAssetId = media,
        expenseId = expenseId,
        purchaseId = purchaseId,
        caption = "Factura del proveedor",
        createdAt = CREATED_AT,
    )

    private companion object {
        const val MISSING_ID = 999L
    }
}
