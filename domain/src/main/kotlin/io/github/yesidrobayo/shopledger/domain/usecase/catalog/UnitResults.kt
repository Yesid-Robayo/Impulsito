package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionInputError
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitInputError
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/** Cómo terminó crear o editar una unidad. */
sealed interface SaveUnitResult {
    /** Quedó guardada, o ya estaba exactamente así. [id] es el de la unidad. */
    data class Saved(val id: Long) : SaveUnitResult

    /** No se guardó nada: cada error va en su campo, y lo escrito se conserva. */
    data class Invalid(val errors: Set<UnitInputError>) : SaveUnitResult

    /** Otra unidad vigente ya se llama así, comparado sin mayúsculas ni tildes. */
    data object DuplicateName : SaveUnitResult

    /** La unidad ya no existe, o se archivó mientras se editaba. */
    data object NotFound : SaveUnitResult

    /** La base no pudo escribir. No quedó nada a medias, y lo escrito se conserva. */
    data class StorageFailed(val failure: StorageFailure) : SaveUnitResult
}

/** Cómo terminó archivar, reactivar o eliminar una unidad. */
sealed interface UnitLifecycleResult {
    /** Se hizo lo pedido. */
    data object Done : UnitLifecycleResult

    /** La unidad ya no existe. */
    data object NotFound : UnitLifecycleResult

    /** Ya estaba como se pedía: archivada al archivar, o vigente al reactivar. Nada cambió. */
    data object Unchanged : UnitLifecycleResult

    /** Es la unidad de conteo inicial, base de todo producto que no dice otra: no se archiva ni se elimina. */
    data object Protected : UnitLifecycleResult

    /** Tiene uso: no se elimina, solo se archiva. */
    data object InUse : UnitLifecycleResult

    /** Una unidad vigente ya usa su nombre: hay que cambiar uno de los dos antes de reactivarla. */
    data object NameTaken : UnitLifecycleResult

    data class StorageFailed(val failure: StorageFailure) : UnitLifecycleResult
}

/** Cómo terminó crear o editar una conversión. */
sealed interface SaveConversionResult {
    /** Quedó guardada, o ya estaba exactamente así. [id] es el de la conversión. */
    data class Saved(val id: Long) : SaveConversionResult

    /** No se guardó nada: cada error va en su campo, y lo escrito se conserva. */
    data class Invalid(val errors: Set<ConversionInputError>) : SaveConversionResult

    /** El producto ya tiene una conversión a esa unidad: se edita la que hay. */
    data object UnitTaken : SaveConversionResult

    /** Otra conversión, u otro producto vigente, ya usa ese código: [productName] es de quién. */
    data class DuplicateBarcode(val productName: String) : SaveConversionResult

    /** El producto no existe o está archivado. */
    data object ProductUnavailable : SaveConversionResult

    /** La unidad no existe o está archivada: no se ofrece para una conversión nueva. */
    data object UnitUnavailable : SaveConversionResult

    /** La conversión que se editaba ya no existe. */
    data object NotFound : SaveConversionResult

    data class StorageFailed(val failure: StorageFailure) : SaveConversionResult
}

/** Cómo terminó quitar una conversión. */
sealed interface DeleteConversionResult {
    data object Deleted : DeleteConversionResult

    data object NotFound : DeleteConversionResult

    data class StorageFailed(val failure: StorageFailure) : DeleteConversionResult
}
