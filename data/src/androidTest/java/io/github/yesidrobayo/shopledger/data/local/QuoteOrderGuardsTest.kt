package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.mediaAsset
import io.github.yesidrobayo.shopledger.data.local.customer.customerRow
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaGuards
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de cotizaciones, pedidos y sus líneas, marcas de las ventas y fotos de
 * comprobantes. Un documento nace abierto, cuadra su total y cambia de estado una sola vez hacia
 * su final. Cerrado, ya no cambia, salvo lo que borra anonimizar a su cliente, y sus líneas no se
 * borran. Sus líneas solo cambian mientras sigue abierto. Cada guarda rechaza lo que debe, con su
 * mensaje, y deja pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class QuoteOrderGuardsTest : GuardProbeTest() {
    private var mediaId = 0L

    @Test
    fun aQuoteIsBornOpenAndBalanced() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val valid = quoteRow()
        for (broken in listOf(
            valid.with("status" to "CONVERTED", "converted_sale_id" to saleId),
            valid.with("status" to "CANCELLED"),
            valid.with("status" to "DRAFT"),
            valid.with("converted_sale_id" to saleId),
            valid.with("total" to 11_901L),
            valid.with("net_total" to 10_000.5),
            valid.with("valid_until" to "2026-02-30"),
            valid.with("note" to ""),
            valid.with("note" to "N".repeat(201)),
            valid.with("business_date" to "2026-1-1"),
            valid.with("updated_at" to null),
        )) {
            rejected(QUOTES, broken.insertInto("quotes"))
        }

        insert("quotes", valid)
        insert("quotes", valid.with("valid_until" to null, "note" to "N".repeat(200)))
        assertEquals(2L, count("SELECT COUNT(*) FROM quotes WHERE status = 'OPEN'"))
    }

    @Test
    fun aQuoteIsConvertedOrCancelledOnlyOnce() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val otherSaleId = insert("sales", saleRow(priceListId))
        val converted = insert("quotes", quoteRow())
        val cancelled = insert("quotes", quoteRow())

        rejected(QUOTES, update("quotes", converted, "status" to "CONVERTED"))
        rejected(QUOTES, update("quotes", converted, "converted_sale_id" to saleId))
        execute(update("quotes", converted, "status" to "CONVERTED", "converted_sale_id" to saleId))
        for (change in listOf(
            arrayOf("status" to "OPEN", "converted_sale_id" to null),
            arrayOf("status" to "CANCELLED", "converted_sale_id" to null),
            arrayOf("converted_sale_id" to otherSaleId),
        )) {
            rejected(QUOTES, update("quotes", converted, *change))
        }

        execute(update("quotes", cancelled, "status" to "CANCELLED", "updated_at" to LATER))
        rejected(QUOTES, update("quotes", cancelled, "status" to "OPEN"))
        assertEquals(saleId, count("SELECT converted_sale_id FROM quotes WHERE id = $converted"))
    }

    @Test
    fun quoteLinesBalanceAndOnlyChangeWhileTheQuoteIsOpen() = runBlocking {
        val open = insert("quotes", quoteRow())
        val valid = lineRow("quote_id" to open)
        for (broken in brokenLines(valid)) rejected(QUOTE_LINES, broken.insertInto("quote_lines"))
        val line = insert("quote_lines", valid)
        execute(update("quote_lines", line, "unit_price" to 6_000L, "line_discount" to 2_000L))

        val cancelled = insert("quotes", quoteRow())
        val closedLine = insert("quote_lines", lineRow("quote_id" to cancelled))
        execute(update("quotes", cancelled, "status" to "CANCELLED"))
        rejected(QUOTE_LINES, lineRow("quote_id" to cancelled).insertInto("quote_lines"))
        rejected(QUOTE_LINES, update("quote_lines", closedLine, "unit_price" to 1L))
        rejected(QUOTE_LINES, update("quote_lines", line, "line_total" to 1L))
        assertEquals(2L, count("SELECT COUNT(*) FROM quote_lines"))
    }

    @Test
    fun anOrderIsBornPendingAndDeliveredOrCancelledOnlyOnce() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val valid = orderRow()
        for (broken in listOf(
            valid.with("status" to "READY"),
            valid.with("status" to "DELIVERED", "delivered_sale_id" to saleId),
            valid.with("delivered_sale_id" to saleId),
            valid.with("status" to "SHIPPED"),
            valid.with("shipping_amount" to -1L),
            valid.with("total" to 1_000_000_000_000_001L),
            valid.with("due_date" to "mañana"),
            valid.with("delivery_address" to "D".repeat(201)),
            valid.with("delivery_note" to ""),
        )) {
            rejected(ORDERS, broken.insertInto("orders"))
        }

        val delivered = insert("orders", valid)
        execute(update("orders", delivered, "status" to "READY"))
        execute(update("orders", delivered, "status" to "PENDING"))
        execute(update("orders", delivered, "status" to "READY"))
        rejected(ORDERS, update("orders", delivered, "status" to "DELIVERED"))
        execute(update("orders", delivered, "status" to "DELIVERED", "delivered_sale_id" to saleId))
        for (change in listOf(
            arrayOf("status" to "READY", "delivered_sale_id" to null),
            arrayOf("status" to "CANCELLED", "delivered_sale_id" to null),
        )) {
            rejected(ORDERS, update("orders", delivered, *change))
        }

        val cancelled = insert("orders", valid.with("due_date" to null, "delivery_address" to null))
        execute(update("orders", cancelled, "status" to "CANCELLED"))
        rejected(ORDERS, update("orders", cancelled, "status" to "PENDING"))
        assertEquals(1L, count("SELECT COUNT(*) FROM orders WHERE status = 'DELIVERED'"))
    }

    @Test
    fun orderLinesBalanceAndOnlyChangeWhileTheOrderIsOpen() = runBlocking {
        val ready = insert("orders", orderRow())
        execute(update("orders", ready, "status" to "READY"))
        val valid = lineRow("order_id" to ready)
        for (broken in brokenLines(valid)) rejected(ORDER_LINES, broken.insertInto("order_lines"))
        val line = insert("order_lines", valid)
        execute(update("order_lines", line, "quantity_milli" to 3_000L))

        val cancelled = insert("orders", orderRow())
        val closedLine = insert("order_lines", lineRow("order_id" to cancelled))
        execute(update("orders", cancelled, "status" to "CANCELLED"))
        rejected(ORDER_LINES, lineRow("order_id" to cancelled).insertInto("order_lines"))
        rejected(ORDER_LINES, update("order_lines", closedLine, "quantity_milli" to 1L))
        assertEquals(2L, count("SELECT COUNT(*) FROM order_lines"))
    }

    @Test
    fun aClosedQuoteNeverChangesButItsUpdateInstant() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val customerId = insert("customers", customerRow())
        val converted = insert("quotes", quoteRow())
        execute(
            update("quotes", converted, "status" to "CONVERTED", "converted_sale_id" to saleId),
        )
        val cancelled = insert("quotes", quoteRow().with("note" to "Para el lunes"))
        execute(update("quotes", cancelled, "status" to "CANCELLED"))

        for (closed in listOf(converted, cancelled)) {
            for (change in listOf(
                // Otro total que sigue cuadrando: neto más impuesto.
                arrayOf("net_total" to 20_000L, "tax_total" to 3_800L, "total" to 23_800L),
                arrayOf("note" to "Otra nota"),
                arrayOf("customer_id" to customerId),
                arrayOf("valid_until" to "2026-02-28"),
                arrayOf("valid_until" to null),
                arrayOf("occurred_at" to LATER),
                arrayOf("business_date" to "2026-01-02"),
                arrayOf("created_at" to LATER),
            )) {
                rejected(QUOTES, update("quotes", closed, *change))
            }
            execute(update("quotes", closed, "updated_at" to MUCH_LATER))
        }
        // Tampoco se borra una nota que ya tenía.
        rejected(QUOTES, update("quotes", cancelled, "note" to null))

        // Abierta, todo eso se puede.
        val open = insert("quotes", quoteRow())
        execute(
            update(
                "quotes",
                open,
                "net_total" to 20_000L,
                "tax_total" to 3_800L,
                "total" to 23_800L,
                "note" to "Otra nota",
                "customer_id" to customerId,
            ),
        )
        assertEquals(23_800L, count("SELECT total FROM quotes WHERE id = $open"))
        assertEquals(
            2L,
            count(
                "SELECT COUNT(*) FROM quotes WHERE status <> 'OPEN' AND total = 11900 " +
                    "AND updated_at = $MUCH_LATER",
            ),
        )
    }

    @Test
    fun aClosedOrderNeverChangesButItsAddressAndNoteCanBeErased() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val customerId = insert("customers", customerRow())
        val withNote = orderRow().with("delivery_note" to "Timbre de la puerta azul")
        val delivered = insert("orders", withNote)
        execute(
            update("orders", delivered, "status" to "DELIVERED", "delivered_sale_id" to saleId),
        )
        val cancelled = insert("orders", withNote)
        execute(update("orders", cancelled, "status" to "CANCELLED"))

        for (closed in listOf(delivered, cancelled)) {
            for (change in listOf(
                arrayOf("total" to 20_000L),
                arrayOf("shipping_amount" to 0L),
                arrayOf("delivery_address" to "Otra calle de ejemplo 2"),
                arrayOf("delivery_note" to "Otra nota"),
                arrayOf("customer_id" to customerId),
                arrayOf("due_date" to "2026-01-06"),
                arrayOf("due_date" to null),
                arrayOf("occurred_at" to LATER),
                arrayOf("business_date" to "2026-01-02"),
                arrayOf("created_at" to LATER),
            )) {
                rejected(ORDERS, update("orders", closed, *change))
            }
            // Lo que borra anonimizar al cliente, por separado o junto con el resto.
            execute(update("orders", closed, "delivery_address" to null, "updated_at" to LATER))
            execute(update("orders", closed, "delivery_note" to null, "updated_at" to MUCH_LATER))
            // Ya vacía, no vuelve a tener dirección.
            rejected(ORDERS, update("orders", closed, "delivery_address" to "Calle de ejemplo 1"))
        }
        val other = insert("orders", withNote)
        execute(update("orders", other, "status" to "DELIVERED", "delivered_sale_id" to saleId))
        execute(update("orders", other, "delivery_address" to null, "delivery_note" to null))

        assertEquals(
            3L,
            count(
                "SELECT COUNT(*) FROM orders WHERE delivery_address IS NULL " +
                    "AND delivery_note IS NULL AND total = 14900",
            ),
        )
    }

    @Test
    fun theLinesOfAClosedDocumentAreNeverDeleted() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val openQuote = insert("quotes", quoteRow())
        val convertedQuote = insert("quotes", quoteRow())
        val cancelledQuote = insert("quotes", quoteRow())
        val quoteLines =
            listOf(openQuote, convertedQuote, cancelledQuote).associateWith {
                insert("quote_lines", lineRow("quote_id" to it))
            }
        execute(
            update(
                "quotes",
                convertedQuote,
                "status" to "CONVERTED",
                "converted_sale_id" to saleId,
            ),
        )
        execute(update("quotes", cancelledQuote, "status" to "CANCELLED"))

        rejected(QUOTE_LINES_CLOSED, deleteLine("quote_lines", quoteLines.getValue(convertedQuote)))
        rejected(QUOTE_LINES_CLOSED, deleteLine("quote_lines", quoteLines.getValue(cancelledQuote)))
        // Un borrado de varias filas que alcanza una cerrada se detiene entero.
        rejected(QUOTE_LINES_CLOSED, "DELETE FROM quote_lines")
        assertEquals(3L, count("SELECT COUNT(*) FROM quote_lines"))
        execute(deleteLine("quote_lines", quoteLines.getValue(openQuote)))
        assertEquals(2L, count("SELECT COUNT(*) FROM quote_lines"))

        val pendingOrder = insert("orders", orderRow())
        val readyOrder = insert("orders", orderRow())
        val deliveredOrder = insert("orders", orderRow())
        val cancelledOrder = insert("orders", orderRow())
        val orderLines =
            listOf(pendingOrder, readyOrder, deliveredOrder, cancelledOrder).associateWith {
                insert("order_lines", lineRow("order_id" to it))
            }
        execute(update("orders", readyOrder, "status" to "READY"))
        execute(
            update(
                "orders",
                deliveredOrder,
                "status" to "DELIVERED",
                "delivered_sale_id" to saleId,
            ),
        )
        execute(update("orders", cancelledOrder, "status" to "CANCELLED"))

        rejected(ORDER_LINES_CLOSED, deleteLine("order_lines", orderLines.getValue(deliveredOrder)))
        rejected(ORDER_LINES_CLOSED, deleteLine("order_lines", orderLines.getValue(cancelledOrder)))
        execute(deleteLine("order_lines", orderLines.getValue(pendingOrder)))
        execute(deleteLine("order_lines", orderLines.getValue(readyOrder)))
        assertEquals(2L, count("SELECT COUNT(*) FROM order_lines"))
    }

    @Test
    fun aCancelledDocumentIsDeletedWithItsLines() = runBlocking {
        // El modelo deja borrar una cotización sin convertir y un pedido sin entregar, y sus líneas
        // se van en cascada. SQLite borra la fila del documento antes de la cascada, así que la
        // guarda de las líneas ya no lo encuentra cerrado y la deja pasar.
        val quote = insert("quotes", quoteRow())
        insert("quote_lines", lineRow("quote_id" to quote))
        insert("quote_lines", lineRow("quote_id" to quote))
        execute(update("quotes", quote, "status" to "CANCELLED"))
        val order = insert("orders", orderRow())
        insert("order_lines", lineRow("order_id" to order))
        execute(update("orders", order, "status" to "CANCELLED"))

        assertEquals(1, database.quoteDao().delete(quote))
        assertEquals(1, database.orderDao().delete(order))

        assertEquals(0L, count("SELECT COUNT(*) FROM quotes"))
        assertEquals(0L, count("SELECT COUNT(*) FROM quote_lines"))
        assertEquals(0L, count("SELECT COUNT(*) FROM orders"))
        assertEquals(0L, count("SELECT COUNT(*) FROM order_lines"))
    }

    @Test
    fun aFiscalMarkIsRecordedWithItsInstantAndStaysWithItsSale() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val otherSaleId = insert("sales", saleRow(priceListId))
        val valid = fiscalMarkRow(saleId)
        for (broken in listOf(
            valid.with("status" to "ISSUED"),
            valid.with("status" to "RECORDED"),
            valid.with("external_reference" to ""),
            valid.with("external_reference" to "R".repeat(61)),
            valid.with("recorded_at" to "hoy"),
            valid.with("updated_at" to null),
        )) {
            rejected(FISCAL_MARKS, broken.insertInto("sale_fiscal_marks"))
        }

        val id = insert("sale_fiscal_marks", valid)
        insert(
            "sale_fiscal_marks",
            fiscalMarkRow(otherSaleId).with(
                "status" to "RECORDED",
                "recorded_at" to LATER,
                "external_reference" to "R".repeat(60),
            ),
        )
        rejected(FISCAL_MARKS, update("sale_fiscal_marks", id, "status" to "RECORDED"))
        rejected(FISCAL_MARKS, update("sale_fiscal_marks", id, "sale_id" to otherSaleId))
        execute(
            update("sale_fiscal_marks", id, "status" to "RECORDED", "recorded_at" to LATER),
        )
        execute(update("sale_fiscal_marks", id, "status" to "NOT_NEEDED", "recorded_at" to null))
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM sale_fiscal_marks WHERE status = 'NOT_NEEDED'"),
        )
    }

    @Test
    fun aFiscalMarkNeverGoesThroughTheVoidingGuardOfItsSale() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val markId =
            insert(
                "sale_fiscal_marks",
                fiscalMarkRow(saleId).with("status" to "RECORDED", "recorded_at" to LATER),
            )

        // La guarda de anulación no nombra la tabla de marcas, y anular una venta marcada pasa.
        assertFalse(SchemaGuards.named("guard_sales_void_only").sql.contains("sale_fiscal_marks"))
        execute(update("sales", saleId, *voiding("ENTRY_ERROR")))
        execute(
            update("sale_fiscal_marks", markId, "status" to "NOT_NEEDED", "recorded_at" to null),
        )
        assertEquals(1L, count("SELECT COUNT(*) FROM sales WHERE status = 'VOIDED'"))
    }

    @Test
    fun anAttachmentBelongsToExactlyOneDocument() = runBlocking {
        val expenseId =
            insert("expenses", expenseRow(insert("expense_categories", expenseCategoryRow())))
        val purchaseId = insert("purchases", purchaseRow())
        mediaId = database.mediaAssetDao().insert(mediaAsset().copy(kind = "DOCUMENT_ATTACHMENT"))
        val valid = attachmentRow(expenseId = expenseId, purchaseId = null)
        for (broken in listOf(
            valid.with("expense_id" to null),
            valid.with("purchase_id" to purchaseId),
            valid.with("caption" to ""),
            valid.with("caption" to "C".repeat(121)),
            valid.with("created_at" to "hoy"),
        )) {
            rejected(ATTACHMENTS, broken.insertInto("document_attachments"))
        }

        val id = insert("document_attachments", valid)
        insert("document_attachments", attachmentRow(expenseId = null, purchaseId = purchaseId))
        rejected(ATTACHMENTS, update("document_attachments", id, "purchase_id" to purchaseId))
        rejected(ATTACHMENTS, update("document_attachments", id, "expense_id" to null))
        execute(
            update("document_attachments", id, "expense_id" to null, "purchase_id" to purchaseId),
        )
        assertEquals(
            2L,
            count("SELECT COUNT(*) FROM document_attachments WHERE purchase_id = $purchaseId"),
        )
    }

    /** Lo que ninguna línea de cotización ni de pedido admite. */
    private fun brokenLines(valid: Row): List<Row> = listOf(
        valid.with("line_total" to 11_901L),
        valid.with("unit_factor_milli" to 0L),
        valid.with("quantity_milli" to 1_000_000_001L),
        valid.with("entered_quantity_milli" to 0.5),
        valid.with("unit_price" to 1_000_000_000_001L),
        valid.with("line_discount" to -1L),
        valid.with("tax_rate_bp" to 10_001L),
        valid.with("tax_name_snapshot" to ""),
        valid.with("product_name_snapshot" to null),
        valid.with("product_name_snapshot" to "P".repeat(121)),
    )

    private fun quoteRow(): Row = row(
        "customer_id" to null,
        "status" to "OPEN",
        "valid_until" to "2026-01-31",
        "net_total" to 10_000L,
        "tax_total" to 1_900L,
        "total" to 11_900L,
        "converted_sale_id" to null,
        "note" to null,
        "occurred_at" to CREATED_AT,
        "business_date" to BUSINESS_DATE,
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    private fun orderRow(): Row = row(
        "customer_id" to null,
        "status" to "PENDING",
        "delivery_address" to "Calle de ejemplo 1",
        "delivery_note" to null,
        "shipping_amount" to 3_000L,
        "due_date" to "2026-01-05",
        "delivered_sale_id" to null,
        "total" to 14_900L,
        "occurred_at" to CREATED_AT,
        "business_date" to BUSINESS_DATE,
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    /** Una línea de cotización o de pedido, según [document]: dos unidades a 5.000 más impuesto. */
    private fun lineRow(document: Pair<String, Long>): Row = row(
        document,
        "product_id" to productId,
        "unit_id" to UNIT_ID,
        "unit_factor_milli" to 1_000L,
        "entered_quantity_milli" to 2_000L,
        "quantity_milli" to 2_000L,
        "unit_price" to 5_000L,
        "line_discount" to 0L,
        "net_amount" to 10_000L,
        "tax_rate_bp" to 1_900L,
        "tax_name_snapshot" to "Tasa general",
        "tax_amount" to 1_900L,
        "line_total" to 11_900L,
        "product_name_snapshot" to "Café",
    )

    private fun fiscalMarkRow(saleId: Long): Row = row(
        "sale_id" to saleId,
        "status" to "PENDING",
        "external_reference" to null,
        "recorded_at" to null,
        "updated_at" to CREATED_AT,
    )

    /** La foto de un comprobante, de [mediaId]. */
    private fun attachmentRow(expenseId: Long?, purchaseId: Long?): Row = row(
        "media_asset_id" to mediaId,
        "expense_id" to expenseId,
        "purchase_id" to purchaseId,
        "caption" to "Factura del proveedor",
        "created_at" to CREATED_AT,
    )

    private fun deleteLine(table: String, id: Long): String = "DELETE FROM $table WHERE id = $id"

    private companion object {
        const val QUOTES = "guard:quotes"
        const val QUOTE_LINES = "guard:quote_lines"
        const val QUOTE_LINES_CLOSED = "guard:quote_lines_closed"
        const val ORDERS = "guard:orders"
        const val ORDER_LINES = "guard:order_lines"
        const val ORDER_LINES_CLOSED = "guard:order_lines_closed"
        const val FISCAL_MARKS = "guard:sale_fiscal_marks"
        const val ATTACHMENTS = "guard:document_attachments"
    }
}
