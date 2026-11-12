package io.github.yesidrobayo.shopledger.ui.supplier

import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierOverview
import io.github.yesidrobayo.shopledger.domain.model.catalog.SuppliersOverview
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveSupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateSupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteSupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditSupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveSupplierOverviewUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveSuppliersUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateSupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveSupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SupplierLifecycleResult
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import kotlinx.coroutines.flow.Flow

/**
 * Lo que las pantallas de proveedores le piden al dominio. Es una interfaz para que las pruebas de
 * sus ViewModel den respuestas fijas sin una base.
 */
interface SupplierOperations {
    fun suppliers(): Flow<SuppliersOverview>

    fun overview(supplierId: Long): Flow<SupplierOverview?>

    /** Crea un proveedor si [editingId] es nulo, o edita ese. */
    suspend fun save(editingId: Long?, form: SupplierForm): SaveSupplierResult

    suspend fun archive(supplierId: Long): SupplierLifecycleResult

    suspend fun reactivate(supplierId: Long): SupplierLifecycleResult

    suspend fun delete(supplierId: Long): SupplierLifecycleResult
}

/** Las operaciones sobre los casos de uso de proveedores. */
class SupplierUseCases(
    private val observeSuppliers: ObserveSuppliersUseCase,
    private val observeOverview: ObserveSupplierOverviewUseCase,
    private val create: CreateSupplierUseCase,
    private val edit: EditSupplierUseCase,
    private val archiveUseCase: ArchiveSupplierUseCase,
    private val reactivateUseCase: ReactivateSupplierUseCase,
    private val deleteUseCase: DeleteSupplierUseCase,
) : SupplierOperations {
    override fun suppliers(): Flow<SuppliersOverview> = observeSuppliers()

    override fun overview(supplierId: Long): Flow<SupplierOverview?> = observeOverview(supplierId)

    override suspend fun save(editingId: Long?, form: SupplierForm): SaveSupplierResult =
        if (editingId == null) create(form) else edit(editingId, form)

    override suspend fun archive(supplierId: Long): SupplierLifecycleResult =
        archiveUseCase(supplierId)

    override suspend fun reactivate(supplierId: Long): SupplierLifecycleResult =
        reactivateUseCase(supplierId)

    override suspend fun delete(supplierId: Long): SupplierLifecycleResult =
        deleteUseCase(supplierId)

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): SupplierUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val tx = ports.transactions
            return SupplierUseCases(
                observeSuppliers = ObserveSuppliersUseCase(ports.suppliers, ports.supplierActivity),
                observeOverview =
                    ObserveSupplierOverviewUseCase(ports.suppliers, ports.supplierActivity),
                create = CreateSupplierUseCase(tx, ports.suppliers, clock),
                edit = EditSupplierUseCase(tx, ports.suppliers, clock),
                archiveUseCase = ArchiveSupplierUseCase(tx, ports.suppliers, clock),
                reactivateUseCase = ReactivateSupplierUseCase(tx, ports.suppliers, clock),
                deleteUseCase = DeleteSupplierUseCase(tx, ports.suppliers),
            )
        }
    }
}

/** Un aviso breve, abajo, tras una acción sobre un proveedor. */
sealed interface SupplierMessage {
    data object Saved : SupplierMessage

    data object Archived : SupplierMessage

    data object Reactivated : SupplierMessage

    data object Deleted : SupplierMessage

    /** Reactivar no se pudo: otro vigente ya usa su nombre. */
    data object NameTaken : SupplierMessage

    /** Eliminar no se pudo: tiene compras y solo se archiva. */
    data object HasHistory : SupplierMessage

    data class Failed(val outOfSpace: Boolean) : SupplierMessage
}

/** El aviso que deja una acción de archivar, reactivar o eliminar, según [result]. */
internal fun lifecycleMessage(
    result: SupplierLifecycleResult,
    done: SupplierMessage,
): SupplierMessage? = when (result) {
    SupplierLifecycleResult.Done -> done

    // Ya estaba así, o ya no existe: la pantalla ya muestra cómo quedó.
    SupplierLifecycleResult.Unchanged, SupplierLifecycleResult.NotFound -> null

    SupplierLifecycleResult.HasHistory -> SupplierMessage.HasHistory

    is SupplierLifecycleResult.NameTaken -> SupplierMessage.NameTaken

    is SupplierLifecycleResult.StorageFailed ->
        SupplierMessage.Failed(result.failure.isOutOfSpace)
}

/** Lo que se confirma antes de hacerlo. [name] es el del proveedor, para el título. */
sealed interface SupplierDialog {
    val supplierId: Long
    val name: String

    data class Archive(override val supplierId: Long, override val name: String) : SupplierDialog

    data class Delete(override val supplierId: Long, override val name: String) : SupplierDialog
}
