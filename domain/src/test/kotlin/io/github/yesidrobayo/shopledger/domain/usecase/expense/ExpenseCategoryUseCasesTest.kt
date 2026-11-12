package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crear, renombrar, archivar, reactivar y eliminar categorías de gasto, cada operación en una sola
 * transacción. Los nombres son inventados.
 */
class ExpenseCategoryUseCasesTest {
    private val world = ExpenseWorld()
    private val rent get() = world.seeded.getValue("RENT")
    private val other get() = world.seeded.getValue("OTHER")

    private fun stored(id: Long) = world.database.tables.value.expenseCategories[id]

    private suspend fun created(name: String, isFixed: Boolean = false): Long {
        val result = world.save()(editingId = null, ExpenseCategoryDraft(name, isFixed))
        return (result as SaveExpenseCategoryResult.Saved).categoryId
    }

    private suspend fun spend(categoryId: Long): Long {
        val result = world.register()(ExpenseRequest(Money(1_000), categoryId))
        return (result as RegisterExpenseResult.Registered).expenseId
    }

    @Test
    fun `a new base has the nine initial categories, and only other is protected`() = runTest {
        val active = world.categories.observe(ArchiveFilter.ACTIVE).first()

        assertEquals(
            listOf(
                "RENT",
                "UTILITIES",
                "INTERNET_PHONE",
                "TRANSPORT",
                "PACKAGING_SUPPLIES",
                "ADVERTISING",
                "TAXES_FEES",
                "WAGES",
                "OTHER",
            ),
            active.map { it.systemKey },
        )
        assertEquals(listOf("OTHER"), active.filter { it.isSystem }.map { it.systemKey })
    }

    @Test
    fun `a new category is stored trimmed and normalized, in one change`() = runTest {
        val id = created("  Fumigación ", isFixed = true)

        val category = requireNotNull(stored(id))
        assertEquals(
            ExpenseCategoryDetails("Fumigación", "fumigacion", isFixed = true),
            category.details,
        )
        assertNull(category.systemKey)
        assertFalse(category.isSystem)
        assertEquals(ExpenseWorld.NOW, category.createdAt)
        assertEquals(1L, world.database.tables.value.changeSeq)
    }

    @Test
    fun `a name already used by an active category is rejected, without accents or case`() =
        runTest {
            created("Fumigación")

            assertEquals(
                SaveExpenseCategoryResult.DuplicateName,
                world.save()(null, ExpenseCategoryDraft("FUMIGACION")),
            )
            assertEquals(1L, world.database.tables.value.changeSeq)
        }

    @Test
    fun `an archived category does not keep its name`() = runTest {
        val old = created("Fumigación")
        world.archive()(old)

        val again = created("fumigación")

        assertTrue(again != old)
        assertEquals("fumigación", stored(again)?.details?.name)
    }

    @Test
    fun `an empty or long name is rejected with its error`() = runTest {
        assertEquals(
            SaveExpenseCategoryResult.Invalid(setOf(ExpenseCategoryInputError.NAME_EMPTY)),
            world.save()(null, ExpenseCategoryDraft("   ")),
        )
        assertEquals(
            SaveExpenseCategoryResult.Invalid(setOf(ExpenseCategoryInputError.NAME_TOO_LONG)),
            world.save()(null, ExpenseCategoryDraft("x".repeat(41))),
        )
        assertEquals(0L, world.database.tables.value.changeSeq)
    }

    @Test
    fun `an initial category takes its own name, and blank brings back the translated one`() =
        runTest {
            assertEquals(
                SaveExpenseCategoryResult.Saved(rent),
                world.save()(rent, ExpenseCategoryDraft("Arriendo del local", isFixed = true)),
            )
            assertEquals(
                ExpenseCategoryDetails("Arriendo del local", "arriendo del local", isFixed = true),
                stored(rent)?.details,
            )
            assertEquals("RENT", stored(rent)?.systemKey)

            assertEquals(
                SaveExpenseCategoryResult.Saved(rent),
                world.save()(rent, ExpenseCategoryDraft("", isFixed = true)),
            )
            assertEquals(ExpenseCategoryDetails(null, null, isFixed = true), stored(rent)?.details)
            assertEquals(2L, world.database.tables.value.changeSeq)
        }

    @Test
    fun `a category the person created needs a name`() = runTest {
        val id = created("Fumigación")

        assertEquals(
            SaveExpenseCategoryResult.Invalid(setOf(ExpenseCategoryInputError.NAME_EMPTY)),
            world.save()(id, ExpenseCategoryDraft("")),
        )
        assertEquals("Fumigación", stored(id)?.details?.name)
    }

    @Test
    fun `renaming to its own name with other case is not a clash`() = runTest {
        val id = created("Fumigación")

        assertEquals(
            SaveExpenseCategoryResult.Saved(id),
            world.save()(id, ExpenseCategoryDraft("FUMIGACIÓN")),
        )
        assertEquals("FUMIGACIÓN", stored(id)?.details?.name)
    }

