package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationInputError
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogName
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText

/** Resultado de [LocationRules.check]. */
sealed interface LocationCheck {
    /** Se puede guardar tal cual. */
    data class Valid(val details: LocationDetails) : LocationCheck

    /** No se guarda nada: cada error va en su campo. */
    data class Invalid(val errors: Set<LocationInputError>) : LocationCheck
}

/** Lo que se revisa de una ubicación antes de guardarla. */
object LocationRules {
    /** Los caracteres más largos del nombre de una ubicación. */
    const val NAME_MAX: Int = 40

    /**
     * Si la interfaz habla de ubicaciones con [activeCount] vigentes: solo con más de una. Con una
     * sola, ninguna pantalla pregunta ni muestra dónde, aunque cada fila del libro lleve la suya.
     */
    fun showsLocations(activeCount: Int): Boolean = activeCount > 1

    /**
     * Revisa [draft]. [isInitial] dice si la ubicación tiene nombre traducido al que volver:
     * entonces un nombre en blanco no es un error, y la deja con ese nombre.
     *
     * El nombre se recorta y se normaliza para compararlo sin mayúsculas ni tildes.
     */
    fun check(draft: LocationDraft, isInitial: Boolean): LocationCheck =
        when (val checked = CatalogText.name(draft.name, NAME_MAX)) {
            CatalogName.Empty -> if (isInitial) {
                LocationCheck.Valid(LocationDetails(null, null, draft.kind))
            } else {
                LocationCheck.Invalid(setOf(LocationInputError.NAME_EMPTY))
            }

            CatalogName.TooLong -> LocationCheck.Invalid(setOf(LocationInputError.NAME_TOO_LONG))

            is CatalogName.Valid -> LocationCheck.Valid(
                LocationDetails(checked.name, checked.normalizedName, draft.kind),
            )
        }
}
