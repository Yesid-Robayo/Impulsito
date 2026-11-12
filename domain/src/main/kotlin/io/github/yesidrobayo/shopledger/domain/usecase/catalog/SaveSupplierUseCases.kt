package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierFormIssue
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.SupplierFormCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.SupplierFormRules

/** Cómo termina crear o editar un proveedor. Salvo [Saved], no se guardó nada. */
sealed interface SaveSupplierResult {
    data class Saved(val supplierId: Long) : SaveSupplierResult

    /** Lo escrito no se puede guardar, por todos estos motivos a la vez. */
    data class Invalid(val issues: Set<SupplierFormIssue>) : SaveSupplierResult

    /**
     * Otro proveedor sin archivar ya tiene ese nombre, comparado sin mayúsculas ni tildes.
     * [existingName] es el suyo tal como se guardó.
     */
    data class DuplicateName(val existingName: String) : SaveSupplierResult

    /** El proveedor que se editaba ya no existe. */
    data object NotFound : SaveSupplierResult

    data class StorageFailed(val failure: StorageFailure) : SaveSupplierResult
}

/**
 * Crea un proveedor, también desde una compra o un producto, sin salir de ellos. Basta el nombre.
 *
 * El nombre no se repite entre proveedores sin archivar, comparado sin mayúsculas ni tildes: dos
 * proveedores que solo se distinguen por una tilde partirían sus compras sin que nadie lo note. Un
 * archivado no reserva el suyo.
 */
class CreateSupplierUseCase(
    private val transactions: TransactionRunner,
    private val suppliers: SupplierRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(form: SupplierForm): SaveSupplierResult =
        storageSafe(SaveSupplierResult::StorageFailed) {
            transactions.write { create(form) }
        }

    private suspend fun create(form: SupplierForm): TxResult<SaveSupplierResult> {
        val details = when (val check = SupplierFormRules.check(form)) {
            is SupplierFormCheck.Invalid ->
                return TxResult.Rollback(SaveSupplierResult.Invalid(check.issues))

            is SupplierFormCheck.Valid -> check.details
        }
        suppliers.findActiveByNormalizedName(details.normalizedName)?.let {
            return TxResult.Rollback(SaveSupplierResult.DuplicateName(it.details.name))
        }
        val id = suppliers.insert(details, clock.now())
        return TxResult.Commit(SaveSupplierResult.Saved(id))
    }
}

/**
 * Cambia los datos de un proveedor, también archivado: así se le puede cambiar el nombre o vaciar
 * su teléfono, su correo y sus notas aunque sus compras impidan borrarlo. Sus compras siguen ligadas
 * a la misma fila, con sus valores intactos, y su saldo no se toca.
 *
 * Un archivado no reserva su nombre, así que solo un proveedor vigente choca con otro. Guardar sin
 * cambios no escribe nada ni cuenta un cambio sin respaldar.
 */
class EditSupplierUseCase(
    private val transactions: TransactionRunner,
    private val suppliers: SupplierRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(supplierId: Long, form: SupplierForm): SaveSupplierResult =
        storageSafe(SaveSupplierResult::StorageFailed) {
            transactions.write { edit(supplierId, form) }
        }

    private suspend fun edit(supplierId: Long, form: SupplierForm): TxResult<SaveSupplierResult> {
        val supplier = suppliers.findById(supplierId)
            ?: return TxResult.Rollback(SaveSupplierResult.NotFound)
        val details = when (val check = SupplierFormRules.check(form)) {
            is SupplierFormCheck.Invalid ->
                return TxResult.Rollback(SaveSupplierResult.Invalid(check.issues))

            is SupplierFormCheck.Valid -> check.details
        }
        if (!supplier.isArchived) {
            suppliers.findActiveByNormalizedName(details.normalizedName)
                ?.takeIf { it.id != supplierId }
                ?.let {
                    return TxResult.Rollback(SaveSupplierResult.DuplicateName(it.details.name))
                }
        }
        if (details == supplier.details) {
            return TxResult.Rollback(SaveSupplierResult.Saved(supplierId))
        }
        suppliers.update(supplierId, details, clock.now())
        return TxResult.Commit(SaveSupplierResult.Saved(supplierId))
    }
}
