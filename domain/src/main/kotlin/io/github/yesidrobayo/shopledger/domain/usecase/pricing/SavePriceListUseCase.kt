package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDetails
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDraft
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.pricing.PriceListFormCheck
import io.github.yesidrobayo.shopledger.domain.rule.pricing.PriceListFormRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/**
 * Crea una lista de precios, o edita una vigente, en una sola transacción.
 *
 * - El nombre es único entre las vigentes, comparado sin mayúsculas ni tildes.
 * - La inicial no se edita: es el precio de venta, con su nombre traducido, y sin ella no hay
 *   precio de partida.
 * - Una relativa parte de una lista vigente, y esa cadena nunca vuelve a la lista que se edita: el
 *   ciclo se rechaza con su resultado antes de escribir, y la base lo vuelve a comprobar al guardar.
 * - Una fija con precios propios no pasa a relativa: esos precios quedarían sin uso.
 *
 * Las ventas pasadas no cambian: cada línea congeló su precio y su lista. Una edición que no cambia
 * nada no escribe nada ni cuenta un cambio sin respaldar.
 */
class SavePriceListUseCase(
    private val transactions: TransactionRunner,
    private val priceLists: PriceListRepository,
    private val productPrices: ProductPriceRepository,
    private val clock: Clock,
) {
    /** Crea la lista de [draft] si [editingId] es nulo, o edita esa lista. */
    suspend operator fun invoke(editingId: Long?, draft: PriceListDraft): SavePriceListResult =
        storageSafe(SavePriceListResult::StorageFailed) {
            transactions.write { save(editingId, draft) }
        }

    private suspend fun save(
        editingId: Long?,
        draft: PriceListDraft,
    ): TxResult<SavePriceListResult> {
        val current = editingId?.let { priceLists.findById(it) }
        if (editingId != null && (current == null || current.isArchived)) {
            return TxResult.Rollback(SavePriceListResult.NotFound)
        }
        if (current?.isDefault == true) return TxResult.Rollback(SavePriceListResult.Protected)
        val details =
            when (val check = PriceListFormRules.check(draft)) {
                is PriceListFormCheck.Invalid ->
                    return TxResult.Rollback(SavePriceListResult.Invalid(check.errors))

                is PriceListFormCheck.Valid -> check.details
            }
        val refused = refusal(current, details)
        if (refused != null) return TxResult.Rollback(refused)
        if (current == null) {
            return TxResult.Commit(
                SavePriceListResult.Saved(priceLists.insert(details, clock.now())),
            )
        }
        if (current.matches(details)) {
            return TxResult.Rollback(SavePriceListResult.Saved(current.id))
        }
        // La sentencia vuelve a mirar la cadena: otra escritura pudo cambiarla tras la lectura.
        if (!priceLists.update(current.id, details, clock.now())) {
            return TxResult.Rollback(SavePriceListResult.CreatesCycle)
        }
        return TxResult.Commit(SavePriceListResult.Saved(current.id))
    }

    /** Por qué no se guarda [details] sobre [current], o `null` si se puede guardar. */
    private suspend fun refusal(
        current: PriceList?,
        details: PriceListDetails,
    ): SavePriceListResult? {
        val clash = priceLists.findActiveByNormalizedName(details.normalizedName)
        if (clash != null && clash.id != current?.id) return SavePriceListResult.DuplicateName
        val relative = details.pricing as? PriceListPricing.Relative ?: return null
        val base = priceLists.findById(relative.baseId)
        // Una base archivada después sigue sirviendo a la lista que ya partía de ella.
        val keepsBase = (current?.pricing as? PriceListPricing.Relative)?.baseId == relative.baseId
        return when {
            current != null && priceLists.wouldCreateCycle(current.id, relative.baseId) ->
                SavePriceListResult.CreatesCycle

            base == null || (base.isArchived && !keepsBase) -> SavePriceListResult.BaseUnavailable

            current != null && current.pricing == PriceListPricing.Fixed &&
                productPrices.hasAny(current.id) -> SavePriceListResult.HasOwnPrices

            else -> null
        }
    }

    private fun PriceList.matches(details: PriceListDetails): Boolean =
        name == details.name && pricing == details.pricing
}
