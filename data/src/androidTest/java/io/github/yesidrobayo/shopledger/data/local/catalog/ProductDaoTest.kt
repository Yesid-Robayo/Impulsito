package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.Limit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los productos: cada columna en su lugar y entera, un archivado que no reserva nombre ni código,
 * variantes que protegen a su padre, y escrituras que tocan solo sus columnas. Lo más importante
 * es lo último: editar el producto nunca pisa lo que dejó el libro de stock.
 */
@RunWith(AndroidJUnit4::class)
internal class ProductDaoTest : CatalogDatabaseTest() {
    private val products get() = database.productDao()

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        val categoryId = database.categoryDao().insert(category("Bebidas"))
        val supplierId = database.supplierDao().insert(supplier("Distribuidora Andina"))
        val parentId = products.insert(product("Camiseta"))
        // Cada columna con un valor distinto, para que dos columnas cruzadas no pasen.
        val variant =
            ProductEntity(
                name = "Camiseta roja M",
                normalizedName = "camiseta roja m",
                categoryId = categoryId,
                defaultSupplierId = supplierId,
                baseUnitId = 3,
                stockMode = "TRACKED",
                salePrice = 45_000,
                averageCost = 21_000,
                initialCost = 20_000,
                fixedUnitCost = 0,
                stockValue = 105_000,
                onHandMilli = 5_000,
                minStockMilli = 2_000,
                barcode = "7700000000017",
                parentProductId = parentId,
                variantAxis1 = "Color",
                variantAxis2 = "Talla",
                variantValue1 = "Rojo",
                variantValue2 = "M",
                photoMediaId = 77,
                taxRateId = 5,
                replacementCost = 22_000,
                replacementCostAt = LATER,
                targetMarginBp = 4_500,
                referenceCost = 600,
                referencePrice = 1_300,
                priceReviewSince = MUCH_LATER,
                tracksLots = 1,
                expiryNoticeDays = 15,
                archivedAt = null,
                createdAt = CREATED_AT,
                updatedAt = LATER,
            )

        val id = products.insert(variant)

