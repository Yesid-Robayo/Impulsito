package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.Row
import io.github.yesidrobayo.shopledger.data.local.batchLineRow
import io.github.yesidrobayo.shopledger.data.local.batchRow
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductComponentEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.category
import io.github.yesidrobayo.shopledger.data.local.catalog.manualChange
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.catalog.supplier
import io.github.yesidrobayo.shopledger.data.local.countLineRow
import io.github.yesidrobayo.shopledger.data.local.countSessionRow
import io.github.yesidrobayo.shopledger.data.local.document.order
import io.github.yesidrobayo.shopledger.data.local.document.orderLine
import io.github.yesidrobayo.shopledger.data.local.document.quote
import io.github.yesidrobayo.shopledger.data.local.document.quoteLine
import io.github.yesidrobayo.shopledger.data.local.locationRow
import io.github.yesidrobayo.shopledger.data.local.purchase
import io.github.yesidrobayo.shopledger.data.local.purchaseLine
import io.github.yesidrobayo.shopledger.data.local.purchaseReturn
import io.github.yesidrobayo.shopledger.data.local.purchaseReturnLine
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleLine
import io.github.yesidrobayo.shopledger.data.local.saleLineComponent
import io.github.yesidrobayo.shopledger.data.local.stock.lot
import io.github.yesidrobayo.shopledger.data.local.transferLineRow
import io.github.yesidrobayo.shopledger.data.local.transferRow
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLowStockAlert
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Los productos: lo que se guarda, lo que se lee, cuándo emiten, y qué cuenta como historia. */
@RunWith(AndroidJUnit4::class)
internal class ProductRepositoryTest : RepositoryTest() {
    private lateinit var products: RoomProductRepository

    @Before
    fun createRepository() {
        products = RoomProductRepository(database)
    }

    @Test
    fun aProductComesBackAsItWasWritten() = runBlocking {
        val categoryId = database.categoryDao().insert(category("Bebidas"))
        val supplierId = database.supplierDao().insert(supplier("Distribuidora Andina"))
        val details =
            productDetails("Té verde", minStock = 2_500, barcode = "7700000000017").copy(
                categoryId = categoryId,
                defaultSupplierId = supplierId,
                targetMargin = BasisPoints(3_500),
                referenceCost = Money(150),
                referencePrice = Money(300),
                expiryNoticeDays = 15,
            )
        // Nanosegundos que la base no guarda: el instante vuelve truncado al milisegundo.
        val restockedAt = Instant.fromEpochSeconds(AT.epochSeconds, 123_456_789)
        val new =
            newProduct("Té verde", details).copy(
                replacementCost = Money(1_300),
                replacementCostAt = restockedAt,
            )

        val id = commit { products.insert(new) }

        val expected =
            Product(
                id = id,
                details = details,
                salePrice = Money(2_500),
                initialCost = Money(1_200),
                averageCost = Money(1_200),
                stockValue = Money.ZERO,
                onHand = QuantityMilli.ZERO,
                replacementCost = Money(1_300),
                replacementCostAt = Instant.fromEpochMilliseconds(AT.toEpochMilliseconds() + 123),
                priceReviewSince = null,
                archivedAt = null,
                createdAt = AT,
                updatedAt = AT,
            )
        assertEquals(expected, products.findById(id))
        assertEquals(expected, products.findActiveByNormalizedName("te verde"))
        assertEquals(expected, products.findActiveByBarcode("7700000000017"))
        assertEquals(listOf(id), products.findByIds(listOf(id, id, MISSING_ID)).map { it.id })
        assertEquals(emptyList<Product>(), products.findByIds(emptyList()))
        assertNull(products.findById(MISSING_ID))
        assertEquals(1L, changeSeq())
    }

