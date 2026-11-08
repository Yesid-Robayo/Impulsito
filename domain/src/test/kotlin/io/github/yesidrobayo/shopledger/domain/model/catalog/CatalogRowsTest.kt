package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRowsTest {
    @Test
    fun `every system row names a key that its table creates`() {
        for (row in SystemRow.entries) {
            assertTrue(
                "${row.name} no es una clave de ${row.table.name}",
                row.systemKey in row.table.systemKeys,
            )
        }
        assertEquals(SeedTable.UNITS.systemKeys.first(), SystemRow.DEFAULT_UNIT.systemKey)
    }

    @Test
    fun `a unit with an archive instant is archived`() {
        val unit = MeasureUnit(
            id = 1L,
            systemKey = "UNIT",
            name = null,
            symbol = null,
            kind = UnitKind.COUNT,
            allowsFraction = false,
            archivedAt = null,
        )

        assertFalse(unit.isArchived)
        assertTrue(unit.copy(archivedAt = Instant.fromEpochMilliseconds(1L)).isArchived)
    }
}
