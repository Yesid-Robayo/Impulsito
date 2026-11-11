package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.BOX_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.KILOGRAM_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que escribe la pantalla de unidades: crear, editar, archivar y eliminar unidades, y las
 * conversiones de cada producto, con las guardas reales y sin tocar una venta ya registrada. Los
 * nombres y los códigos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class UnitRepositoriesTest : RepositoryTest() {
    private val units by lazy { RoomUnitRepository(database) }
    private val conversions by lazy { RoomProductUnitRepository(database) }
    private val sales by lazy { RoomSaleRepository(database) }

    @Test
    fun aUnitOfThePersonIsCreatedEditedArchivedAndDeleted() = runBlocking {
        val id = commit { units.insert(BULTO, AT) }
        assertEquals(
            MeasureUnit(id, null, "Bulto", "blt", UnitKind.MASS, true, null),
            units.findById(id),
        )
        assertEquals(id, units.findActiveByNormalizedName("bulto")?.id)

        commit { units.update(id, BULTO.copy(name = "Saco", normalizedName = "saco"), LATER_AT) }
        assertEquals("Saco", units.findById(id)?.name)

        commit { units.setArchivedAt(id, archivedAt = LATER_AT, updatedAt = LATER_AT) }
        assertNull(units.findActiveByNormalizedName("saco"))
        assertEquals(LATER_AT, units.observeAll().first().single { it.id == id }.archivedAt)

        commit { units.delete(id) }
        assertNull(units.findById(id))
        assertEquals(4L, changeSeq())
    }

    @Test
    fun aRepeatedActiveNameIsRejectedByTheGuardAndLeavesNothing() = runBlocking {
        commit { units.insert(BULTO, AT) }

        val failure = storageFailureOf { commit { units.insert(BULTO.copy(symbol = null), AT) } }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(1, units.observeAll().first().count { it.name == "Bulto" })
        assertEquals(1L, changeSeq())
    }

    @Test
    fun anInitialUnitRenamedAndBackKeepsItsKey() = runBlocking {
        commit {
            units.update(BOX_ID, UnitDetails("Bandeja", "bandeja", null, UnitKind.COUNT, false), AT)
        }
        assertEquals("Bandeja", units.findById(BOX_ID)?.name)

        commit {
            units.update(BOX_ID, UnitDetails(null, null, null, UnitKind.COUNT, false), LATER_AT)
        }

        val box = requireNotNull(units.findById(BOX_ID))
        assertEquals("BOX", box.systemKey)
        assertNull(box.name)
    }

    @Test
    fun theUnitsInUseFollowProductsAndConversions() = runBlocking {
        units.observeInUse().test {
            // El producto de la base de prueba se lleva por unidad.
            awaitValue(setOf(UNIT_ID))
            commit { conversions.insert(productId, box(), AT) }
            awaitValue(setOf(UNIT_ID, BOX_ID))
            database.productDao().insert(product("Arroz").copy(baseUnitId = KILOGRAM_ID))
            awaitValue(setOf(UNIT_ID, BOX_ID, KILOGRAM_ID))
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(true, units.isInUse(BOX_ID))
        // Una unidad con uso no se borra: su llave es RESTRICT.
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf {
                commit { units.delete(BOX_ID) }
            },
        )
        assertEquals(BOX_ID, units.findById(BOX_ID)?.id)
    }

    @Test
    fun aConversionIsSavedFoundChangedAndRemoved() = runBlocking {
        val id = commit { conversions.insert(productId, box(barcode = "7701"), AT) }
        val saved = ProductConversion(id, productId, box(barcode = "7701"))

        assertEquals(saved, conversions.findById(id))
        assertEquals(saved, conversions.findByProductAndUnit(productId, BOX_ID))
        assertEquals(saved, conversions.findByBarcode("7701"))
        assertEquals(listOf(saved), conversions.observeByProduct(productId).first())

        val changed = box(factor = 12_000L, price = Money(9_000L), barcode = null)
        commit { conversions.update(id, changed, LATER_AT) }
        assertEquals(
            listOf(ProductConversion(id, productId, changed)),
            conversions.observeAll().first(),
        )

        commit { conversions.delete(id) }
        assertNull(conversions.findById(id))
        assertEquals(3L, changeSeq())
    }

    @Test
    fun aCodeOfAnotherProductOrASecondConversionToTheSameUnitIsRejected() = runBlocking {
        database.productDao().insert(product("Arroz", barcode = "8800"))
        commit { conversions.insert(productId, box(), AT) }

        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf { commit { conversions.insert(productId, box(factor = 6_000L), AT) } },
        )
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf {
                commit {
                    conversions.insert(
                        productId,
                        box(barcode = "8800").copy(unitId = KILOGRAM_ID),
                        AT,
                    )
                }
            },
        )
        assertEquals(1, conversions.observeAll().first().size)
    }

    @Test
    fun changingOrRemovingAConversionNeverChangesAPastSale() = runBlocking {
        val id = commit { conversions.insert(productId, box(), AT) }
        val saleId = commit {
            sales.insert(newSale(priceListId)).also {
                sales.insertLine(it, newSaleLine(productId, priceListId))
            }
        }
        val before = sales.findLines(saleId)

        commit { conversions.update(id, box(factor = 6_000L), LATER_AT) }
        assertEquals(before, sales.findLines(saleId))
        commit { conversions.delete(id) }

        assertEquals(before, sales.findLines(saleId))
    }

    private fun box(factor: Long = 24_000L, price: Money? = null, barcode: String? = null) =
        ConversionDetails(BOX_ID, factor, price, barcode, forSale = false, forPurchase = true)

    private companion object {
        val BULTO = UnitDetails("Bulto", "bulto", "blt", UnitKind.MASS, allowsFraction = true)
    }
}
