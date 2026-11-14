package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.CodeMatch
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductUnitRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Generar códigos propios: `P` y el id con 8 cifras, guardados en una sola transacción, y un
 * choque se rechaza sin tocar a los demás. El código generado se resuelve como cualquier otro.
 */
class GenerateOwnBarcodesUseCaseTest {
    private val fixture = CatalogFixture()
    private val conversions = FakeProductUnitRepository(fixture.database)
    private val generate = generateOf(fixture)

    @Test
    fun `products without a code get P and their id, and the scanner finds them`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml")
        fixture.seedProduct(id = 123L, name = "Jabón de avena")

        val result = generate(listOf(123L, 1L))

        assertEquals(
            GenerateOwnBarcodesResult.Done(
                generated = mapOf(1L to "P00000001", 123L to "P00000123"),
                clashes = emptyList(),
                skipped = emptyList(),
            ),
            result,
        )
        assertEquals("P00000123", fixture.product(123L).details.barcode)
        assertEquals(1L, fixture.tables.changeSeq)
        val find = FindProductByCodeUseCase(fixture.transactions, fixture.products, conversions)
        assertEquals(CodeMatch.Found(fixture.product(123L), null), find("P00000123"))
    }

    @Test
    fun `a code another product already has is rejected and the rest are saved`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml")
        fixture.seedProduct(id = 2L, name = "Arroz 1 kg")
        // Alguien escribió a mano el código que le tocaría al 1.
        fixture.seedProduct(id = 3L, name = "Jabón de avena", barcode = "P00000001")

        val result = generate(listOf(1L, 2L))

        assertEquals(
            GenerateOwnBarcodesResult.Done(
                generated = mapOf(2L to "P00000002"),
                clashes = listOf(OwnBarcodeClash(1L, "P00000001", "Jabón de avena")),
                skipped = emptyList(),
            ),
            result,
        )
        assertNull(fixture.product(1L).details.barcode)
    }

    @Test
    fun `a conversion with the code also clashes`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml")
        fixture.seedProduct(id = 2L, name = "Arroz 1 kg")
        fixture.seedConversion(id = 7L, productId = 2L, barcode = "P00000001")
        val before = fixture.tables

        val result = generate(listOf(1L))

        assertEquals(
            GenerateOwnBarcodesResult.Done(
                generated = emptyMap(),
                clashes = listOf(OwnBarcodeClash(1L, "P00000001", "Arroz 1 kg")),
                skipped = emptyList(),
            ),
            result,
        )
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `a product with a code, archived or missing is skipped`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml", barcode = "2900000123458")
        fixture.seedProduct(id = 2L, name = "Arroz 1 kg", archived = true)
        val before = fixture.tables

        val result = generate(listOf(9L, 2L, 1L, 1L))

        assertEquals(
            GenerateOwnBarcodesResult.Done(emptyMap(), emptyList(), listOf(1L, 2L, 9L)),
            result,
        )
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `a restore in progress writes nothing`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml")
        fixture.transactions.restoreInProgress = true

        assertEquals(
            GenerateOwnBarcodesResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            generate(listOf(1L)),
        )
    }

    @Test
    fun `a failure at each write leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = {
                seedProduct(id = 1L, name = "Aceite 900 ml")
                seedProduct(id = 2L, name = "Arroz 1 kg")
            },
        ) { generateOf(this)(listOf(1L, 2L)) }
    }

    private companion object {
        fun generateOf(fixture: CatalogFixture) = GenerateOwnBarcodesUseCase(
            fixture.transactions,
            fixture.products,
            FakeProductUnitRepository(fixture.database),
            fixture.clock,
        )
    }
}