    @Test
    fun theListsEmitEachConfirmedChangeByFilter() = runBlocking {
        products.observe(ArchiveFilter.ACTIVE).test {
            assertEquals(listOf("Café"), awaitItem().map { it.details.name })

            val rice = commit { products.insert(newProduct("Arroz")) }
            assertEquals(listOf("Arroz", "Café"), awaitItem().map { it.details.name })

            commit { products.setArchivedAt(rice, LATER_AT, LATER_AT) }
            assertEquals(listOf("Café"), awaitItem().map { it.details.name })
            assertEquals(
                listOf(rice),
                products.observe(ArchiveFilter.ARCHIVED).first().map {
                    it.id
                },
            )
            // Archivado no reserva su nombre ni sale entre los vigentes.
            assertNull(products.findActiveByNormalizedName("arroz"))

            commit { products.setArchivedAt(rice, null, LATER_AT) }
            assertEquals(listOf("Arroz", "Café"), awaitItem().map { it.details.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun oneProductEmitsOnlyWhenItChanges() = runBlocking {
        val rice = commit { products.insert(newProduct("Arroz")) }

        products.observeById(rice).test {
            assertEquals(Money(2_500), awaitItem()?.salePrice)
            // Otro producto cambia la tabla, no a este: no vuelve a emitir lo mismo.
            commit { products.updateSalePrice(productId, Money(9_900), LATER_AT) }
            commit { products.updateSalePrice(rice, Money(2_800), LATER_AT) }
            val changed = awaitItem()
            assertEquals(Money(2_800), changed?.salePrice)
            assertEquals(LATER_AT, changed?.updatedAt)

            commit { products.delete(rice) }
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun eachUpdateTouchesOnlyItsColumns() = runBlocking {
        val details = productDetails(
            "Café molido",
            minStock = 1_000,
        ).copy(taxRateId = null, expiryNoticeDays = 30, usage = ProductUsage.BOTH)

        commit {
            products.updateDetails(productId, details, LATER_AT)
            products.updateReplacementCost(productId, Money(1_400), LATER_AT, LATER_AT)
            products.setPriceReviewSince(productId, LATER_AT, LATER_AT)
        }

        val product = requireNotNull(products.findById(productId))
        assertEquals(details, product.details)
        assertEquals(Money(1_400), product.replacementCost)
        assertEquals(LATER_AT, product.replacementCostAt)
        assertEquals(LATER_AT, product.priceReviewSince)
        // El precio y el stock no se tocan con la edición.
        assertEquals(Money(2_500), product.salePrice)
        assertEquals(QuantityMilli.ZERO, product.onHand)

        commit { products.setPriceReviewSince(productId, null, LATER_AT) }
        assertNull(products.findById(productId)?.priceReviewSince)
    }

    @Test
    fun aProductIsBornWithItsUsageAndAnInputIsReadBack() = runBlocking {
        val details = productDetails("Harina").copy(usage = ProductUsage.INPUT)
        val flour = commit { products.insert(newProduct("Harina", details)) }

        assertEquals(ProductUsage.INPUT, products.findById(flour)?.details?.usage)
        // El producto de la base nació sin decir su uso: se vende.
        assertEquals(ProductUsage.SALE, products.findById(productId)?.details?.usage)
        // En la base va por su nombre, como cualquier enum.
        assertEquals("INPUT", database.productDao().findById(flour)?.usage)
    }

    @Test
    fun theInitialCostMovesTheAverageOnlyWithoutLedgerRows() = runBlocking {
        commit { products.updateInitialCost(productId, Money(1_750), LATER_AT) }
        val before = requireNotNull(products.findById(productId))
        assertEquals(
            listOf(Money(1_750), Money(1_750)),
            listOf(before.initialCost, before.averageCost),
        )

        commit { RoomStockLedgerRepository(database).append(openingRow(productId)) }
        val seq = changeSeq()
        val failure =
            storageFailureOf {
                commit { products.updateInitialCost(productId, Money(900), LATER_AT) }
            }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(Money(1_200), products.findById(productId)?.averageCost)
        assertEquals(seq, changeSeq())
    }

    @Test
    fun aWriteOnAMissingProductOrARepeatedNameIsATypedRejection() = runBlocking {
        val writes: List<suspend () -> Unit> =
            listOf(
                { products.updateDetails(MISSING_ID, productDetails("Nada"), LATER_AT) },
                { products.updateSalePrice(MISSING_ID, Money(1), LATER_AT) },
                { products.updateReplacementCost(MISSING_ID, Money(1), null, LATER_AT) },
                { products.setPriceReviewSince(MISSING_ID, LATER_AT, LATER_AT) },
                { products.setArchivedAt(MISSING_ID, LATER_AT, LATER_AT) },
                { products.delete(MISSING_ID) },
                // Dos vigentes con el mismo nombre: lo impide la guarda de la tabla.
                { products.insert(newProduct("Café")) },
            )
        for (write in writes) {
            assertEquals(
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf {
                    commit { write() }
                },
            )
        }
        assertEquals(0L, changeSeq())
    }

    @Test
    fun deletingWithoutHistoryTakesItsPricesAndAlertsAlong() = runBlocking {
        val rice = commit { products.insert(newProduct("Arroz")) }
        database.priceHistoryDao().insert(
            manualChange(rice, priceListId, oldPrice = 2_000, newPrice = 2_500),
        )
        commit {
            RoomLowStockAlertRepository(database, RestoreLock { false }).insert(
                NewLowStockAlert(
                    rice,
                    QuantityMilli.ZERO,
                    QuantityMilli(1_000),
                    LowStockNotificationStatus.SUPPRESSED,
                    AT,
                    DATE,
                ),
            )
        }
        assertFalse(products.hasHistory(rice))

        commit { products.delete(rice) }

        assertNull(products.findById(rice))
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM product_price_history WHERE product_id = $rice"),
        )
        assertEquals(0L, count("SELECT COUNT(*) FROM low_stock_alerts WHERE product_id = $rice"))
    }

    @Test
    fun everyDocumentAndLedgerTableCountsAsHistory() = runBlocking {
        val warehouse = insertRow("locations", locationRow("Bodega"))
        val cases: Map<String, suspend (Long) -> Unit> =
            mapOf(
                "libro de stock" to
                    { id -> commit { RoomStockLedgerRepository(database).append(openingRow(id)) } },
                "lote" to { id ->
                    execute("UPDATE products SET tracks_lots = 1 WHERE id = $id")
                    database.stockLotDao().insert(lot(id, "L-01"))
                },
                "línea de venta" to { id ->
                    val saleId = database.saleDao().insert(sale(priceListId))
                    database.saleLineDao().insert(saleLine(saleId, id, priceListId))
                },
                "componente vendido" to { id ->
                    val saleId = database.saleDao().insert(sale(priceListId))
                    val combo = database.productDao().insert(
                        product("Combo $id").copy(stockMode = "COMPONENTS"),
                    )
                    val line = database.saleLineDao().insert(
                        saleLine(saleId, combo, priceListId).copy(stockEffect = "COMPONENTS"),
                    )
                    database.saleLineComponentDao().insert(saleLineComponent(line, id))
                },
                "línea de compra" to { id ->
                    val purchaseId = database.purchaseDao().insert(purchase(supplierId = null))
                    database.purchaseLineDao().insert(purchaseLine(purchaseId, id))
                },
                "devolución a proveedor" to { id ->
                    val returnId = database.purchaseReturnDao().insert(purchaseReturn())
                    database.purchaseReturnLineDao().insert(purchaseReturnLine(returnId, id))
                },
                "cotización" to
                    { id ->
                        database.quoteDao().insertLine(
                            quoteLine(database.quoteDao().insert(quote()), id),
                        )
                    },
                "pedido" to
                    { id ->
                        database.orderDao().insertLine(
                            orderLine(database.orderDao().insert(order()), id),
                        )
                    },
                "transferencia" to { id ->
                    val transfer = insertRow("stock_transfers", transferRow(1, warehouse))
                    insertRow("stock_transfer_lines", transferLineRow(transfer, id))
                },
                "conteo" to
                    { id ->
                        insertRow(
                            "count_session_lines",
                            countLineRow(insertRow("count_sessions", countSessionRow()), id),
                        )
                    },
                "producción" to { id -> insertRow("production_batches", batchRow(id)) },
                "insumo de producción" to { id ->
                    val made = database.productDao().insert(product("Hecho $id"))
                    insertRow(
                        "production_batch_lines",
                        batchLineRow(insertRow("production_batches", batchRow(made)), id),
                    )
                },
            )
        for ((case, addHistory) in cases) {
            val id = database.productDao().insert(product("Producto de $case"))
            assertFalse(case, products.hasHistory(id))

            addHistory(id)

            assertTrue(case, products.hasHistory(id))
            // Con historia la llave rechaza el borrado, y llega tipado, sin el error de SQLite.
            assertEquals(
                case,
                StorageFailure.INVARIANT_VIOLATION,
                storageFailureOf {
                    commit { products.delete(id) }
                },
            )
        }
    }

    @Test
    fun aProductUsedByAComboIsNotHistoryButStillNotDeleted() = runBlocking {
        val combo = database.productDao().insert(product("Desayuno").copy(stockMode = "COMPONENTS"))
        database.productComponentDao().insert(
            ProductComponentEntity(
                parentProductId = combo,
                componentProductId = productId,
                quantityMilli = 1_000,
                createdAt = AT.toEpochMilliseconds(),
                updatedAt = AT.toEpochMilliseconds(),
            ),
        )

        assertFalse(products.hasHistory(productId))
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf {
                commit { products.delete(productId) }
            },
        )
    }

    private suspend fun insertRow(table: String, row: Row): Long {
        execute(row.insertInto(table))
        return count("SELECT max(id) FROM $table")
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
