package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDraft
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitCatalogRepository
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitFormCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitFormRules

/**
 * Crea una unidad, o edita una vigente, en una sola transacción.
 *
 * El nombre es único entre las vigentes, comparado sin mayúsculas ni tildes. Una unidad inicial sin
 * nombre propio sigue el idioma de la interfaz; con uno, deja de seguirlo. Las líneas pasadas no
 * cambian: cada una guarda su unidad y su factor. Una edición que no cambia nada no escribe nada ni
 * cuenta un cambio sin respaldar.
 */
class SaveUnitUseCase(
    private val transactions: TransactionRunner,
    private val units: UnitCatalogRepository,
    private val clock: Clock,
) {
    /** Crea la unidad de [draft] si [editingId] es nulo, o edita esa unidad. */
    suspend operator fun invoke(editingId: Long?, draft: UnitDraft): SaveUnitResult =
        storageSafe(SaveUnitResult::StorageFailed) {
            transactions.write { save(editingId, draft) }
        }

    private suspend fun save(editingId: Long?, draft: UnitDraft): TxResult<SaveUnitResult> {
        val current = editingId?.let { units.findById(it) }
        if (editingId != null && (current == null || current.isArchived)) {
            return TxResult.Rollback(SaveUnitResult.NotFound)
        }
        val details =
            when (val check = UnitFormRules.check(draft, isInitial = current?.systemKey != null)) {
                is UnitFormCheck.Invalid ->
                    return TxResult.Rollback(SaveUnitResult.Invalid(check.errors))

                is UnitFormCheck.Valid -> check.details
            }
        val clash = details.normalizedName?.let { units.findActiveByNormalizedName(it) }
        if (clash != null && clash.id != editingId) {
            return TxResult.Rollback(SaveUnitResult.DuplicateName)
        }
        if (current == null) {
            return TxResult.Commit(SaveUnitResult.Saved(units.insert(details, clock.now())))
        }
        if (current.matches(details)) return TxResult.Rollback(SaveUnitResult.Saved(current.id))
        units.update(current.id, details, clock.now())
        return TxResult.Commit(SaveUnitResult.Saved(current.id))
    }

    private fun MeasureUnit.matches(details: UnitDetails): Boolean =
        name == details.name && symbol == details.symbol && kind == details.kind &&
            allowsFraction == details.allowsFraction
}
