package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomValueIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ExtraNameIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductExtras
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.domain.port.CustomFieldRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TagRepository
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Los campos propios y las etiquetas: crearlos, editarlos, ordenarlos, archivarlos, borrarlos y
 * guardarlos en un producto. Nombres y valores inventados.
 */
class ProductExtrasUseCasesTest {
    private val fixture = CatalogFixture()
    private val fields = InMemoryFields()
    private val tags = InMemoryTags()
    private val saveField = SaveCustomFieldUseCase(fixture.transactions, fields, fixture.clock)
    private val archive = ArchiveCustomFieldUseCase(fixture.transactions, fields, fixture.clock)
    private val deleteField = DeleteCustomFieldUseCase(fixture.transactions, fields)
    private val reorder = ReorderCustomFieldsUseCase(fixture.transactions, fields, fixture.clock)
    private val saveTag = SaveTagUseCase(fixture.transactions, tags, fixture.clock)
    private val deleteTag = DeleteTagUseCase(fixture.transactions, tags)
    private val saveExtras =
        SaveProductExtrasUseCase(
            fixture.transactions,
            fixture.products,
            fields,
            tags,
            fixture.clock,
        )

    @Test
    fun `a field is created at the end and its name is unique among the active ones`() = runTest {
        val brand = saved(saveField(null, CustomFieldDraft("Marca", CustomFieldType.TEXT)))
        val harvest = saved(saveField(null, CustomFieldDraft("Cosecha", CustomFieldType.DATE)))

        assertEquals(
            listOf(0L, 1L),
            listOf(brand, harvest).map {
                fields.rows.getValue(it).sortOrder
            },
        )
        assertEquals(
            SaveExtraResult.DuplicateName,
            saveField(null, CustomFieldDraft(" MARCA ", CustomFieldType.NUMBER)),
        )
        assertEquals(
            SaveExtraResult.Invalid(ExtraNameIssue.EMPTY),
            saveField(null, CustomFieldDraft(" ", CustomFieldType.TEXT)),
        )
        assertEquals(
            SaveExtraResult.NotFound,
            saveField(99L, CustomFieldDraft("X", CustomFieldType.TEXT)),
        )
    }

    @Test
    fun `the type changes only while no product has a value`() = runTest {
        val weight = saved(saveField(null, CustomFieldDraft("Peso", CustomFieldType.TEXT)))
        assertEquals(
            SaveExtraResult.Saved(weight),
            saveField(weight, CustomFieldDraft("Peso neto", CustomFieldType.NUMBER)),
        )
        fixture.seedProduct(1L, "Café inventado")
        assertEquals(
            SaveProductExtrasResult.Saved,
            saveExtras(1L, mapOf(weight to "500"), emptySet()),
        )

        assertEquals(
            SaveExtraResult.TypeLocked,
            saveField(weight, CustomFieldDraft("Peso neto", CustomFieldType.TEXT)),
        )
        // Sin cambios no escribe.
        val commits = fixture.transactions.commits
        assertEquals(
            SaveExtraResult.Saved(weight),
            saveField(weight, CustomFieldDraft("Peso neto", CustomFieldType.NUMBER)),
        )
        assertEquals(commits, fixture.transactions.commits)
    }

    @Test
    fun `a field with values is archived, not deleted, and comes back if its name is free`() =
        runTest {
            val size = saved(saveField(null, CustomFieldDraft("Talla", CustomFieldType.TEXT)))
            fixture.seedProduct(1L, "Camiseta inventada")
            saveExtras(1L, mapOf(size to "M"), emptySet())

            assertEquals(ExtraLifecycleResult.HasValues, deleteField(size))
            assertEquals(ExtraLifecycleResult.Done, archive(size, archive = true))
            // Archivado no reserva su nombre: otro vigente lo toma, y entonces no vuelve.
            val other = saved(saveField(null, CustomFieldDraft("Talla", CustomFieldType.NUMBER)))
            assertEquals(ExtraLifecycleResult.NameTaken, archive(size, archive = false))
            assertEquals(ExtraLifecycleResult.Done, deleteField(other))
            assertEquals(ExtraLifecycleResult.Done, archive(size, archive = false))
            assertEquals(mapOf(size to "M"), fields.findValues(1L))
            assertEquals(ExtraLifecycleResult.NotFound, deleteField(other))
            assertEquals(ExtraLifecycleResult.NotFound, archive(other, archive = true))
            assertEquals(ExtraLifecycleResult.Done, archive(size, archive = false))
        }

    @Test
    fun `reordering writes only the fields that move`() = runTest {
        val a = saved(saveField(null, CustomFieldDraft("A", CustomFieldType.TEXT)))
        val b = saved(saveField(null, CustomFieldDraft("B", CustomFieldType.TEXT)))
        val c = saved(saveField(null, CustomFieldDraft("C", CustomFieldType.TEXT)))

        assertEquals(ExtraLifecycleResult.Done, reorder(listOf(c, a, b, 99L)))
        assertEquals(listOf(1L, 2L, 0L), listOf(a, b, c).map { fields.rows.getValue(it).sortOrder })
        val commits = fixture.transactions.commits
        assertEquals(ExtraLifecycleResult.Done, reorder(listOf(c, a, b)))
        assertEquals(commits, fixture.transactions.commits)
    }

