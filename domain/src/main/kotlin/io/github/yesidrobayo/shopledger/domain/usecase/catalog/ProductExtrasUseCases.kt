package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomValueIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ExtraNameIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductExtras
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomFieldRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TagRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CustomFieldRules
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CustomValueCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ExtraNameCheck
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

// Los campos propios y las etiquetas: crearlos, editarlos, ordenarlos, archivarlos o borrarlos, y
// guardarlos en cada producto. Cada caso de uso escribe en una sola transacción, y ninguno toca
// dinero ni stock.

/** Cómo terminó crear o editar un campo propio o una etiqueta. */
sealed interface SaveExtraResult {
    /** Quedó guardado, o ya estaba así. [id] es el suyo. */
    data class Saved(val id: Long) : SaveExtraResult

    /** El nombre no sirve. Lo escrito se conserva. */
    data class Invalid(val issue: ExtraNameIssue) : SaveExtraResult

    /** Otro vigente ya se llama así, comparado sin mayúsculas ni tildes. */
    data object DuplicateName : SaveExtraResult

    /** El campo ya tiene valores: su tipo no cambia, porque se escribieron para el que tenía. */
    data object TypeLocked : SaveExtraResult

    /** Ya no existe, o se archivó mientras se editaba. */
    data object NotFound : SaveExtraResult

    data class StorageFailed(val failure: StorageFailure) : SaveExtraResult
}

/** Cómo terminó archivar, reactivar, borrar u ordenar un campo, o borrar una etiqueta. */
sealed interface ExtraLifecycleResult {
    data object Done : ExtraLifecycleResult

    data object NotFound : ExtraLifecycleResult

    /** El campo tiene valores: no se borra, se archiva. */
    data object HasValues : ExtraLifecycleResult

    /** Un campo vigente ya usa su nombre: hay que cambiar uno antes de reactivarlo. */
    data object NameTaken : ExtraLifecycleResult

    data class StorageFailed(val failure: StorageFailure) : ExtraLifecycleResult
}

/** Cómo terminó guardar los campos y las etiquetas de un producto. */
sealed interface SaveProductExtrasResult {
    data object Saved : SaveProductExtrasResult

    /** Algún valor no sirve para su campo: nada se guardó. Cada problema va por id de campo. */
    data class Invalid(val issues: Map<Long, CustomValueIssue>) : SaveProductExtrasResult

    /** El producto ya no existe. */
    data object NotFound : SaveProductExtrasResult

    data class StorageFailed(val failure: StorageFailure) : SaveProductExtrasResult
}

/**
 * Crea un campo propio, o edita uno vigente. El nombre es único entre los vigentes, y el tipo solo
 * cambia mientras ningún producto tiene un valor en él. Una edición que no cambia nada no escribe.
 */
class SaveCustomFieldUseCase(
    private val transactions: TransactionRunner,
    private val fields: CustomFieldRepository,
    private val clock: Clock,
) {
    /** Crea el campo de [draft] si [editingId] es nulo, o edita ese campo. */
    suspend operator fun invoke(editingId: Long?, draft: CustomFieldDraft): SaveExtraResult =
        storageSafe(SaveExtraResult::StorageFailed) {
            transactions.write { save(editingId, draft) }
        }

    private suspend fun save(editingId: Long?, draft: CustomFieldDraft): TxResult<SaveExtraResult> {
        val current = editingId?.let { fields.findById(it) }
        if (editingId != null && (current == null || current.isArchived)) {
            return TxResult.Rollback(SaveExtraResult.NotFound)
        }
        val name =
            when (val check = CustomFieldRules.name(draft.name)) {
                is ExtraNameCheck.Invalid -> return TxResult.Rollback(
                    SaveExtraResult.Invalid(check.issue),
                )

                is ExtraNameCheck.Valid -> check
            }
        val clash = fields.findActiveByNormalizedName(name.normalizedName)
        if (clash != null && clash.id != editingId) {
            return TxResult.Rollback(SaveExtraResult.DuplicateName)
        }
        val now = clock.now()
        if (current == null) {
            val id = fields.insert(
                name.name,
                name.normalizedName,
                draft.type,
                fields.nextSortOrder(),
                now,
            )
            return TxResult.Commit(SaveExtraResult.Saved(id))
        }
        if (current.type != draft.type && current.productCount > 0) {
            return TxResult.Rollback(SaveExtraResult.TypeLocked)
        }
        if (current.name == name.name && current.type == draft.type) {
            return TxResult.Rollback(SaveExtraResult.Saved(current.id))
        }
        fields.update(current.id, name.name, name.normalizedName, draft.type, now)
        return TxResult.Commit(SaveExtraResult.Saved(current.id))
    }
}

/**
 * Archiva o reactiva un campo propio. Archivado deja de pedirse en los productos, y sus valores se
 * conservan. Reactivarlo falla si otro vigente ya usa su nombre.
 */
