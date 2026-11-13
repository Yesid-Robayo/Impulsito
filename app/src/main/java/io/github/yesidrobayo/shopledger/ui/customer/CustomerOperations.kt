package io.github.yesidrobayo.shopledger.ui.customer

import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CreateCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomersOverview
import io.github.yesidrobayo.shopledger.domain.usecase.customer.EditCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ObserveCustomersUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ObserveLoyaltyProgramUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ReactivateCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Lo que las pantallas de clientes le piden al dominio. Es una interfaz para que las pruebas de sus
 * ViewModel den respuestas fijas sin una base.
 */
interface CustomerOperations {
    fun customers(): Flow<CustomersOverview>

    /** Si el programa de puntos está activo: solo entonces la lista muestra los puntos. */
    fun loyaltyEnabled(): Flow<Boolean>

    /**
     * Crea un cliente si [editingId] es nulo, o edita ese. Con [allowSameName], la persona ya vio el
     * aviso de nombre repetido y guarda igual.
     */
    suspend fun save(
        editingId: Long?,
        form: CustomerForm,
        allowSameName: Boolean,
    ): SaveCustomerResult

    suspend fun reactivate(customerId: Long): CustomerLifecycleResult
}

/** Las operaciones sobre los casos de uso de clientes. */
class CustomerUseCases(
    private val observeCustomers: ObserveCustomersUseCase,
    private val observeLoyalty: ObserveLoyaltyProgramUseCase,
    private val create: CreateCustomerUseCase,
    private val edit: EditCustomerUseCase,
    private val reactivateUseCase: ReactivateCustomerUseCase,
) : CustomerOperations {
    override fun customers(): Flow<CustomersOverview> = observeCustomers()

    override fun loyaltyEnabled(): Flow<Boolean> =
        observeLoyalty().map { it.settings.enabled }.distinctUntilChanged()

    override suspend fun save(
        editingId: Long?,
        form: CustomerForm,
        allowSameName: Boolean,
    ): SaveCustomerResult = if (editingId == null) {
        create(form, allowSameName)
    } else {
        edit(editingId, form, allowSameName)
    }

    override suspend fun reactivate(customerId: Long): CustomerLifecycleResult =
        reactivateUseCase(customerId)

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): CustomerUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val tx = ports.transactions
            return CustomerUseCases(
                observeCustomers = ObserveCustomersUseCase(ports.customers),
                observeLoyalty = ObserveLoyaltyProgramUseCase(ports.settings, ports.customers),
                create = CreateCustomerUseCase(tx, ports.customers, clock),
                edit = EditCustomerUseCase(tx, ports.customers, clock),
                reactivateUseCase = ReactivateCustomerUseCase(tx, ports.customers, clock),
            )
        }
    }
}

/** Un aviso breve, abajo, tras una acción sobre un cliente. */
sealed interface CustomerMessage {
    data object Saved : CustomerMessage

    data object Reactivated : CustomerMessage

    data class Failed(val outOfSpace: Boolean) : CustomerMessage
}

/** El aviso que deja reactivar, según [result], o `null` si no hay nada que decir. */
internal fun reactivateMessage(result: CustomerLifecycleResult): CustomerMessage? = when (result) {
    CustomerLifecycleResult.Done -> CustomerMessage.Reactivated

    // Ya estaba vigente, o ya no existe: la lista ya muestra cómo quedó. Reactivar no choca con
    // otro cliente ni pide historia.
    CustomerLifecycleResult.Unchanged, CustomerLifecycleResult.NotFound,
    CustomerLifecycleResult.HasHistory,
    -> null

    is CustomerLifecycleResult.StorageFailed -> CustomerMessage.Failed(result.failure.isOutOfSpace)
}