        assertEquals(variant.copy(id = id), products.findById(id))
        assertNull(products.findById(MISSING_ID))
    }

    @Test
    fun moneyAndQuantitiesAreStoredAsExactIntegersAtTheirLimits() = runBlocking {
        // Topes de la base: precio por unidad, valor total del stock y saldo negativo, porque
        // vender nunca se bloquea por falta de stock.
        val atLimits =
            product("Lingote").copy(
                salePrice = Limit.UNIT_MONEY.max,
                averageCost = Limit.UNIT_MONEY.max,
                initialCost = Limit.UNIT_MONEY.max,
                replacementCost = Limit.UNIT_MONEY.max,
                stockValue = Limit.STOCK_VALUE.max,
                onHandMilli = -Limit.ON_HAND_MILLI.max,
                minStockMilli = Limit.ON_HAND_MILLI.max,
            )

        val id = products.insert(atLimits)

        assertEquals(atLimits.copy(id = id), products.findById(id))
        val types =
            count(
                "SELECT COUNT(*) FROM products WHERE id = $id AND typeof(sale_price) = 'integer' " +
                    "AND typeof(average_cost) = 'integer' AND typeof(stock_value) = 'integer' " +
                    "AND typeof(on_hand_milli) = 'integer' AND typeof(min_stock_milli) = 'integer'",
            )
        assertEquals(1L, types)
    }

    @Test
    fun variantNeedsAnExistingParentAndProtectsIt() = runBlocking {
        constraintFailure(FOREIGN_KEY) {
            products.insert(product("Camiseta azul", parentProductId = MISSING_ID))
        }
        val parentId = products.insert(product("Camiseta"))
        val variantId = products.insert(product("Camiseta azul", parentProductId = parentId))

        // El padre no se borra mientras tenga variantes.
        constraintFailure(FOREIGN_KEY) { products.delete(parentId) }
        assertEquals(parentId, products.findById(variantId)?.parentProductId)

        assertEquals(1, products.delete(variantId))
        assertEquals(1, products.delete(parentId))
        assertEquals(0L, count("SELECT COUNT(*) FROM products"))
    }

    @Test
    fun archivedProductDoesNotReserveItsNameNorItsBarcode() = runBlocking {
        val archivedId =
            products.insert(product("Café Molido", barcode = "7701", archivedAt = LATER))
        // Ni el nombre ni el código son índices únicos: la unicidad entre vigentes es de la
        // guarda, que no cuenta los archivados.
        val activeId = products.insert(product("CAFE molido", barcode = "7701"))

        assertEquals(activeId, products.findActiveByNormalizedName("cafe molido")?.id)
        assertEquals(activeId, products.findActiveByBarcode("7701")?.id)
        assertEquals("cafe molido", products.findById(archivedId)?.normalizedName)

        products.setArchivedAt(activeId, archivedAt = MUCH_LATER, updatedAt = MUCH_LATER)

        assertNull(products.findActiveByNormalizedName("cafe molido"))
        assertNull(products.findActiveByBarcode("7701"))
    }

    @Test
    fun activeAndArchivedListsAreSeparateAndOrderedByNormalizedName() = runBlocking {
        val zucchini = products.insert(product("Zucchini"))
        val water = products.insert(product("Água"))
        val bread = products.insert(product("pan"))
        val oldWater = products.insert(product("AGUA", archivedAt = LATER))
        val olderWater = products.insert(product("agua", archivedAt = LATER))

        assertEquals(listOf(water, bread, zucchini), products.observeActive().first().map { it.id })
        // Mismo nombre normalizado: desempata el orden de registro.
        assertEquals(listOf(oldWater, olderWater), products.observeArchived().first().map { it.id })
    }

    @Test
    fun findByIdsReturnsOnlyTheProductsThatExist() = runBlocking {
        val rice = products.insert(product("Arroz"))
        val salt = products.insert(product("Sal"))
        products.insert(product("Azúcar"))

        val found = products.findByIds(listOf(salt, MISSING_ID, rice))

        assertEquals(setOf(rice, salt), found.map { it.id }.toSet())
        assertEquals(emptyList<ProductEntity>(), products.findByIds(emptyList()))
    }

    @Test
    fun editingDetailsNeverTouchesPriceCostsOrStock() = runBlocking {
        val categoryId = database.categoryDao().insert(category("Granos"))
        val id = products.insert(product("Lenteja"))
        // Lo que dejaron el libro de stock, el historial de precios y el archivado.
        products.updateStockState(
            id,
            onHandMilli = 12_500,
            averageCost = 3_100,
            stockValue = 38_750,
            LATER,
        )
        products.updateSalePrice(id, salePrice = 4_900, updatedAt = LATER)
        products.updateReplacementCost(
            id,
            replacementCost = 3_300,
            replacementCostAt = LATER,
            LATER,
        )
        products.setPriceReviewSince(id, since = LATER, updatedAt = LATER)
        val before = requireNotNull(products.findById(id))

        val changed =
            products.updateDetails(
                id = id,
                name = "Lenteja roja",
                normalizedName = "lenteja roja",
                categoryId = categoryId,
                defaultSupplierId = null,
                baseUnitId = 2,
                stockMode = "TRACKED",
                tracksLots = 1,
                fixedUnitCost = 0,
                minStockMilli = 3_000,
                barcode = "7702",
                parentProductId = null,
                variantAxis1 = "Peso",
                variantAxis2 = null,
                variantValue1 = "500 g",
                variantValue2 = null,
                photoMediaId = 9,
                taxRateId = 4,
                targetMarginBp = 3_000,
                referenceCost = 80,
                referencePrice = 150,
                expiryNoticeDays = 20,
                updatedAt = MUCH_LATER,
            )

        assertEquals(1, changed)
        val expected =
            before.copy(
                name = "Lenteja roja",
                normalizedName = "lenteja roja",
                categoryId = categoryId,
                baseUnitId = 2,
                tracksLots = 1,
                minStockMilli = 3_000,
                barcode = "7702",
                variantAxis1 = "Peso",
                variantValue1 = "500 g",
                photoMediaId = 9,
                taxRateId = 4,
                targetMarginBp = 3_000,
                referenceCost = 80,
                referencePrice = 150,
                expiryNoticeDays = 20,
                updatedAt = MUCH_LATER,
            )
        assertEquals(expected, products.findById(id))
    }

    @Test
    fun salePriceChangesAlone() = runBlocking {
        val id = products.insert(product("Galletas"))
        val before = requireNotNull(products.findById(id))

        assertEquals(1, products.updateSalePrice(id, salePrice = 3_100, updatedAt = LATER))

        assertEquals(before.copy(salePrice = 3_100, updatedAt = LATER), products.findById(id))
    }

    @Test
    fun initialCostMovesTheAverageCostWithIt() = runBlocking {
        val id = products.insert(product("Harina"))
        val before = requireNotNull(products.findById(id))

        assertEquals(1, products.updateInitialCost(id, initialCost = 1_750, updatedAt = LATER))

        val expected = before.copy(initialCost = 1_750, averageCost = 1_750, updatedAt = LATER)
        assertEquals(expected, products.findById(id))
    }

    @Test
    fun stockStateChangesOnlyTheLedgerCopies() = runBlocking {
        val id = products.insert(product("Aceite"))
        val before = requireNotNull(products.findById(id))

        val changed =
            products.updateStockState(
                id,
                onHandMilli = 24_000,
                averageCost = 417,
                stockValue = 10_000,
                updatedAt = LATER,
            )

        assertEquals(1, changed)
        // El caso de 24 unidades por 10.000: el valor total se guarda exacto, aunque el promedio
        // redondee. El costo inicial no cambia: es el punto de partida de la reproducción.
        val expected =
            before.copy(
                onHandMilli = 24_000,
                averageCost = 417,
                stockValue = 10_000,
                updatedAt = LATER,
            )
        assertEquals(expected, products.findById(id))
    }

    @Test
    fun replacementCostReviewAndArchiveChangeOnlyTheirColumns() = runBlocking {
        val id = products.insert(product("Leche"))
        val before = requireNotNull(products.findById(id))

        products.updateReplacementCost(
            id,
            replacementCost = 2_900,
            replacementCostAt = LATER,
            LATER,
        )
        products.setPriceReviewSince(id, since = LATER, updatedAt = LATER)
        products.setArchivedAt(id, archivedAt = LATER, updatedAt = LATER)
        val expected =
            before.copy(
                replacementCost = 2_900,
                replacementCostAt = LATER,
                priceReviewSince = LATER,
                archivedAt = LATER,
                updatedAt = LATER,
            )
        assertEquals(expected, products.findById(id))

        // Nulo saca de la cola de revisión, reactiva, y deja el costo de reposición sin fecha.
        products.updateReplacementCost(
            id,
            replacementCost = 0,
            replacementCostAt = null,
            MUCH_LATER,
        )
        products.setPriceReviewSince(id, since = null, updatedAt = MUCH_LATER)
        products.setArchivedAt(id, archivedAt = null, updatedAt = MUCH_LATER)
        assertEquals(before.copy(updatedAt = MUCH_LATER), products.findById(id))
    }

    @Test
    fun writesToAMissingProductChangeNothing() = runBlocking {
        assertEquals(0, products.updateSalePrice(MISSING_ID, 1_000, LATER))
        assertEquals(0, products.updateInitialCost(MISSING_ID, 1_000, LATER))
        assertEquals(0, products.updateStockState(MISSING_ID, 1_000, 1_000, 1_000, LATER))
        assertEquals(0, products.setArchivedAt(MISSING_ID, LATER, LATER))
        assertEquals(0, products.delete(MISSING_ID))
    }

    @Test
    fun productEmitsAgainAfterEachCommitAndNullOnceDeleted() = runBlocking {
        val id = products.insert(product("Panela"))
        val created = product("Panela").copy(id = id)
        val emissions = Emissions(this, products.observeById(id))
        try {
            emissions.awaitValue(created)

            products.updateStockState(
                id,
                onHandMilli = 6_000,
                averageCost = 2_000,
                stockValue = 12_000,
                LATER,
            )
            val stocked = created.copy(
                onHandMilli = 6_000,
                averageCost = 2_000,
                stockValue = 12_000,
                updatedAt = LATER,
            )
            emissions.awaitValue(stocked)

            products.delete(id)
            emissions.awaitValue(null)
        } finally {
            emissions.stop()
        }
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
