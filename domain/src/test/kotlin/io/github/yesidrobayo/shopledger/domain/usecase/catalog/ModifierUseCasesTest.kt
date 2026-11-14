package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.ModifierIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductModifier
import io.github.yesidrobayo.shopledger.domain.port.ProductModifierRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ModifierRules
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Los extras de un producto: qué puede ser extra, guardar solo lo que cambió y en su orden, y la
 * lectura de la pantalla, con lo que se puede agregar. Productos inventados.
 */
class ModifierUseCasesTest {
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val products = FakeProductRepository(database)
    private val modifiers = FakeModifiers()
    private val clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS)

    private val save = SaveModifiersUseCase(transactions, products, modifiers, clock)

    init {
        database.seed {
            it.copy(
                products = listOf(
                    product(AREPA, name = "Arepa"),
                    product(CHEESE, name = "Queso"),
                    product(SAUCE, name = "Salsa"),
                    product(HONEY, name = "Miel"),
                    product(OLD, name = "Antiguo", archived = true),
                    product(SHIRT, name = "Camisa"),
                    product(RED_SHIRT, name = "Camisa roja").let { red ->
                        red.copy(details = red.details.copy(parentProductId = SHIRT))
                    },
                ).associateBy { product -> product.id },
                catalogLinks = mapOf(SHIRT to setOf(CatalogLink.VARIANT_PARENT)),
            )
        }
    }

    @Test
    fun savingKeepsTheChosenOrderAndCountsAChange() = runTest {
        val result = save(AREPA, listOf(SAUCE, CHEESE))

        assertEquals(SaveModifiersResult.Saved, result)
        assertEquals(listOf(SAUCE to 0L, CHEESE to 1L), stored())
        assertEquals(1, transactions.commits)
    }

    @Test
    fun savingOnlyWritesWhatChanged() = runTest {
        save(AREPA, listOf(CHEESE, SAUCE))
        val writes = modifiers.writes

        save(AREPA, listOf(SAUCE, HONEY))

        // Se quita el queso, la salsa sube al primer lugar y la miel entra al final.
        assertEquals(listOf(SAUCE to 0L, HONEY to 1L), stored())
        assertEquals(writes + 3, modifiers.writes)
    }

    @Test
    fun theSameExtrasWriteNothingAndCountNoChange() = runTest {
        save(AREPA, listOf(CHEESE))

        val result = save(AREPA, listOf(CHEESE))

        assertEquals(SaveModifiersResult.Saved, result)
        assertEquals(1, transactions.commits)
        assertEquals(1, modifiers.writes)
    }

    @Test
    fun eachInvalidChoiceIsRejectedWithoutWriting() = runTest {
        assertEquals(invalid(ModifierIssue.SAME_PRODUCT), save(AREPA, listOf(AREPA)))
        assertEquals(invalid(ModifierIssue.UNAVAILABLE), save(AREPA, listOf(OLD)))
        assertEquals(invalid(ModifierIssue.UNAVAILABLE), save(AREPA, listOf(MISSING)))
        assertEquals(invalid(ModifierIssue.HAS_VARIANTS), save(AREPA, listOf(SHIRT)))
        assertEquals(invalid(ModifierIssue.DUPLICATE), save(AREPA, listOf(CHEESE, CHEESE)))
        assertEquals(SaveModifiersResult.NotFound, save(MISSING, listOf(CHEESE)))
        assertEquals(0, modifiers.writes)
    }

    @Test
    fun moreExtrasThanTheLimitAreRejected() = runTest {
        val many = (1L..ModifierRules.MAX_MODIFIERS + 1L).map { 100L + it }

        assertEquals(
            ModifierIssue.TOO_MANY,
            ModifierRules.check(AREPA, many, emptyMap(), emptySet()),
        )
    }

    @Test
    fun onlyAnotherActiveProductWithoutVariantsCanBeOffered() {
        val variants = setOf(SHIRT)

        assertEquals(true, ModifierRules.canOffer(AREPA, product(CHEESE), variants))
        assertEquals(false, ModifierRules.canOffer(AREPA, product(AREPA), variants))
        assertEquals(false, ModifierRules.canOffer(AREPA, product(OLD, archived = true), variants))
        assertEquals(false, ModifierRules.canOffer(AREPA, product(SHIRT), variants))
    }

    @Test
    fun aStorageFailureIsAResult() = runTest {
        modifiers.failing = true

        val result = save(AREPA, listOf(CHEESE))

        assertEquals(SaveModifiersResult.StorageFailed(StorageFailure.IO_ERROR), result)
    }

    @Test
    fun theEditorOffersOnlyWhatCanBeSoldAloneAndNotYetChosen() = runTest {
        save(AREPA, listOf(CHEESE))
        archive(CHEESE)

        val data = ObserveModifierEditorUseCase(products, modifiers)(AREPA).first()

        val editor = requireNotNull(data)
        // El queso archivado sigue en la lista para poder quitarlo.
        assertEquals(listOf(CHEESE), editor.extras.map { it.id })
        // Ni la arepa misma, ni lo archivado, ni el principal de unas variantes; la variante sí.
        assertEquals(
            listOf("Camisa roja", "Miel", "Salsa"),
            editor.candidates.map { it.details.name },
        )
        assertNull(ObserveModifierEditorUseCase(products, modifiers)(MISSING).first())
    }

    private fun invalid(issue: ModifierIssue) = SaveModifiersResult.Invalid(issue)

    private fun stored(): List<Pair<Long, Long>> =
        modifiers.rows.value.filter { it.productId == AREPA }
            .sortedBy { it.sortOrder }
            .map { it.modifierProductId to it.sortOrder }

    private fun archive(productId: Long) = database.seed { tables ->
        val archived = tables.products.getValue(productId).copy(archivedAt = SaleWorld.EPOCH)
        tables.copy(products = tables.products + (productId to archived))
    }

    /** Los extras en memoria, con cuántas escrituras recibieron y una falla de la base a pedido. */
    private class FakeModifiers : ProductModifierRepository {
        val rows = MutableStateFlow(emptyList<ProductModifier>())
        var writes = 0
        var failing = false
        private var nextId = 1L

        override fun observeAll(): Flow<List<ProductModifier>> = rows

        override fun observeByProduct(productId: Long): Flow<List<ProductModifier>> =
            rows.map { all -> all.filter { it.productId == productId }.sortedBy { it.sortOrder } }

        override suspend fun findByProduct(productId: Long): List<ProductModifier> =
            rows.value.filter { it.productId == productId }.sortedBy { it.sortOrder }

        override suspend fun insert(
            productId: Long,
            modifierProductId: Long,
            sortOrder: Long,
            createdAt: Instant,
        ): Long {
            written()
            val id = nextId++
            rows.value += ProductModifier(id, productId, modifierProductId, null, sortOrder)
            return id
        }

        override suspend fun updateOrder(id: Long, sortOrder: Long, updatedAt: Instant) {
            written()
            rows.value = rows.value.map { if (it.id == id) it.copy(sortOrder = sortOrder) else it }
        }

        override suspend fun delete(id: Long) {
            written()
            rows.value = rows.value.filterNot { it.id == id }
        }

        private fun written() {
            if (failing) throw StorageFailureException(StorageFailure.IO_ERROR)
            writes++
        }
    }

    private companion object {
        const val AREPA = 1L
        const val CHEESE = 2L
        const val SAUCE = 3L
        const val HONEY = 4L
        const val OLD = 5L
        const val SHIRT = 6L
        const val RED_SHIRT = 7L
        const val MISSING = 99L
    }
}
