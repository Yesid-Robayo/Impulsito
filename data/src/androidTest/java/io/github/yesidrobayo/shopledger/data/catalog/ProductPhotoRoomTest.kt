package io.github.yesidrobayo.shopledger.data.catalog

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductPhotoChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La foto de un producto sobre la base real: la base guarda solo su fila de metadatos, la misma
 * imagen es una sola fila para dos productos, y la que deja de usarse queda marcada como huérfana.
 * Las imágenes son sumas inventadas: aquí no hace falta el archivo.
 */
internal class ProductPhotoRoomTest : CatalogDatabaseTest() {
    private val ports by lazy {
        DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
    }
    private val create by lazy {
        CreateProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers, ports.units,
            ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
            BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings), FIXED_CLOCK,
            ports.mediaAssets,
        )
    }
    private val edit by lazy {
        EditProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers, ports.units,
            ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
            BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings), FIXED_CLOCK,
            ports.productComponents,
            ports.mediaAssets,
        )
    }

    @Test
    fun twoProductsWithTheSamePhotoShareOneRow() = runBlocking {
        val first = saved(create(form("Pan de queso", ProductPhotoChange.Use(image('a')))))
        val second = saved(create(form("Pan de bono", ProductPhotoChange.Use(image('a')))))

        assertEquals(1L, count("SELECT COUNT(*) FROM media_assets"))
        assertEquals(
            1L,
            count(
                "SELECT COUNT(DISTINCT photo_media_id) FROM products " +
                    "WHERE id IN ($first, $second)",
            ),
        )
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM media_assets WHERE kind = 'PRODUCT_PHOTO'"),
        )
    }

    @Test
    fun aReplacedPhotoIsMarkedOrphanedAndTheNewOneIsNot() = runBlocking {
        val id = saved(create(form("Pan de queso", ProductPhotoChange.Use(image('a')))))

        saved(edit(id, form("Pan de queso", ProductPhotoChange.Use(image('b')))))

        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM media_assets WHERE orphaned_at IS NOT NULL"),
        )
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM products p JOIN media_assets m " +
                    "ON m.id = p.photo_media_id WHERE p.id = $id AND m.orphaned_at IS NULL " +
                    "AND m.sha256 = '${image('b').sha256}'",
            ),
        )
    }

    @Test
    fun theDatabaseHoldsNoImageColumn() = runBlocking {
        // Ninguna tabla guarda bytes: las fotos viven en sus archivos, nombrados por su suma.
        assertEquals(
            0L,
            count(
                "SELECT COUNT(*) FROM sqlite_master t, pragma_table_info(t.name) c " +
                    "WHERE t.type = 'table' AND upper(c.type) = 'BLOB'",
            ),
        )
    }

    private fun form(name: String, photo: ProductPhotoChange) =
        ProductForm(name = name, salePrice = Money(2_500L), photo = photo)

    private fun saved(result: SaveProductResult): Long =
        (result as? SaveProductResult.Saved)?.productId
            ?: throw AssertionError("No se guardó: $result")

    private fun image(digit: Char) = StoredImage(
        sha256 = digit.toString().repeat(64),
        mimeType = "image/webp",
        widthPx = 640,
        heightPx = 480,
        sizeBytes = 21_000L,
    )
}