    @Test
    fun `the four types are checked before anything is written`() = runTest {
        val text = saved(saveField(null, CustomFieldDraft("Marca", CustomFieldType.TEXT)))
        val number = saved(saveField(null, CustomFieldDraft("Peso", CustomFieldType.NUMBER)))
        val date = saved(saveField(null, CustomFieldDraft("Cosecha", CustomFieldType.DATE)))
        val yes = saved(saveField(null, CustomFieldDraft("Orgánico", CustomFieldType.BOOLEAN)))
        fixture.seedProduct(1L, "Café inventado")

        assertEquals(
            SaveProductExtrasResult.Invalid(
                mapOf(number to CustomValueIssue.NOT_A_NUMBER, date to CustomValueIssue.NOT_A_DATE),
            ),
            saveExtras(1L, mapOf(text to "Altura", number to "1,5", date to "ayer"), emptySet()),
        )
        assertEquals(emptyMap<Long, String>(), fields.findValues(1L))

        val values =
            mapOf(text to "Altura", number to "0500.0", date to "2026-11-14", yes to "true")
        assertEquals(SaveProductExtrasResult.Saved, saveExtras(1L, values, emptySet()))
        assertEquals(
            mapOf(text to "Altura", number to "500", date to "2026-11-14", yes to "true"),
            fields.findValues(1L),
        )
        // En blanco quita el valor; un campo que no viene no se toca.
        assertEquals(SaveProductExtrasResult.Saved, saveExtras(1L, mapOf(text to " "), emptySet()))
        assertEquals(setOf(number, date, yes), fields.findValues(1L).keys)
        assertEquals(SaveProductExtrasResult.NotFound, saveExtras(99L, emptyMap(), emptySet()))
    }

    @Test
    fun `tags are linked and unlinked, and a deleted tag is ignored`() = runTest {
        val gift = saved(saveTag(null, "Regalo"))
        val handmade = saved(saveTag(null, "Hecho a mano"))
        fixture.seedProduct(1L, "Vela inventada")

        assertEquals(
            SaveProductExtrasResult.Saved,
            saveExtras(1L, emptyMap(), setOf(gift, handmade)),
        )
        assertEquals(setOf(gift, handmade), tags.findTagIdsOf(1L))
        assertEquals(SaveProductExtrasResult.Saved, saveExtras(1L, emptyMap(), setOf(gift, 99L)))
        assertEquals(setOf(gift), tags.findTagIdsOf(1L))
        assertEquals(mapOf(1L to listOf("regalo")), tags.observeSearchIndex().first())

        assertEquals(SaveExtraResult.DuplicateName, saveTag(null, "regalo"))
        assertEquals(SaveExtraResult.Saved(gift), saveTag(gift, "Para regalo"))
        assertEquals(SaveExtraResult.Saved(gift), saveTag(gift, "Para regalo"))
        assertEquals(SaveExtraResult.NotFound, saveTag(99L, "Otra"))
        assertEquals(
            SaveExtraResult.Invalid(ExtraNameIssue.TOO_LONG),
            saveTag(null, "x".repeat(41)),
        )
        assertEquals(ExtraLifecycleResult.Done, deleteTag(gift))
        assertEquals(emptySet<Long>(), tags.findTagIdsOf(1L))
        assertEquals(ExtraLifecycleResult.NotFound, deleteTag(gift))
    }

    @Test
    fun `the form sees the active fields, the values and the tags`() = runTest {
        val brand = saved(saveField(null, CustomFieldDraft("Marca", CustomFieldType.TEXT)))
        val old = saved(saveField(null, CustomFieldDraft("Viejo", CustomFieldType.TEXT)))
        val gift = saved(saveTag(null, "Regalo"))
        fixture.seedProduct(1L, "Vela inventada")
        saveExtras(1L, mapOf(brand to "Luz", old to "x"), setOf(gift))
        archive(old, archive = true)

        val extras = ObserveProductExtrasUseCase(fields, tags)(1L).first()
        val fresh = ObserveProductExtrasUseCase(fields, tags)(null).first()

        assertEquals(listOf(brand), extras.fields.map { it.id })
        assertEquals(mapOf(brand to "Luz", old to "x"), extras.values)
        assertEquals(setOf(gift), extras.tagIds)
        assertEquals(ProductExtras(extras.fields, emptyMap(), extras.tags, emptySet()), fresh)
        val (all, labels) = ObserveExtrasCatalogUseCase(fields, tags)().first()
        assertEquals(listOf(brand, old), all.map { it.id })
        assertEquals(listOf(ProductTag(gift, "Regalo", 1)), labels)
        assertEquals(mapOf(1L to listOf("regalo")), ObserveTagSearchIndexUseCase(tags)().first())
    }

    @Test
    fun `a storage failure is a result, with nothing half written`() = runTest {
        fixture.transactions.restoreInProgress = true

        assertEquals(
            SaveExtraResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            saveTag(null, "Regalo"),
        )
        assertEquals(
            SaveProductExtrasResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            saveExtras(1L, emptyMap(), emptySet()),
        )
        assertEquals(
            ExtraLifecycleResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            deleteTag(1L),
        )
    }

