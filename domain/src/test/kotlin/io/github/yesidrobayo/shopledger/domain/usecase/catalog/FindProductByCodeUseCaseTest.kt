package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.CodeMatch
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductUnitRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Un código se busca primero en los productos y luego en sus conversiones. Un archivado se dice
 * archivado, y sin coincidencia se ofrece crear el producto. Los códigos son inventados.
 */
class FindProductByCodeUseCaseTest {
    private val fixture = CatalogFixture()
    private val conversions = FakeProductUnitRepository(fixture.database)
    private val find =
        FindProductByCodeUseCase(fixture.transactions, fixture.products, conversions)

    private var nextBox = 7L

    private fun boxOf(productId: Long, barcode: String): Long =
        fixture.seedConversion(nextBox++, productId, barcode).id

    @Test
    fun `a product code finds the product without a unit`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml", barcode = EAN)

        assertEquals(CodeMatch.Found(fixture.product(1L), conversion = null), find(EAN))
    }

    @Test
    fun `a conversion code finds its product in that unit`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml", barcode = EAN)
        val box = boxOf(1L, BOX)

        val match = find(BOX)

        assertEquals(
            CodeMatch.Found(fixture.product(1L), conversions.findById(box)),
            match,
        )
    }

    @Test
    fun `the product code wins over a conversion with the same text`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml")
        boxOf(1L, BOX)
        // Las guardas no lo dejan pasar; si pasara, manda el código del producto, como dice la
        // regla.
        fixture.seedProduct(id = 2L, name = "Arroz 1 kg", barcode = BOX)

        assertEquals(CodeMatch.Found(fixture.product(2L), conversion = null), find(BOX))
    }

    @Test
    fun `an archived product is reported as archived`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml", barcode = EAN, archived = true)

        assertEquals(CodeMatch.Archived(fixture.product(1L)), find(EAN))
    }

    @Test
    fun `a conversion of an archived product is reported as archived`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml", archived = true)
        boxOf(1L, BOX)

        assertEquals(CodeMatch.Archived(fixture.product(1L)), find(BOX))
    }

    @Test
    fun `an unknown code comes back trimmed to create the product`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml", barcode = EAN)

        assertEquals(CodeMatch.Unknown("P00000042"), find(" P00000042 "))
        assertEquals(CodeMatch.Unknown(""), find("   "))
    }

    @Test
    fun `a UPC-A read with or without its leading zero finds the same product`() = runTest {
        fixture.seedProduct(id = 1L, name = "Aceite 900 ml", barcode = "012345678905")
        fixture.seedProduct(id = 2L, name = "Arroz 1 kg", barcode = "0098765432109")

        assertEquals(CodeMatch.Found(fixture.product(1L), null), find("0012345678905"))
        assertEquals(CodeMatch.Found(fixture.product(2L), null), find("098765432109"))
    }

    private companion object {
        const val EAN = "2900000123458"
        const val BOX = "2900000999992"
    }
}
