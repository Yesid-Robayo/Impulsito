package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode

/** Lo que la persona hace en el formulario de un producto. */
sealed interface ProductEditAction {
    /** Vuelve a leer tras una falla de lectura. */
    data object Retry : ProductEditAction

    data class NameChange(val name: String) : ProductEditAction

    /** Salió del campo del nombre: se comprueba si otro producto ya lo usa. */
    data object NameLeft : ProductEditAction

    /** Eligió una categoría, o sin categoría con `null`. */
    data class CategoryChange(val categoryId: Long?) : ProductEditAction

    /** Creó una categoría desde la hoja, con lo que escribió. */
    data class CreateCategory(val name: String) : ProductEditAction

    data class UnitChange(val unitId: Long) : ProductEditAction

    /** Eligió el modo de stock: con stock propio o sin stock, para un servicio. */
    data class StockModeChange(val mode: ProductStockMode) : ProductEditAction

    /** Quiere crear una unidad sin salir del formulario. */
    data object NewUnit : ProductEditAction

    /** Quiere administrar las unidades: lo resuelve la navegación. */
    data object ManageUnits : ProductEditAction

    /** Abre las variantes del producto guardado. */
    data object OpenVariants : ProductEditAction

    /** Quiere agregar una conversión de compra o venta a este producto. */
    data object NewConversion : ProductEditAction

    /** Abrió una conversión del producto para editarla. */
    data class EditConversion(val conversionId: Long) : ProductEditAction

    /** Abre la hoja del precio del producto en la lista [listId]. */
    data class EditListPrice(val listId: Long) : ProductEditAction

    data class SalePriceChange(val text: String) : ProductEditAction

    data class CostChange(val text: String) : ProductEditAction

    data class InitialStockChange(val text: String) : ProductEditAction

    data class MinStockChange(val text: String) : ProductEditAction

    data class BarcodeChange(val text: String) : ProductEditAction

    /** Corrigió a mano lo que costaría reponer una unidad. Vacío es sin dato. */
    data class ReplacementCostChange(val text: String) : ProductEditAction

    /** Escribió el precio de referencia, en la moneda de referencia. */
    data class ReferencePriceChange(val text: String) : ProductEditAction

    data object Save : ProductEditAction

    /** Quiere cerrar: con cambios sin guardar, primero pregunta. */
    data object Close : ProductEditAction

    data object KeepEditing : ProductEditAction

    /** Confirmó descartar lo escrito. */
    data object Discard : ProductEditAction

    /** Quiere confirmar la moneda del negocio: lo resuelve la navegación. */
    data object ConfirmCurrency : ProductEditAction

    /** Abre la sugerencia de precio con el margen objetivo del producto. */
    data object OpenSuggestion : ProductEditAction

    /** Escribió otro margen objetivo en la sugerencia. No se guarda en ninguna parte. */
    data class SuggestionTarget(val text: String) : ProductEditAction

    /** Pone el precio sugerido como precio de venta y cierra la sugerencia. */
    data object UseSuggestedPrice : ProductEditAction

    data object DismissSuggestion : ProductEditAction
}
