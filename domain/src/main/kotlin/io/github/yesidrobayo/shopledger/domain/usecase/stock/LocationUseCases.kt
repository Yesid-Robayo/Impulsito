package io.github.yesidrobayo.shopledger.domain.usecase.stock

import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationInputError
import io.github.yesidrobayo.shopledger.domain.model.stock.NewLocation
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.LocationRepository
import io.github.yesidrobayo.shopledger.domain.port.LocationStockSource
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.LocationCheck
import io.github.yesidrobayo.shopledger.domain.rule.stock.LocationRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/** Cómo termina crear o editar una ubicación. Salvo [Saved], no se escribió nada. */
sealed interface SaveLocationResult {
    /** Quedó guardada, o ya estaba exactamente así. [locationId] es el de la ubicación. */
    data class Saved(val locationId: Long) : SaveLocationResult

    /** Cada error va en su campo, y lo escrito se conserva. */
    data class Invalid(val errors: Set<LocationInputError>) : SaveLocationResult

    /** Otra ubicación vigente ya se llama así, comparado sin mayúsculas ni tildes. */
    data object DuplicateName : SaveLocationResult

    /** La ubicación que se editaba ya no existe. */
    data object NotFound : SaveLocationResult

    /** La base no pudo escribir. No quedó nada a medias, y lo escrito se conserva. */
    data class StorageFailed(val failure: StorageFailure) : SaveLocationResult
}

/**
 * Crea una ubicación, o cambia el nombre y la clase de una existente, en una sola transacción.
 *
 * - **Crear la segunda no mueve stock.** Todo lo que hay sigue en la ubicación donde se registró
 *   hasta que se transfiere: una ubicación nace vacía y sin filas en el libro.
 * - **El nombre es único entre las vigentes**, comparado sin mayúsculas ni tildes. La inicial sin
 *   nombre propio sigue el idioma de la interfaz, no reserva nada, y vuelve a ese nombre si se
 *   deja en blanco.
 *
 * Una edición que no cambia nada no escribe ni cuenta un cambio sin respaldar.
 */
class SaveLocationUseCase(
    private val transactions: TransactionRunner,
    private val locations: LocationRepository,
    private val clock: Clock,
) {
    /** Crea la ubicación de [draft] si [editingId] es nulo, o edita esa ubicación. */
    suspend operator fun invoke(editingId: Long?, draft: LocationDraft): SaveLocationResult =
        storageSafe(SaveLocationResult::StorageFailed) {
            transactions.write {
                if (editingId == null) create(draft) else edit(editingId, draft)
            }
        }

    private suspend fun create(draft: LocationDraft): TxResult<SaveLocationResult> {
        val details = when (val check = LocationRules.check(draft, isInitial = false)) {
            is LocationCheck.Invalid -> return reject(SaveLocationResult.Invalid(check.errors))
            is LocationCheck.Valid -> check.details
        }
        if (isTaken(details.normalizedName, editingId = null)) {
            return reject(SaveLocationResult.DuplicateName)
        }
        val id = locations.insert(NewLocation(details, clock.now()))
        return TxResult.Commit(SaveLocationResult.Saved(id))
    }

    private suspend fun edit(editingId: Long, draft: LocationDraft): TxResult<SaveLocationResult> {
        val current = locations.findById(editingId)
            ?: return reject(SaveLocationResult.NotFound)
        val details = when (val check = LocationRules.check(draft, current.isInitial)) {
            is LocationCheck.Invalid -> return reject(SaveLocationResult.Invalid(check.errors))
            is LocationCheck.Valid -> check.details
        }
        // Una archivada no reserva su nombre, así que no choca al renombrarla: choca al
        // reactivarla.
        if (!current.isArchived && isTaken(details.normalizedName, editingId)) {
            return reject(SaveLocationResult.DuplicateName)
        }
        if (current.details == details) {
            return TxResult.Rollback(SaveLocationResult.Saved(current.id))
        }
        locations.update(current.id, details, clock.now())
        return TxResult.Commit(SaveLocationResult.Saved(current.id))
    }

    /** Si otra vigente ya usa [normalizedName]. Una sin nombre propio no choca con nadie. */
    private suspend fun isTaken(normalizedName: String?, editingId: Long?): Boolean {
        if (normalizedName == null) return false
        val clash = locations.findActiveByNormalizedName(normalizedName) ?: return false
        return clash.id != editingId
    }

    private fun reject(result: SaveLocationResult): TxResult<SaveLocationResult> =
        TxResult.Rollback(result)
}

