package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.CustomFieldDefinitionEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.CustomFieldWithCount
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductCustomValueEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductTagEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.TagEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.TagWithCount
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.domain.port.CustomFieldRepository
import io.github.yesidrobayo.shopledger.domain.port.TagRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los campos propios sobre Room. Cada escritura toca solo sus columnas: un valor se actualiza en
 * su fila, nunca con `REPLACE`, que borraría y volvería a crear.
 */
internal class RoomCustomFieldRepository(database: AppDatabase) : CustomFieldRepository {
    private val fields = database.customFieldDao()
    private val writes = RepositoryWrites(database)

    // Room vuelve a emitir con cualquier cambio de las tablas: una lista igual no se repite.
    override fun observeFields(): Flow<List<CustomField>> =
        fields.observeFieldsWithCount().map { rows -> rows.map(::toField) }.distinctUntilChanged()

    override suspend fun findById(id: Long): CustomField? =
        fields.findFieldWithCount(id)?.let(::toField)

    override suspend fun findActiveByNormalizedName(normalizedName: String): CustomField? =
        fields.findActiveField(normalizedName)?.let { findById(it.id) }

    override suspend fun nextSortOrder(): Long = fields.nextSortOrder()

    override suspend fun insert(
        name: String,
        normalizedName: String,
        type: CustomFieldType,
        sortOrder: Long,
        createdAt: Instant,
    ): Long = writes.inTransaction {
        val at = createdAt.toStoredMillis()
        fields.insertField(
            CustomFieldDefinitionEntity(
                name = name,
                normalizedName = normalizedName,
                fieldType = type.name,
                sortOrder = sortOrder,
                archivedAt = null,
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    override suspend fun update(
        id: Long,
        name: String,
        normalizedName: String,
        type: CustomFieldType,
        updatedAt: Instant,
    ) {
        writes.inTransaction {
            requireOneRow(
                fields.updateNameAndType(
                    id,
                    name,
                    normalizedName,
                    type.name,
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun setSortOrder(id: Long, sortOrder: Long, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(fields.setSortOrder(id, sortOrder, updatedAt.toStoredMillis()))
        }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                fields.setFieldArchived(
                    id,
                    archivedAt?.toStoredMillis(),
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(fields.deleteField(id)) }
    }

    override fun observeValues(productId: Long): Flow<Map<Long, String>> = fields
        .observeValues(productId)
        .map { rows -> rows.associate { it.fieldId to it.valueText } }
        .distinctUntilChanged()

    override suspend fun findValues(productId: Long): Map<Long, String> =
        fields.findValues(productId).associate { it.fieldId to it.valueText }

    override suspend fun putValue(
        productId: Long,
        fieldId: Long,
        stored: String,
        updatedAt: Instant,
    ) {
        writes.inTransaction {
            val at = updatedAt.toStoredMillis()
            val current = fields.findValue(productId, fieldId)
            if (current == null) {
                fields.insertValue(ProductCustomValueEntity(0, productId, fieldId, stored, at))
            } else {
                requireOneRow(fields.updateValue(current.id, stored, at))
            }
        }
    }

    override suspend fun removeValue(productId: Long, fieldId: Long) {
        writes.inTransaction { requireOneRow(fields.deleteValueOf(productId, fieldId)) }
    }

    private fun toField(row: CustomFieldWithCount) = CustomField(
        id = row.field.id,
        name = row.field.name,
        // Un tipo desconocido, de una copia más nueva, se muestra pero no se llena.
        type = CustomFieldType.fromStored(row.field.fieldType),
        sortOrder = row.field.sortOrder,
        isArchived = row.field.archivedAt != null,
        productCount = row.productCount,
    )
}

/** Las etiquetas sobre Room. Un vínculo se crea o se borra: no tiene nada que editar. */
internal class RoomTagRepository(database: AppDatabase) : TagRepository {
    private val tags = database.tagDao()
    private val writes = RepositoryWrites(database)

    override fun observeTags(): Flow<List<ProductTag>> =
        tags.observeAllWithCount().map { rows -> rows.map(::toTag) }.distinctUntilChanged()

    override suspend fun findById(id: Long): ProductTag? = tags.findWithCount(id)?.let(::toTag)

    override suspend fun findByNormalizedName(normalizedName: String): ProductTag? =
        tags.findByNormalizedName(normalizedName)?.let { findById(it.id) }

    override suspend fun insert(name: String, normalizedName: String, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            tags.insert(
                TagEntity(
                    name = name,
                    normalizedName = normalizedName,
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    override suspend fun rename(
        id: Long,
        name: String,
        normalizedName: String,
        updatedAt: Instant,
    ) {
        writes.inTransaction {
            requireOneRow(tags.update(id, name, normalizedName, updatedAt.toStoredMillis()))
        }
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(tags.delete(id)) }
    }

    override fun observeTagIdsOf(productId: Long): Flow<Set<Long>> =
        tags.observeTagIdsOf(productId).map { it.toSet() }.distinctUntilChanged()

    override suspend fun findTagIdsOf(productId: Long): Set<Long> =
        tags.findTagIdsOf(productId).toSet()

    override suspend fun link(productId: Long, tagId: Long) {
        writes.inTransaction { tags.link(ProductTagEntity(productId = productId, tagId = tagId)) }
    }

    override suspend fun unlink(productId: Long, tagId: Long) {
        writes.inTransaction { requireOneRow(tags.unlink(productId, tagId)) }
    }

    override fun observeSearchIndex(): Flow<Map<Long, List<String>>> = tags
        .observeSearchIndex()
        .map { rows ->
            rows.groupBy({ it.productId }, { it.normalizedName })
        }
        .distinctUntilChanged()

    private fun toTag(row: TagWithCount) = ProductTag(row.tag.id, row.tag.name, row.productCount)
}
