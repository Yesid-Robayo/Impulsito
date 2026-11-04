package io.github.yesidrobayo.shopledger.domain.model.expense

import kotlin.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseCategoryTest {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun category(systemKey: String?, name: String?, archivedAt: Instant? = null) =
        ExpenseCategory(
            id = 1L,
            systemKey = systemKey,
            details = ExpenseCategoryDetails(name, name?.lowercase(), isFixed = false),
            isSystem = false,
            archivedAt = archivedAt,
            createdAt = at,
            updatedAt = at,
        )

    @Test
    fun `an initial category may go without a name of its own`() {
        // Se muestra con el texto traducido de su clave hasta que la persona la renombra.
        category(systemKey = "RENT", name = null)
        category(systemKey = "RENT", name = "Local")
        category(systemKey = null, name = "Fletes")
    }

    @Test
    fun `a category needs a name or an initial key`() {
        assertThrows(IllegalArgumentException::class.java) {
            category(systemKey = null, name = null)
        }
    }

    @Test
    fun `a category is archived only with an archive instant`() {
        assertFalse(category(systemKey = null, name = "Fletes").isArchived)
        assertTrue(category(systemKey = null, name = "Fletes", archivedAt = at).isArchived)
    }
}
