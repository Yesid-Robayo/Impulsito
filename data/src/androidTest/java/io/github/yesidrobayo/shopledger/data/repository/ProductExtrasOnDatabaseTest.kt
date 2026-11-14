package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomValueIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveCustomFieldUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteCustomFieldUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteTagUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ExtraLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReorderCustomFieldsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCustomFieldUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveExtraResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductExtrasResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductExtrasUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveTagUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los campos propios y las etiquetas sobre la base real: los cuatro tipos se guardan en su forma
 * neutra, los conteos de productos, el índice de la búsqueda, el orden, el archivo y las cascadas.
 * Nombres y valores inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ProductExtrasOnDatabaseTest : RepositoryTest() {
    private val fields by lazy { RoomCustomFieldRepository(database) }
    private val tags by lazy { RoomTagRepository(database) }
    private val products by lazy { RoomProductRepository(database) }
    private val clock = Clock { LATER_AT }
    private val saveField by lazy { SaveCustomFieldUseCase(runner, fields, clock) }
    private val saveTag by lazy { SaveTagUseCase(runner, tags, clock) }
    private val saveExtras by lazy {
        SaveProductExtrasUseCase(runner, products, fields, tags, clock)
    }

    @Test
    fun theFourTypesAreStoredInTheirNeutralFormAndCounted() = runBlocking {
        val coffee = database.productDao().insert(product("Café inventado"))
        val brand = field("Marca", CustomFieldType.TEXT)
        val weight = field("Peso", CustomFieldType.NUMBER)
        val harvest = field("Cosecha", CustomFieldType.DATE)
        val organic = field("Orgánico", CustomFieldType.BOOLEAN)

        assertEquals(
            SaveProductExtrasResult.Invalid(mapOf(harvest to CustomValueIssue.NOT_A_DATE)),
            saveExtras(coffee, mapOf(brand to "Altura", harvest to "2027-02-29"), emptySet()),
        )
        assertEquals(emptyMap<Long, String>(), fields.findValues(coffee))
        val values =
            mapOf(
                brand to "Altura",
                weight to "0500.50",
                harvest to "2026-11-14",
                organic to "true",
            )
        assertEquals(SaveProductExtrasResult.Saved, saveExtras(coffee, values, emptySet()))

        assertEquals(
            mapOf(brand to "Altura", weight to "500.5", harvest to "2026-11-14", organic to "true"),
            fields.observeValues(coffee).first(),
        )
        assertEquals(listOf(1, 1, 1, 1), fields.observeFields().first().map { it.productCount })
        // Cambiar un valor actualiza su fila; en blanco la quita.
        saveExtras(coffee, mapOf(brand to "Montaña", organic to ""), emptySet())
        assertEquals(
            mapOf(brand to "Montaña", weight to "500.5", harvest to "2026-11-14"),
            fields.findValues(coffee),
        )
        assertEquals(
            SaveExtraResult.TypeLocked,
            saveField(weight, draft("Peso", CustomFieldType.TEXT)),
        )
    }

    @Test
    fun fieldsAreOrderedArchivedAndDeletedOnlyWithoutValues() = runBlocking {
        val coffee = database.productDao().insert(product("Café inventado"))
        val a = field("Marca", CustomFieldType.TEXT)
        val b = field("Origen", CustomFieldType.TEXT)
        saveExtras(coffee, mapOf(a to "Altura"), emptySet())

        ReorderCustomFieldsUseCase(runner, fields, clock)(listOf(b, a))
        assertEquals(listOf(b, a), fields.observeFields().first().map { it.id })
        val delete = DeleteCustomFieldUseCase(runner, fields)
        assertEquals(ExtraLifecycleResult.HasValues, delete(a))
        assertEquals(ExtraLifecycleResult.Done, delete(b))
        assertEquals(
            ExtraLifecycleResult.Done,
            ArchiveCustomFieldUseCase(runner, fields, clock)(a, true),
        )
        assertEquals(true, fields.findById(a)?.isArchived)
        // Archivado, su nombre queda libre, y su valor sigue en el producto.
        assertEquals(
            SaveExtraResult.Saved(a + 2),
            saveField(null, draft("Marca", CustomFieldType.TEXT)),
        )
        assertEquals(mapOf(a to "Altura"), fields.findValues(coffee))
    }

    @Test
    fun tagsAreFoundByTheSearchIndexAndGoInCascade() = runBlocking {
        val candle = database.productDao().insert(product("Vela inventada"))
        val soap = database.productDao().insert(product("Jabón inventado"))
        val gift = (saveTag(null, "Para regalo") as SaveExtraResult.Saved).id
        val handmade = (saveTag(null, "Hecho a mano") as SaveExtraResult.Saved).id
        saveExtras(candle, emptyMap(), setOf(gift, handmade))
        saveExtras(soap, emptyMap(), setOf(handmade))

        assertEquals(
            mapOf(candle to listOf("para regalo", "hecho a mano"), soap to listOf("hecho a mano")),
            tags.observeSearchIndex().first(),
        )
        assertEquals(
            listOf(ProductTag(handmade, "Hecho a mano", 2), ProductTag(gift, "Para regalo", 1)),
            tags.observeTags().first(),
        )
        assertEquals(SaveExtraResult.DuplicateName, saveTag(null, "PARA REGALO"))

        assertEquals(ExtraLifecycleResult.Done, DeleteTagUseCase(runner, tags)(handmade))
        assertEquals(setOf(gift), tags.findTagIdsOf(candle))
        assertEquals(emptySet<Long>(), tags.findTagIdsOf(soap))
    }

    private suspend fun field(name: String, type: CustomFieldType): Long =
        (saveField(null, draft(name, type)) as SaveExtraResult.Saved).id

    private fun draft(name: String, type: CustomFieldType) = CustomFieldDraft(name, type)
}
