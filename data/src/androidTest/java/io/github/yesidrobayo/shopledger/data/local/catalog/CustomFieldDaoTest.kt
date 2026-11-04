package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los campos propios y sus valores: nombre único también entre archivados, orden de la persona y
 * después por `id`, un valor por campo y producto, y valores que se van en cascada con su campo y
 * con su producto.
 */
@RunWith(AndroidJUnit4::class)
internal class CustomFieldDaoTest : CatalogDatabaseTest() {
    private val fields get() = database.customFieldDao()

    @Test
    fun activeFieldsFollowTheirOrderThenTheirIdAndEmitAgain() = runBlocking {
        val second = fields.insertField(field("Talla", sortOrder = 2))
        val first = fields.insertField(field("Color", sortOrder = 1))
        val tied = fields.insertField(field("Material", sortOrder = 2))
        val archived =
            fields.insertField(field("Lote viejo", sortOrder = 0).copy(archivedAt = LATER))

        val emissions = Emissions(this, fields.observeActiveFields().ids { it.id })
        try {
            emissions.awaitValue(listOf(first, second, tied))

            // Reactivarlo lo devuelve a la lista, y archivarlo lo saca.
            assertEquals(1, fields.setFieldArchived(archived, archivedAt = null, MUCH_LATER))
            emissions.awaitValue(listOf(archived, first, second, tied))
            fields.setFieldArchived(second, archivedAt = MUCH_LATER, MUCH_LATER)
            emissions.awaitValue(listOf(archived, first, tied))
        } finally {
            emissions.stop()
        }
        assertEquals(
            field("Talla", sortOrder = 2)
                .copy(id = second, archivedAt = MUCH_LATER, updatedAt = MUCH_LATER),
            fields.findField(second),
        )
    }

    @Test
    fun aFieldNameIsUniqueAlsoAgainstArchivedOnesAndItsTypeStays() = runBlocking {
        val archived = fields.insertField(field("Talla").copy(archivedAt = LATER))
        val id = fields.insertField(field("Fecha de cosecha", type = "DATE"))

        constraintFailure(UNIQUE) { fields.insertField(field("TALLA")) }
        constraintFailure(UNIQUE) { fields.updateField(id, "Talla", "talla", 0, LATER) }

        assertEquals(1, fields.updateField(id, "Cosecha", "cosecha", sortOrder = 5, MUCH_LATER))
        assertEquals(
            field("Fecha de cosecha", type = "DATE").copy(
                id = id,
                name = "Cosecha",
                normalizedName = "cosecha",
                sortOrder = 5,
                updatedAt = MUCH_LATER,
            ),
            fields.findFieldByNormalizedName("cosecha"),
        )
        assertEquals(archived, fields.findFieldByNormalizedName("talla")?.id)
        assertNull(fields.findField(MISSING_ID))
        assertEquals(0, fields.updateField(MISSING_ID, "Otro", "otro", 0, LATER))
    }

    @Test
    fun aProductHasOneValuePerFieldInTheOrderItWasWritten() = runBlocking {
        val productId = database.productDao().insert(product("Camiseta"))
        val size = fields.insertField(field("Talla"))
        val harvest = fields.insertField(field("Cosecha", type = "DATE"))
        val first = value(productId, size, "M")
        val firstId = fields.insertValue(first)
        val secondId = fields.insertValue(value(productId, harvest, "2026-03-15"))

        constraintFailure(UNIQUE) { fields.insertValue(value(productId, size, "L")) }
        constraintFailure(FOREIGN_KEY) { fields.insertValue(value(productId, MISSING_ID, "L")) }
        constraintFailure(FOREIGN_KEY) { fields.insertValue(value(MISSING_ID, size, "L")) }

        val emissions = Emissions(this, fields.observeValues(productId).ids { it.id })
        try {
            emissions.awaitValue(listOf(firstId, secondId))
            assertEquals(1, fields.updateValue(firstId, valueText = "XL", updatedAt = LATER))
            assertEquals(1, fields.deleteValue(secondId))
            emissions.awaitValue(listOf(firstId))
        } finally {
            emissions.stop()
        }
        assertEquals(
            listOf(first.copy(id = firstId, valueText = "XL", updatedAt = LATER)),
            fields.findValues(productId),
        )
    }

    @Test
    fun valuesGoWithTheirFieldAndWithTheirProduct() = runBlocking {
        val shirt = database.productDao().insert(product("Camiseta"))
        val cap = database.productDao().insert(product("Gorra"))
        val size = fields.insertField(field("Talla"))
        val color = fields.insertField(field("Color"))
        fields.insertValue(value(shirt, size, "M"))
        fields.insertValue(value(cap, size, "Única"))
        fields.insertValue(value(cap, color, "Azul"))

        assertEquals(2, fields.countValues(size))
        // Borrar el campo borra sus valores en todos los productos, y nada más.
        assertEquals(1, fields.deleteField(size))
        assertEquals(0, fields.countValues(size))
        assertEquals(1, fields.findValues(cap).size)

        // Borrar el producto borra sus valores.
        assertEquals(1, database.productDao().delete(cap))
        assertEquals(0L, count("SELECT COUNT(*) FROM product_custom_values"))
    }

    private fun field(name: String, type: String = "TEXT", sortOrder: Long = 0) =
        CustomFieldDefinitionEntity(
            name = name,
            normalizedName = TextNormalizer.normalize(name),
            fieldType = type,
            sortOrder = sortOrder,
            archivedAt = null,
            createdAt = CREATED_AT,
            updatedAt = CREATED_AT,
        )

    private fun value(productId: Long, fieldId: Long, text: String) = ProductCustomValueEntity(
        productId = productId,
        fieldId = fieldId,
        valueText = text,
        updatedAt = CREATED_AT,
    )

    private companion object {
        const val MISSING_ID = 999L
    }
}