class ArchiveCustomFieldUseCase(
    private val transactions: TransactionRunner,
    private val fields: CustomFieldRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long, archive: Boolean): ExtraLifecycleResult =
        storageSafe(ExtraLifecycleResult::StorageFailed) {
            transactions.write {
                val field =
                    fields.findById(id)
                        ?: return@write TxResult.Rollback(ExtraLifecycleResult.NotFound)
                if (field.isArchived ==
                    archive
                ) {
                    return@write TxResult.Rollback(ExtraLifecycleResult.Done)
                }
                if (!archive) {
                    val normalized = (
                        CustomFieldRules.name(
                            field.name,
                        ) as? ExtraNameCheck.Valid
                        )?.normalizedName
                    val clash = normalized?.let { fields.findActiveByNormalizedName(it) }
                    if (clash !=
                        null
                    ) {
                        return@write TxResult.Rollback(ExtraLifecycleResult.NameTaken)
                    }
                }
                val now = clock.now()
                fields.setArchivedAt(id, if (archive) now else null, now)
                TxResult.Commit(ExtraLifecycleResult.Done)
            }
        }
}

/** Borra un campo propio sin valores. Con valores no se borra: se archiva. */
class DeleteCustomFieldUseCase(
    private val transactions: TransactionRunner,
    private val fields: CustomFieldRepository,
) {
    suspend operator fun invoke(id: Long): ExtraLifecycleResult =
        storageSafe(ExtraLifecycleResult::StorageFailed) {
            transactions.write {
                val field =
                    fields.findById(id)
                        ?: return@write TxResult.Rollback(ExtraLifecycleResult.NotFound)
                if (field.productCount >
                    0
                ) {
                    return@write TxResult.Rollback(ExtraLifecycleResult.HasValues)
                }
                fields.delete(id)
                TxResult.Commit(ExtraLifecycleResult.Done)
            }
        }
}

/**
 * Deja los campos vigentes en el orden de [orderedIds]: cada uno toma su posición. Solo escribe los
 * que cambian de lugar. Un id que ya no existe o está archivado se salta.
 */
class ReorderCustomFieldsUseCase(
    private val transactions: TransactionRunner,
    private val fields: CustomFieldRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(orderedIds: List<Long>): ExtraLifecycleResult =
        storageSafe(ExtraLifecycleResult::StorageFailed) {
            transactions.write {
                val now = clock.now()
                var changed = false
                orderedIds.forEachIndexed { index, id ->
                    val field = fields.findById(id)
                    val position = index.toLong()
                    if (field != null && !field.isArchived && field.sortOrder != position) {
                        fields.setSortOrder(id, position, now)
                        changed = true
                    }
                }
                if (changed) {
                    TxResult.Commit(ExtraLifecycleResult.Done)
                } else {
                    TxResult.Rollback(ExtraLifecycleResult.Done)
                }
            }
        }
}

/** Crea una etiqueta, o le cambia el nombre. El nombre es único entre todas. */
class SaveTagUseCase(
    private val transactions: TransactionRunner,
    private val tags: TagRepository,
    private val clock: Clock,
) {
    /** Crea la etiqueta [name] si [editingId] es nulo, o renombra esa etiqueta. */
    suspend operator fun invoke(editingId: Long?, name: String): SaveExtraResult =
        storageSafe(SaveExtraResult::StorageFailed) {
            transactions.write { save(editingId, name) }
        }

    private suspend fun save(editingId: Long?, raw: String): TxResult<SaveExtraResult> {
        val current = editingId?.let { tags.findById(it) }
        if (editingId != null && current == null) return TxResult.Rollback(SaveExtraResult.NotFound)
        val name =
            when (val check = CustomFieldRules.name(raw)) {
                is ExtraNameCheck.Invalid -> return TxResult.Rollback(
                    SaveExtraResult.Invalid(check.issue),
                )

                is ExtraNameCheck.Valid -> check
            }
        val clash = tags.findByNormalizedName(name.normalizedName)
        if (clash != null && clash.id != editingId) {
            return TxResult.Rollback(SaveExtraResult.DuplicateName)
        }
        val now = clock.now()
        if (current == null) {
            return TxResult.Commit(
                SaveExtraResult.Saved(tags.insert(name.name, name.normalizedName, now)),
            )
        }
        if (current.name == name.name) return TxResult.Rollback(SaveExtraResult.Saved(current.id))
        tags.rename(current.id, name.name, name.normalizedName, now)
        return TxResult.Commit(SaveExtraResult.Saved(current.id))
    }
}

