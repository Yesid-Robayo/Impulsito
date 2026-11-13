package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerFormIssue
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerFormCheck
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerFormRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/** Cómo termina crear o editar un cliente. Salvo [Saved], no se guardó nada. */
sealed interface SaveCustomerResult {
    data class Saved(val customerId: Long) : SaveCustomerResult

    /** Lo escrito no se puede guardar, por todos estos motivos a la vez. */
    data class Invalid(val issues: Set<CustomerFormIssue>) : SaveCustomerResult

    /**
     * Otro cliente sin archivar ya se llama así, comparado sin mayúsculas ni tildes. No es un error:
     * dos personas pueden llamarse igual. La pantalla lo avisa, y si la persona sigue, se vuelve a
     * pedir permitiendo el nombre repetido.
     */
    data object SameName : SaveCustomerResult

    /** El cliente que se editaba ya no existe. */
    data object NotFound : SaveCustomerResult

    /** El cliente que se editaba se anonimizó: ya no tiene datos que editar, y no se deshace. */
    data object Anonymized : SaveCustomerResult

    data class StorageFailed(val failure: StorageFailure) : SaveCustomerResult
}

/**
 * Crea un cliente con lo mínimo, también desde una venta, sin salir de ella. Basta el nombre: no se
 * pide documento de identidad, y teléfono, correo y notas son opcionales.
 *
 * El nombre no es único. Si otro cliente vigente se llama igual, devuelve
 * [SaveCustomerResult.SameName] sin escribir nada, para que la pantalla avise del posible duplicado;
 * con `allowSameName`, la persona ya lo vio y se crea igual.
 */
class CreateCustomerUseCase(
    private val transactions: TransactionRunner,
    private val customers: CustomerRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        form: CustomerForm,
        allowSameName: Boolean = false,
    ): SaveCustomerResult = storageSafe(SaveCustomerResult::StorageFailed) {
        transactions.write { create(form, allowSameName) }
    }

    private suspend fun create(
        form: CustomerForm,
        allowSameName: Boolean,
    ): TxResult<SaveCustomerResult> {
        val details = when (val check = CustomerFormRules.check(form)) {
            is CustomerFormCheck.Invalid ->
                return TxResult.Rollback(SaveCustomerResult.Invalid(check.issues))

            is CustomerFormCheck.Valid -> check.details
        }
        if (!allowSameName &&
            customers.findActiveByNormalizedName(details.contact.normalizedName).isNotEmpty()
        ) {
            return TxResult.Rollback(SaveCustomerResult.SameName)
        }
        val id = customers.insert(details, clock.now())
        return TxResult.Commit(SaveCustomerResult.Saved(id))
    }
}

/**
 * Cambia los datos de un cliente, también archivado: así se le puede corregir el nombre o vaciar su
 * teléfono, su correo y sus notas aunque su historia impida borrarlo. Sus ventas y sus libros siguen
 * ligados a la misma fila, con sus montos intactos, y sus saldos no se tocan.
 *
 * Solo avisa de un nombre repetido si el nombre cambia a uno que ya usa otro cliente vigente:
 * conservar el propio nunca avisa. Un cliente anonimizado no se edita. Guardar sin cambios no
 * escribe nada ni cuenta un cambio sin respaldar.
 */
class EditCustomerUseCase(
    private val transactions: TransactionRunner,
    private val customers: CustomerRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        customerId: Long,
        form: CustomerForm,
        allowSameName: Boolean = false,
    ): SaveCustomerResult = storageSafe(SaveCustomerResult::StorageFailed) {
        transactions.write { edit(customerId, form, allowSameName) }
    }

    private suspend fun edit(
        customerId: Long,
        form: CustomerForm,
        allowSameName: Boolean,
    ): TxResult<SaveCustomerResult> {
        val customer = customers.findById(customerId)
            ?: return TxResult.Rollback(SaveCustomerResult.NotFound)
        val current = customer.details
            ?: return TxResult.Rollback(SaveCustomerResult.Anonymized)
        val details = when (val check = CustomerFormRules.check(form)) {
            is CustomerFormCheck.Invalid ->
                return TxResult.Rollback(SaveCustomerResult.Invalid(check.issues))

            is CustomerFormCheck.Valid -> check.details
        }
        val normalizedName = details.contact.normalizedName
        val renamed = normalizedName != current.contact.normalizedName
        if (renamed && !allowSameName &&
            customers.findActiveByNormalizedName(normalizedName).any { it.id != customerId }
        ) {
            return TxResult.Rollback(SaveCustomerResult.SameName)
        }
        if (details == current) {
            return TxResult.Rollback(SaveCustomerResult.Saved(customerId))
        }
        customers.update(customerId, details, clock.now())
        return TxResult.Commit(SaveCustomerResult.Saved(customerId))
    }
}
