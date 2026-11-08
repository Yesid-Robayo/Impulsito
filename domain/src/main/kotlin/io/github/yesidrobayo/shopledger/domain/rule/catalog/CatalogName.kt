package io.github.yesidrobayo.shopledger.domain.rule.catalog

/** Resultado de [CatalogText.name]. */
sealed interface CatalogName {
    /** El nombre recortado, y su forma normalizada para comparar y buscar. */
    data class Valid(val name: String, val normalizedName: String) : CatalogName

    /** Vacío tras recortarlo, o hecho solo de marcas sin letra. */
    data object Empty : CatalogName

    /** Pasa del largo máximo. */
    data object TooLong : CatalogName
}
