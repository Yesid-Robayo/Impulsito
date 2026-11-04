package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.catalog.unit
import io.github.yesidrobayo.shopledger.data.local.customer.customer
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.domain.model.Limit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las ventas en espera: cada columna en su lugar, en el orden en que se crearon, escrituras que
 * tocan solo sus columnas, y una espera que se va con sus líneas, sus extras y sin tocar nada del
 * negocio: ni el libro, ni las ventas, ni el contador de cambios.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleDraftDaoTest : LedgerDatabaseTest() {
    private val drafts get() = database.saleDraftDao()

    @Test
    fun aDraftAndItsLinesReadBackWithEveryColumnInOrder() = runBlocking {
        val tea = database.productDao().insert(product("Té"))
        // Cada columna con un valor distinto, para que dos columnas cruzadas no pasen.
        val customerId = database.customerDao().insert(customer("Ana"))
        val draft = draft("Mesa 4").copy(customerId = customerId, priceListId = priceListId)
        val id = drafts.insert(draft)
        val main =
            line(id, productId).copy(
                enteredQuantityMilli = Limit.LINE_QUANTITY_MILLI.max,
                unitPrice = Limit.UNIT_MONEY.max,
                lineDiscount = Limit.DOCUMENT_TOTAL.max,
            )
        val mainId = drafts.insertLine(main)
        val extra = line(id, tea).copy(unitPrice = null, parentLineId = mainId)
        val extraId = drafts.insertLine(extra)

        assertEquals(draft.copy(id = id), drafts.findById(id))
        assertEquals(
            listOf(main.copy(id = mainId), extra.copy(id = extraId)),
            drafts.findLines(id),
        )
        assertNull(drafts.findById(MISSING_ID))
    }

    @Test
    fun writesChangeOnlyTheirColumnsAndListsEmitAgain() = runBlocking {
        val first = drafts.insert(draft("Mesa 1"))
        val emissions = Emissions(this, drafts.observeAll().ids { it.id })
        try {
            emissions.awaitValue(listOf(first))
            val second = drafts.insert(draft(null))
            emissions.awaitValue(listOf(first, second))

            assertEquals(1, drafts.update(first, null, null, priceListId, LATER))
            assertEquals(
                draft(null).copy(id = first, priceListId = priceListId, updatedAt = LATER),
                drafts.findById(first),
            )
        } finally {
            emissions.stop()
        }

        val lineId = drafts.insertLine(line(first, productId))
        val lines = Emissions(this, drafts.observeLines(first))
        try {
            assertEquals(1, drafts.updateLine(lineId, 3_500, unitPrice = null, lineDiscount = 200))
            lines.awaitValue(
                listOf(
                    line(first, productId).copy(
                        id = lineId,
                        enteredQuantityMilli = 3_500,
                        unitPrice = null,
                        lineDiscount = 200,
                    ),
                ),
            )
            assertEquals(1, drafts.deleteLine(lineId))
            lines.awaitValue(emptyList())
        } finally {
            lines.stop()
        }
        assertEquals(0, drafts.update(MISSING_ID, null, null, null, LATER))
    }

    @Test
    fun deletingADraftDeletesItsLinesAndTouchesNothingElse() = runBlocking {
        val sold = insertSaleWithLine()
        val changeSeq = count("SELECT change_seq FROM backup_state")
        val kept = drafts.insert(draft("Mesa 2"))
        val keptLine = drafts.insertLine(line(kept, productId))
        val id = drafts.insert(draft("Mesa 1"))
        val main = drafts.insertLine(line(id, productId))
        drafts.insertLine(line(id, productId).copy(parentLineId = main))

        assertEquals(1, drafts.delete(id))

        // Las líneas y sus extras se van con la espera; la otra espera y la venta quedan.
        assertEquals(listOf(keptLine), drafts.findLines(kept).map { it.id })
        assertEquals(emptyList<SaleDraftLineEntity>(), drafts.findLines(id))
        assertEquals(1L, count("SELECT COUNT(*) FROM sale_draft_lines"))
        assertEquals(1L, count("SELECT COUNT(*) FROM sale_lines WHERE id = ${sold.id}"))
        // Ninguna escritura de la espera toca el libro, las ventas ni el contador de cambios.
        assertEquals(0L, count("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(changeSeq, count("SELECT change_seq FROM backup_state"))
        assertEquals(0, drafts.delete(id))
    }

    @Test
    fun aLineGoesWithItsProductAndItsMainLineAndTheListFallsBackToTheDefault() = runBlocking {
        val tea = database.productDao().insert(product("Té"))
        val otherList = insertPriceList("mayorista")
        val id = drafts.insert(draft("Mesa 3").copy(priceListId = otherList))
        val main = drafts.insertLine(line(id, tea))
        val extra = drafts.insertLine(line(id, productId).copy(parentLineId = main))
        val other = drafts.insertLine(line(id, productId))

        // Borrar el producto saca su línea y, con ella, sus extras.
        assertEquals(1, database.productDao().delete(tea))
        assertEquals(listOf(other), drafts.findLines(id).map { it.id })
        assertNull(drafts.findLines(id).firstOrNull { it.id == extra })

        // Borrar la lista deja la espera con la de siempre, y borrar el cliente, sin cliente.
        execute("DELETE FROM price_lists WHERE id = $otherList")
        assertNull(drafts.findById(id)?.priceListId)
        val customerId = database.customerDao().insert(customer("Beatriz"))
        drafts.update(id, "Mesa 3", customerId, null, LATER)
        execute("DELETE FROM customers WHERE id = $customerId")
        assertNull(drafts.findById(id)?.customerId)

        constraintFailure(FOREIGN_KEY) { drafts.insertLine(line(MISSING_ID, productId)) }
        constraintFailure(FOREIGN_KEY) {
            drafts.insertLine(line(id, productId).copy(parentLineId = MISSING_ID))
        }

        // La unidad de una línea en espera no se borra: se archiva. Sin la línea, sí se borra.
        constraintFailure(FOREIGN_KEY) {
            drafts.insertLine(line(id, productId).copy(unitId = MISSING_ID))
        }
        val box = database.unitDao().insert(unit("bulto"))
        assertFalse(database.unitDao().isInUse(box))
        val boxed = drafts.insertLine(line(id, productId).copy(unitId = box))
        assertTrue(database.unitDao().isInUse(box))
        constraintFailure(FOREIGN_KEY) { database.unitDao().delete(box) }
        assertEquals(1, drafts.deleteLine(boxed))
        assertEquals(1, database.unitDao().delete(box))
    }

    private fun draft(label: String?) = SaleDraftEntity(
        label = label,
        customerId = null,
        priceListId = null,
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )

    private fun line(draftId: Long, productId: Long) = SaleDraftLineEntity(
        draftId = draftId,
        productId = productId,
        unitId = UNIT_ID,
        enteredQuantityMilli = 2_000,
        unitPrice = 2_500,
        lineDiscount = 0,
        parentLineId = null,
    )

    private companion object {
        const val MISSING_ID = 999L
    }
}
