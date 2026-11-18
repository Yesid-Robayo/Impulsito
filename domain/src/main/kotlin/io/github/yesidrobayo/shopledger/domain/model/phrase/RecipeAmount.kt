package io.github.yesidrobayo.shopledger.domain.model.phrase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Lo que dice la receta de un insumo dicho en una producción, para mostrar en la tarjeta en qué se
 * aparta lo dicho.
 */
sealed interface RecipeAmount {
    /**
     * No hay con qué comparar: lo producido no tiene receta, o falta su cantidad, o falta elegir el
     * insumo.
     */
    data object None : RecipeAmount

    /** La receta no lo lleva: entra solo en esta producción, salvo que se actualice la receta. */
    data object NotInRecipe : RecipeAmount

    /**
     * Para esta tanda, la receta lleva [quantity] milésimas de [unitId]: la unidad dicha si se
     * puede llevar a ella, o la base del insumo. [same] dice si es lo que se dijo.
     */
    data class InRecipe(val quantity: QuantityMilli, val unitId: Long, val same: Boolean) :
        RecipeAmount
}
