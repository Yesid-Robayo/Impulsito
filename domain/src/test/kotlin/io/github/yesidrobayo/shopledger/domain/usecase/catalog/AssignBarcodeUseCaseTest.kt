package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductUnitRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dar un código a un producto que no tiene: queda guardado si nadie lo usa, y un choque se rechaza
 * sin escribir nada. Los códigos son inventados.
 */
class AssignBarcodeUseCaseTest {
    private val fixture = CatalogFixture()
    private val conversions = FakeProductUnitRepository(fixture.database)
    private val assign = assignOf(fixture)

    @Test
    fun `a free code is saved on the product and nothing else changes`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml", salePrice = Money(4_200L))
        val before = fixture.product(1L)

        assertEquals(AssignBarcodeResult.Assigned(CODE), assign(1L, " $CODE "))

        val after = fixture.product(1L)
        assertEquals(CODE, after.details.barcode)
        assertEquals(before.details.copy(barcode = CODE), after.details)
        assertEquals(before.salePrice, after.salePrice)
        assertEquals(CatalogFixture.NOW, after.updatedAt)
        assertEquals(1L, fixture.tables.changeSeq)
    }

    @Test
    fun `a code another active product has is rejected with its name`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml")
        fixture.seedProduct(id = 2L, name = "Arroz 1 kg", barcode = CODE)
        val before = fixture.tables

        assertEquals(AssignBarcodeResult.Taken("Arroz 1 kg"), assign(1L, CODE))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `a code of a conversion is rejected with its product name`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml")
        fixture.seedProduct(id = 2L, name = "Arroz 1 kg")
        fixture.seedConversion(id = 7L, productId = 2L, barcode = CODE)
        val before = fixture.tables

        assertEquals(AssignBarcodeResult.Taken("Arroz 1 kg"), assign(1L, CODE))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `an archived product with the code does not block it`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml")
        fixture.seedProduct(id = 2L, name = "Arroz 1 kg", barcode = CODE, archived = true)

        assertEquals(AssignBarcodeResult.Assigned(CODE), assign(1L, CODE))
    }

    @Test
    fun `a product with a code, archived or missing is not touched`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml", barcode = "2900000123458")
        fixture.seedProduct(id = 2L, name = "Arroz 1 kg", archived = true)
        val before = fixture.tables

        assertEquals(AssignBarcodeResult.AlreadyHasCode, assign(1L, CODE))
        assertEquals(AssignBarcodeResult.Archived, assign(2L, CODE))
        assertEquals(AssignBarcodeResult.NotFound, assign(9L, CODE))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `a blank or too long code is rejected`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml")

        assertEquals(AssignBarcodeResult.InvalidCode, assign(1L, "   "))
        assertEquals(AssignBarcodeResult.InvalidCode, assign(1L, "7".repeat(65)))
        assertEquals(AssignBarcodeResult.Assigned("7".repeat(64)), assign(1L, "7".repeat(64)))
    }

    @Test
    fun `a restore in progress writes nothing`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml")
        fixture.transactions.restoreInProgress = true

        assertEquals(
            AssignBarcodeResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            assign(1L, CODE),
        )
    }

    @Test
    fun `a failure at each write leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = { seedProduct(id = 1L, name = "Aceite 900 ml") },
        ) { assignOf(this)(1L, CODE) }
    }

    private companion object {
        const val CODE = "P00000001"

        fun assignOf(fixture: CatalogFixture) = AssignBarcodeUseCase(
            fixture.transactions,
            fixture.products,
            FakeProductUnitRepository(fixture.database),
            fixture.clock,
        )
    }
}