/** Cómo termina archivar o reactivar una ubicación. Salvo [Done], no se escribió nada. */
sealed interface LocationLifecycleResult {
    /** Se hizo lo pedido. */
    data object Done : LocationLifecycleResult

    /** La ubicación ya no existe. */
    data object NotFound : LocationLifecycleResult

    /** Ya estaba como se pedía: archivada al archivar, o vigente al reactivar. */
    data object Unchanged : LocationLifecycleResult

    /** Es la ubicación inicial, que nunca se archiva: siempre hay dónde anotar el stock. */
    data object Initial : LocationLifecycleResult

    /**
     * Le queda algún saldo distinto de cero. Primero hay que transferirlo a otra ubicación, o
     * ajustarlo si es un faltante: archivada, ese stock dejaría de verse.
     */
    data object HasStock : LocationLifecycleResult

    /** Otra vigente ya usa su nombre: hay que renombrar una de las dos antes de reactivarla. */
    data object NameTaken : LocationLifecycleResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : LocationLifecycleResult
}

/**
 * Archiva una ubicación: deja de ofrecerse, pero lo que se registró en ella sigue entero y se
 * puede reactivar. La inicial nunca se archiva, y ninguna con stock: archivada, ese stock dejaría
 * de verse sin dejar de contar en el total del producto.
 */
class ArchiveLocationUseCase(
    private val transactions: TransactionRunner,
    private val locations: LocationRepository,
    private val stock: LocationStockSource,
    private val clock: Clock,
) {
    suspend operator fun invoke(locationId: Long): LocationLifecycleResult =
        storageSafe(LocationLifecycleResult::StorageFailed) {
            transactions.write {
                val location = locations.findById(locationId)
                when {
                    location == null -> TxResult.Rollback(LocationLifecycleResult.NotFound)

                    location.isArchived -> TxResult.Rollback(LocationLifecycleResult.Unchanged)

                    location.isInitial -> TxResult.Rollback(LocationLifecycleResult.Initial)

                    // Se lee dentro de la transacción: nada entra al libro entre mirar y archivar.
                    stock.hasStock(locationId) ->
                        TxResult.Rollback(LocationLifecycleResult.HasStock)

                    else -> {
                        val now = clock.now()
                        locations.setArchivedAt(locationId, now, now)
                        TxResult.Commit(LocationLifecycleResult.Done)
                    }
                }
            }
        }
}

/**
 * Reactiva una ubicación archivada. Una archivada no reserva su nombre, así que mientras lo estuvo
 * otra pudo tomarlo: entonces se rechaza y se pide renombrar una de las dos.
 */
class ReactivateLocationUseCase(
    private val transactions: TransactionRunner,
    private val locations: LocationRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(locationId: Long): LocationLifecycleResult =
        storageSafe(LocationLifecycleResult::StorageFailed) {
            transactions.write {
                val location = locations.findById(locationId)
                val clash = location?.details?.normalizedName
                    ?.let { locations.findActiveByNormalizedName(it) }
                when {
                    location == null -> TxResult.Rollback(LocationLifecycleResult.NotFound)

                    !location.isArchived -> TxResult.Rollback(LocationLifecycleResult.Unchanged)

                    clash != null -> TxResult.Rollback(LocationLifecycleResult.NameTaken)

                    else -> {
                        locations.setArchivedAt(locationId, null, clock.now())
                        TxResult.Commit(LocationLifecycleResult.Done)
                    }
                }
            }
        }
}
