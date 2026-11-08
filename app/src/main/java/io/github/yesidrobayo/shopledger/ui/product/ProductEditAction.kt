package io.github.yesidrobayo.shopledger.ui.product

/** Lo que la persona hace en el formulario de un producto. */
sealed interface ProductEditAction {
    data class NameChange(val name: String) : ProductEditAction

    /** Salió del campo del nombre: se comprueba si otro producto ya lo usa. */
    data object NameLeft : ProductEditAction

    /** Eligió una categoría, o sin categoría con `null`. */
    data class CategoryChange(val categoryId: Long?) : ProductEditAction

    /** Creó una categoría desde la hoja, con lo que escribió. */
    data class CreateCategory(val name: String) : ProductEditAction

    data class UnitChange(val unitId: Long) : ProductEditAction

    data class SalePriceChange(val text: String) : ProductEditAction

    data class CostChange(val text: String) : ProductEditAction

    data class InitialStockChange(val text: String) : ProductEditAction

    data class MinStockChange(val text: String) : ProductEditAction

    data class BarcodeChange(val text: String) : ProductEditAction

    data object Save : ProductEditAction

    /** Quiere cerrar: con cambios sin guardar, primero pregunta. */
    data object Close : ProductEditAction

    data object KeepEditing : ProductEditAction

    /** Confirmó descartar lo escrito. */
    data object Discard : ProductEditAction

    /** Quiere confirmar la moneda del negocio: lo resuelve la navegación. */
    data object ConfirmCurrency : ProductEditAction
}
