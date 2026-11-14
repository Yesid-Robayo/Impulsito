package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductPhotoChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La foto de un producto: se anota al guardarlo, en la misma transacción, la misma imagen es una
 * sola fila aunque la usen dos productos, y la que deja de usarse queda marcada para borrarse más
 * adelante, salvo que otro producto la siga usando.
 */
class ProductPhotoUseCasesTest {
    private val fixture = CatalogFixture()
    private val photo = image('a')
    private val otherPhoto = image('b')

    private fun form(name: String, photo: ProductPhotoChange = ProductPhotoChange.Keep) =
        ProductForm(name = name, salePrice = Money(2_500L), photo = photo)

    @Test
    fun `a new product with a photo records it in the same transaction`() = runTest {
        val result = fixture.create(form("Pan de queso", ProductPhotoChange.Use(photo)))

        val id = (result as SaveProductResult.Saved).productId
        val row = fixture.tables.mediaAssets.entries.single()
        assertEquals(row.key, fixture.product(id).details.photoMediaId)
        assertEquals(photo, row.value.image)
        assertEquals(MediaKind.PRODUCT_PHOTO, row.value.kind)
        assertEquals(NOW, row.value.createdAt)
        assertNull(row.value.orphanedAt)
        assertEquals(1, fixture.transactions.commits)
    }

    @Test
    fun `two products with the same photo share one row`() = runTest {
        fixture.create(form("Pan de queso", ProductPhotoChange.Use(photo)))
        fixture.create(form("Pan de bono", ProductPhotoChange.Use(photo)))

        val id = fixture.tables.mediaAssets.keys.single()
        assertEquals(id, fixture.product(1L).details.photoMediaId)
        assertEquals(id, fixture.product(2L).details.photoMediaId)
    }

    @Test
    fun `replacing the photo marks the old one as unused`() = runTest {
        fixture.create(form("Pan de queso", ProductPhotoChange.Use(photo)))
        val old = requireNotNull(fixture.product(1L).details.photoMediaId)

        fixture.edit(1L, form("Pan de queso", ProductPhotoChange.Use(otherPhoto)))

        val current = requireNotNull(fixture.product(1L).details.photoMediaId)
        assertEquals(otherPhoto, fixture.tables.mediaAssets.getValue(current).image)
        assertEquals(NOW, fixture.tables.mediaAssets.getValue(old).orphanedAt)
        assertNull(fixture.tables.mediaAssets.getValue(current).orphanedAt)
    }

    @Test
    fun `removing a photo another product uses keeps it in use`() = runTest {
        fixture.create(form("Pan de queso", ProductPhotoChange.Use(photo)))
        fixture.create(form("Pan de bono", ProductPhotoChange.Use(photo)))

        fixture.edit(1L, form("Pan de queso", ProductPhotoChange.Remove))

        assertNull(fixture.product(1L).details.photoMediaId)
        assertNull(fixture.tables.mediaAssets.values.single().orphanedAt)
    }

    @Test
    fun `an unused photo chosen again is used again`() = runTest {
        fixture.create(form("Pan de queso", ProductPhotoChange.Use(photo)))
        fixture.edit(1L, form("Pan de queso", ProductPhotoChange.Remove))
        assertEquals(NOW, fixture.tables.mediaAssets.values.single().orphanedAt)

        fixture.edit(1L, form("Pan de queso", ProductPhotoChange.Use(photo)))

        val row = fixture.tables.mediaAssets.entries.single()
        assertEquals(row.key, fixture.product(1L).details.photoMediaId)
        assertNull(row.value.orphanedAt)
    }

    @Test
    fun `keeping the photo writes nothing`() = runTest {
        fixture.create(form("Pan de queso", ProductPhotoChange.Use(photo)))
        val before = fixture.tables

        val result = fixture.edit(1L, form("Pan de queso"))

        assertEquals(SaveProductResult.Saved(1L), result)
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `a rejected product leaves no photo row`() = runTest {
        fixture.create(form("Pan de queso"))

        val result = fixture.create(form("pan de QUESO", ProductPhotoChange.Use(photo)))

        assertEquals(SaveProductResult.DuplicateName("Pan de queso"), result)
        assertEquals(emptyMap<Long, Any>(), fixture.tables.mediaAssets)
    }

    @Test
    fun `a failure at any write leaves neither the product nor its photo`() = runTest {
        val before = fixture.tables
        for (write in 1..4) {
            fixture.database.failAtWrite = write
            try {
                fixture.create(form("Pan de queso", ProductPhotoChange.Use(photo)))
            } catch (expected: InjectedFailure) {
                // La falla inyectada sale del caso de uso tras deshacer la transacción.
            }
            fixture.database.failAtWrite = null
            assertEquals("escritura $write", before, fixture.tables)
        }
    }

    @Test
    fun `importing a product photo asks for the product size`() = runTest {
        var asked: MediaKind? = null
        val import = ImportProductPhotoUseCase { _, kind ->
            asked = kind
            ImageImport.Imported(photo)
        }

        assertEquals(ImageImport.Imported(photo), import("content://picker/1"))
        assertEquals(MediaKind.PRODUCT_PHOTO, asked)
    }

    private fun image(digit: Char) = StoredImage(
        sha256 = digit.toString().repeat(64),
        mimeType = "image/webp",
        widthPx = 640,
        heightPx = 480,
        sizeBytes = 21_000L,
    )
}