    @Test
    fun `renaming to the name of another active category is rejected`() = runTest {
        created("Fumigación")

        assertEquals(
            SaveExpenseCategoryResult.DuplicateName,
            world.save()(rent, ExpenseCategoryDraft("fumigacion")),
        )
        assertNull(stored(rent)?.details?.name)
    }

    @Test
    fun `an archived category can take a name in use, and then it cannot come back`() = runTest {
        created("Fumigación")
        val archived = created("Limpieza")
        world.archive()(archived)

        assertEquals(
            SaveExpenseCategoryResult.Saved(archived),
            world.save()(archived, ExpenseCategoryDraft("Fumigacion")),
        )
        assertEquals(ExpenseCategoryLifecycleResult.NameTaken, world.reactivate()(archived))
        assertTrue(stored(archived)?.isArchived == true)
    }

    @Test
    fun `saving without changes writes nothing`() = runTest {
        val id = created("Fumigación")
        val changes = world.database.tables.value.changeSeq

        assertEquals(
            SaveExpenseCategoryResult.Saved(id),
            world.save()(id, ExpenseCategoryDraft(" Fumigación ")),
        )
        assertEquals(changes, world.database.tables.value.changeSeq)
        assertEquals(0, world.database.lastTransactionWrites)
    }

    @Test
    fun `editing a missing category says so`() = runTest {
        assertEquals(
            SaveExpenseCategoryResult.NotFound,
            world.save()(999L, ExpenseCategoryDraft("Fumigación")),
        )
    }

    @Test
    fun `a storage failure while saving comes back as a result`() = runTest {
        world.database.failAtWrite = 1
        world.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        assertEquals(
            SaveExpenseCategoryResult.StorageFailed(StorageFailure.DISK_FULL),
            world.save()(null, ExpenseCategoryDraft("Fumigación")),
        )
    }

    @Test
    fun `other is never archived nor deleted`() = runTest {
        assertEquals(ExpenseCategoryLifecycleResult.Protected, world.archive()(other))
        assertEquals(ExpenseCategoryLifecycleResult.Protected, world.delete()(other))
        assertFalse(stored(other)?.isArchived == true)
        assertEquals(0L, world.database.tables.value.changeSeq)
    }

    @Test
    fun `a category with expenses, even voided, is archived and not deleted`() = runTest {
        val expense = spend(rent)
        world.void()(expense, VoidReason.ENTRY_ERROR, null)

        assertEquals(ExpenseCategoryLifecycleResult.HasExpenses, world.delete()(rent))
        assertEquals(ExpenseCategoryLifecycleResult.Done, world.archive()(rent))

        val archived = requireNotNull(stored(rent))
        assertEquals(ExpenseWorld.NOW, archived.archivedAt)
        assertEquals(
            listOf(rent),
            world.categories.observe(ArchiveFilter.ARCHIVED).first().map { it.id },
        )
        // El gasto sigue ligado a ella.
        assertEquals(
            rent,
            world.database.tables.value.expenses.getValue(expense).content.expenseCategoryId,
        )
    }

    @Test
    fun `a category without expenses is deleted`() = runTest {
        val id = created("Fumigación")

        assertEquals(ExpenseCategoryLifecycleResult.Done, world.delete()(id))
        assertNull(stored(id))
        assertEquals(ExpenseCategoryLifecycleResult.NotFound, world.delete()(id))
    }

    @Test
    fun `archiving twice or reactivating an active one changes nothing`() = runTest {
        world.archive()(rent)
        val changes = world.database.tables.value.changeSeq

        assertEquals(ExpenseCategoryLifecycleResult.Unchanged, world.archive()(rent))
        assertEquals(ExpenseCategoryLifecycleResult.Unchanged, world.reactivate()(other))
        assertEquals(changes, world.database.tables.value.changeSeq)
    }

    @Test
    fun `reactivating brings the category back to new expenses`() = runTest {
        spend(rent)
        world.archive()(rent)

        assertEquals(ExpenseCategoryLifecycleResult.Done, world.reactivate()(rent))

        assertNull(stored(rent)?.archivedAt)
        assertTrue(
            world.register()(ExpenseRequest(Money(10), rent)) is RegisterExpenseResult.Registered,
        )
    }

    @Test
    fun `an initial category without its own name never clashes when it comes back`() = runTest {
        world.archive()(rent)
        created("Alquiler")

        assertEquals(ExpenseCategoryLifecycleResult.Done, world.reactivate()(rent))
    }

    @Test
    fun `missing categories and storage failures come back as results`() = runTest {
        assertEquals(ExpenseCategoryLifecycleResult.NotFound, world.archive()(999L))
        assertEquals(ExpenseCategoryLifecycleResult.NotFound, world.reactivate()(999L))

        world.database.failAtWrite = 1
        world.database.failure = { StorageFailureException(StorageFailure.IO_ERROR) }
        assertEquals(
            ExpenseCategoryLifecycleResult.StorageFailed(StorageFailure.IO_ERROR),
            world.archive()(rent),
        )
        assertNull(stored(rent)?.archivedAt)
    }
}