/** Borra una etiqueta: los productos que la llevaban la pierden, y nada más cambia. */
class DeleteTagUseCase(
    private val transactions: TransactionRunner,
    private val tags: TagRepository,
) {
    suspend operator fun invoke(id: Long): ExtraLifecycleResult =
        storageSafe(ExtraLifecycleResult::StorageFailed) {
            transactions.write {
                tags.findById(id) ?: return@write TxResult.Rollback(ExtraLifecycleResult.NotFound)
                tags.delete(id)
                TxResult.Commit(ExtraLifecycleResult.Done)
            }
        }
}

/**
 * Guarda los campos propios y las etiquetas de un producto, en una sola transacción. Revisa todos
 * los valores antes de escribir: si uno no sirve, no escribe ninguno. Solo escribe lo que cambia.
 *
 * Los campos que no vienen en [values], como los archivados, no se tocan. Un valor en blanco quita
 * el que había. Una etiqueta que ya no existe se ignora: la borraron mientras se editaba.
 */
class SaveProductExtrasUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val fields: CustomFieldRepository,
    private val tags: TagRepository,
    private val clock: Clock,
) {
    /**
     * @param values lo escrito en cada campo, por id de campo, ya en forma neutra.
     * @param tagIds las etiquetas que debe llevar el producto, y ninguna otra.
     */
    suspend operator fun invoke(
        productId: Long,
        values: Map<Long, String>,
        tagIds: Set<Long>,
    ): SaveProductExtrasResult = storageSafe(SaveProductExtrasResult::StorageFailed) {
        transactions.write { save(productId, values, tagIds) }
    }

    private suspend fun save(
        productId: Long,
        values: Map<Long, String>,
        tagIds: Set<Long>,
    ): TxResult<SaveProductExtrasResult> {
        products.findById(productId) ?: return TxResult.Rollback(SaveProductExtrasResult.NotFound)
        val checked = mutableMapOf<Long, String?>()
        val issues = mutableMapOf<Long, CustomValueIssue>()
        for ((fieldId, raw) in values) {
            val field = fields.findById(fieldId) ?: continue
            when (val check = CustomFieldRules.value(field.type, raw)) {
                CustomValueCheck.Blank -> checked[fieldId] = null
                is CustomValueCheck.Valid -> checked[fieldId] = check.stored
                is CustomValueCheck.Invalid -> issues[fieldId] = check.issue
            }
        }
        if (issues.isNotEmpty()) return TxResult.Rollback(SaveProductExtrasResult.Invalid(issues))
        val now = clock.now()
        var changed = false
        val stored = fields.findValues(productId)
        for ((fieldId, value) in checked) {
            val before = stored[fieldId]
            if (value == before) continue
            if (value ==
                null
            ) {
                fields.removeValue(productId, fieldId)
            } else {
                fields.putValue(productId, fieldId, value, now)
            }
            changed = true
        }
        val linked = tags.findTagIdsOf(productId)
        val wanted = tagIds.filterTo(mutableSetOf()) { it in linked || tags.findById(it) != null }
        (linked - wanted).forEach { tags.unlink(productId, it) }
        (wanted - linked).forEach { tags.link(productId, it) }
        if (linked != wanted) changed = true
        return if (changed) {
            TxResult.Commit(SaveProductExtrasResult.Saved)
        } else {
            TxResult.Rollback(SaveProductExtrasResult.Saved)
        }
    }
}

/** Los campos y las etiquetas, para su pantalla de administración. */
class ObserveExtrasCatalogUseCase(
    private val fields: CustomFieldRepository,
    private val tags: TagRepository,
) {
    operator fun invoke(): Flow<Pair<List<CustomField>, List<ProductTag>>> =
        combine(fields.observeFields(), tags.observeTags()) { all, labels -> all to labels }
            .distinctUntilChanged()
}

/**
 * Lo propio del producto [productId] para su formulario: los campos vigentes, los valores que tiene
 * y las etiquetas. Sin [productId], un producto nuevo: sin valores ni etiquetas.
 */
class ObserveProductExtrasUseCase(
    private val fields: CustomFieldRepository,
    private val tags: TagRepository,
) {
    operator fun invoke(productId: Long?): Flow<ProductExtras> {
        val values =
            productId?.let { fields.observeValues(it) }
                ?: kotlinx.coroutines.flow.flowOf(emptyMap())
        val linked =
            productId?.let { tags.observeTagIdsOf(it) }
                ?: kotlinx.coroutines.flow.flowOf(emptySet())
        return combine(fields.observeFields(), values, tags.observeTags(), linked) {
                all,
                stored,
                labels,
                ids,
            ->
            ProductExtras(all.filterNot { it.isArchived }, stored, labels, ids)
        }.distinctUntilChanged()
    }
}

/** El índice de etiquetas por producto, para que la búsqueda encuentre por ellas. */
class ObserveTagSearchIndexUseCase(private val tags: TagRepository) {
    operator fun invoke(): Flow<Map<Long, List<String>>> = tags.observeSearchIndex()
}