    private fun saved(result: SaveExtraResult): Long = (result as SaveExtraResult.Saved).id

    /** Los campos en memoria, con sus valores. */
    private class InMemoryFields : CustomFieldRepository {
        val rows = linkedMapOf<Long, CustomField>()
        private val values = MutableStateFlow(mapOf<Pair<Long, Long>, String>())
        private val changes = MutableStateFlow(0)
        private var nextId = 1L

        private fun withCount(field: CustomField) =
            field.copy(productCount = values.value.keys.count { it.second == field.id })

        override fun observeFields(): Flow<List<CustomField>> = changes.map {
            rows.values.map(::withCount).sortedWith(compareBy({ it.sortOrder }, { it.id }))
        }

        override suspend fun findById(id: Long) = rows[id]?.let(::withCount)

        override suspend fun findActiveByNormalizedName(normalizedName: String) =
            rows.values.firstOrNull {
                !it.isArchived && TextNormalizer.normalize(it.name) == normalizedName
            }?.let(::withCount)

        override suspend fun nextSortOrder(): Long =
            (rows.values.maxOfOrNull { it.sortOrder } ?: -1) + 1

        override suspend fun insert(
            name: String,
            normalizedName: String,
            type: CustomFieldType,
            sortOrder: Long,
            createdAt: Instant,
        ): Long {
            val id = nextId++
            rows[id] = CustomField(id, name, type, sortOrder, isArchived = false, productCount = 0)
            changes.value++
            return id
        }

        override suspend fun update(
            id: Long,
            name: String,
            normalizedName: String,
            type: CustomFieldType,
            updatedAt: Instant,
        ) {
            rows[id] = rows.getValue(id).copy(name = name, type = type)
            changes.value++
        }

        override suspend fun setSortOrder(id: Long, sortOrder: Long, updatedAt: Instant) {
            rows[id] = rows.getValue(id).copy(sortOrder = sortOrder)
        }

        override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
            rows[id] = rows.getValue(id).copy(isArchived = archivedAt != null)
            changes.value++
        }

        override suspend fun delete(id: Long) {
            rows.remove(id)
            values.value = values.value.filterKeys { it.second != id }
        }

        override fun observeValues(productId: Long): Flow<Map<Long, String>> = values.map { all ->
            all.filterKeys { it.first == productId }.mapKeys { it.key.second }
        }

        override suspend fun findValues(productId: Long): Map<Long, String> =
            observeValues(productId).first()

        override suspend fun putValue(
            productId: Long,
            fieldId: Long,
            stored: String,
            updatedAt: Instant,
        ) {
            values.value = values.value + ((productId to fieldId) to stored)
        }

        override suspend fun removeValue(productId: Long, fieldId: Long) {
            values.value = values.value - (productId to fieldId)
        }
    }

    /** Las etiquetas en memoria, con sus vínculos. */
    private class InMemoryTags : TagRepository {
        private val rows = MutableStateFlow(mapOf<Long, String>())
        private val links = MutableStateFlow(setOf<Pair<Long, Long>>())
        private var nextId = 1L

        override fun observeTags(): Flow<List<ProductTag>> = rows.map { all ->
            all.map { (id, name) -> tag(id, name) }.sortedBy { TextNormalizer.normalize(it.name) }
        }

        private fun tag(id: Long, name: String) =
            ProductTag(id, name, links.value.count { it.second == id })

        override suspend fun findById(id: Long) = rows.value[id]?.let { tag(id, it) }

        override suspend fun findByNormalizedName(normalizedName: String) = rows.value.entries
            .firstOrNull { TextNormalizer.normalize(it.value) == normalizedName }
            ?.let { tag(it.key, it.value) }

        override suspend fun insert(
            name: String,
            normalizedName: String,
            createdAt: Instant,
        ): Long {
            val id = nextId++
            rows.value = rows.value + (id to name)
            return id
        }

        override suspend fun rename(
            id: Long,
            name: String,
            normalizedName: String,
            updatedAt: Instant,
        ) {
            rows.value = rows.value + (id to name)
        }

        override suspend fun delete(id: Long) {
            rows.value = rows.value - id
            links.value = links.value.filterTo(mutableSetOf()) { it.second != id }
        }

        override fun observeTagIdsOf(productId: Long): Flow<Set<Long>> = links.map { all ->
            all.filter { it.first == productId }.mapTo(mutableSetOf()) { it.second }
        }

        override suspend fun findTagIdsOf(productId: Long): Set<Long> =
            observeTagIdsOf(productId).first()

        override suspend fun link(productId: Long, tagId: Long) {
            links.value = links.value + (productId to tagId)
        }

        override suspend fun unlink(productId: Long, tagId: Long) {
            links.value = links.value - (productId to tagId)
        }

        override fun observeSearchIndex(): Flow<Map<Long, List<String>>> = links.map { all ->
            all.groupBy({ it.first }, { TextNormalizer.normalize(rows.value.getValue(it.second)) })
        }
    }
}
