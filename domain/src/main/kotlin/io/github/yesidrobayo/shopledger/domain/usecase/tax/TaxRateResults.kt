package io.github.yesidrobayo.shopledger.domain.usecase.tax

import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateInputError
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/** Cómo terminó crear o editar una tasa. */
sealed interface SaveTaxRateResult {
    /** Quedó guardada, o ya estaba exactamente así. [id] es el de la tasa. */
    data class Saved(val id: Long) : SaveTaxRateResult

    /** No se guardó nada: cada error va en su campo, y lo escrito se conserva. */
    data class Invalid(val errors: Set<TaxRateInputError>) : SaveTaxRateResult

    /** La tasa ya no existe, por ejemplo porque se eliminó mientras se editaba. */
    data object NotFound : SaveTaxRateResult

    /** La tasa se archivó mientras se editaba: primero hay que reactivarla. */
    data object Archived : SaveTaxRateResult

    /** La base no pudo escribir. No quedó nada a medias, y lo escrito se conserva. */
    data class StorageFailed(val failure: StorageFailure) : SaveTaxRateResult
}

/** Cómo terminó archivar una tasa. */
sealed interface ArchiveTaxRateResult {
    /** Quedó archivada: ya no se ofrece, y las ventas y compras pasadas no cambian. */
    data object Archived : ArchiveTaxRateResult

    data object NotFound : ArchiveTaxRateResult

    /** Ya estaba archivada. Nada cambió. */
    data object AlreadyArchived : ArchiveTaxRateResult

    data class StorageFailed(val failure: StorageFailure) : ArchiveTaxRateResult
}

/** Cómo terminó reactivar una tasa archivada. */
sealed interface ReactivateTaxRateResult {
    data object Reactivated : ReactivateTaxRateResult

    data object NotFound : ReactivateTaxRateResult

    /** No estaba archivada. Nada cambió. */
    data object AlreadyActive : ReactivateTaxRateResult

    /** Una tasa vigente ya usa su nombre: hay que cambiar uno de los dos antes. */
    data object NameTaken : ReactivateTaxRateResult

    data class StorageFailed(val failure: StorageFailure) : ReactivateTaxRateResult
}

/** Cómo terminó eliminar una tasa. */
sealed interface DeleteTaxRateResult {
    data object Deleted : DeleteTaxRateResult

    data object NotFound : DeleteTaxRateResult

    /** La usa algún producto o es la tasa por defecto: solo se archiva. */
    data object InUse : DeleteTaxRateResult

    data class StorageFailed(val failure: StorageFailure) : DeleteTaxRateResult
}

/** Cómo terminó cambiar un ajuste de impuestos. */
sealed interface ChangeTaxSettingsResult {
    data object Changed : ChangeTaxSettingsResult

    /** Ya tenía ese valor: no se escribió nada ni se contó un cambio. */
    data object Unchanged : ChangeTaxSettingsResult

    data class StorageFailed(val failure: StorageFailure) : ChangeTaxSettingsResult
}
