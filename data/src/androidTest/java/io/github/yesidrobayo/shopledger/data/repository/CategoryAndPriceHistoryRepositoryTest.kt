package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRule
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Las categorías de producto y el historial de precios, que solo crece. */
@RunWith(AndroidJUnit4::class)
internal class CategoryAndPriceHistoryRepositoryTest : RepositoryTest() {
    private val categories by lazy { RoomCategoryRepository(database) }
    private val history by lazy { RoomPriceHistoryRepository(database) }

    @Test
    fun categoriesEmitByNameAndLeaveTheirProductsWithoutCategoryOnDelete() = runBlocking {
        categories.observeAll().test {
            assertEquals(emptyList<Category>(), awaitItem())

            val (drinks, bakery) =
                commit {
                    // La segunda primero: la lista va por nombre, no por orden de creación.
                    val bakery = categories.insert(
                        CategoryDetails("Panadería", "panaderia", null),
                        AT,
                    )
                    categories.insert(
                        CategoryDetails("Bebidas", "bebidas", BasisPoints(3_000)),
                        AT,
                    ) to
                        bakery
                }
            assertEquals(
                listOf("bebidas", "panaderia"),
                awaitItem().map {
                    it.details.normalizedName
                },
            )

            commit {
                categories.update(
                    bakery,
                    CategoryDetails("Aguas", "aguas", BasisPoints(1_000)),
                    LATER_AT,
                )
            }
            assertEquals(listOf("aguas", "bebidas"), awaitItem().map { it.details.normalizedName })
            assertEquals(
                Category(
                    bakery,
                    CategoryDetails("Aguas", "aguas", BasisPoints(1_000)),
                    AT,
                    LATER_AT,
                ),
                categories.findByNormalizedName("aguas"),
            )

            val water = database.productDao().insert(product("Agua", categoryId = drinks))
            assertEquals(1, categories.countProducts(drinks))
            commit { categories.delete(drinks) }
            assertEquals(listOf("aguas"), awaitItem().map { it.details.normalizedName })
            assertNull(categories.findById(drinks))
            assertNull(database.productDao().findById(water)?.categoryId)
            cancelAndIgnoreRemainingEvents()
        }
        // Un nombre repetido o una categoría que no existe no se escriben.
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf {
                commit { categories.insert(CategoryDetails("Aguas", "aguas", null), AT) }
            },
        )
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf {
                commit { categories.delete(MISSING_ID) }
            },
        )
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf {
                commit { categories.update(MISSING_ID, CategoryDetails("X", "x", null), AT) }
            },
        )
    }

    @Test
    fun everyKindOfPriceChangeComesBackAsItWasWritten() = runBlocking {
        val batch = Uuid.parse("0f3c5e2a-9b1d-4c7e-8a60-2d4b6f8e1a3c")
        val undo = Uuid.parse("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")
        val rules =
            listOf(
                BulkPriceAdjustment.Percent(BasisPoints(1_000)),
                BulkPriceAdjustment.Amount(Money(-200)),
                BulkPriceAdjustment.TargetMargin(BasisPoints(3_000)),
                BulkPriceAdjustment.ReferenceRate(4_100_000_000),
            )
        val changes =
            listOf(
                change(PriceChangeSource.INITIAL, oldPrice = null),
                change(PriceChangeSource.MANUAL),
            ) +
                rules.map {
                    change(
                        PriceChangeSource.BULK,
                        batchId = batch,
                        rule = BulkPriceRule(it, Money(50)),
                    )
                } +
                listOf(
                    change(PriceChangeSource.BULK_UNDO, batchId = undo),
                    change(PriceChangeSource.IMPORT),
                )

        history.observeByProduct(productId).test {
            assertEquals(emptyList<Any>(), awaitItem())
            val ids = commit { changes.map { history.append(it) } }

            assertEquals(changes.zip(ids) { change, id -> change.recordedAs(id) }, awaitItem())
            assertEquals(ids.subList(2, 6), history.findByBatch(batch).map { it.id })
            assertEquals(listOf(ids[6]), history.findByBatch(undo).map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun change(
        source: PriceChangeSource,
        oldPrice: Money? = Money(2_000),
        batchId: Uuid? = null,
        rule: BulkPriceRule? = null,
    ) = NewPriceChange(
        productId = productId,
        priceListId = priceListId,
        oldPrice = oldPrice,
        newPrice = Money(2_500),
        source = source,
        batchId = batchId,
        bulkRule = rule,
        occurredAt = AT,
        businessDate = DATE,
    )

    private companion object {
        const val MISSING_ID = 999L
    }
}
