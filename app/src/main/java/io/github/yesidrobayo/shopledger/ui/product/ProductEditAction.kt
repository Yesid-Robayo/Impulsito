package io.github.yesidrobayo.shopledger.ui.product

import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage

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

    /** Encendió o apagó el control por lotes del producto. */
    data class TracksLotsChange(val enabled: Boolean) : ProductEditAction

    /** Eligió si el producto se vende, es un insumo o las dos cosas. */
    data class UsageChange(val usage: ProductUsage) : ProductEditAction

    /** Quiere crear una unidad sin salir del formulario. */
    data object NewUnit : ProductEditAction

    /** Quiere administrar las unidades: lo resuelve la navegación. */
    data object ManageUnits : ProductEditAction

    /** Abre las variantes del producto guardado. */
    data object OpenVariants : ProductEditAction

    /** Quiere ver la receta del combo que edita: lo resuelve la navegación. */
    data object OpenRecipe : ProductEditAction

    /** Quiere elegir los extras que se ofrecen al venderlo: lo resuelve la navegación. */
    data object OpenExtras : ProductEditAction

    /**
     * Quiere cambiar el costo de un producto con movimientos, que aquí no se edita: lo resuelve la
     * navegación, con la hoja `Corregir costo` del detalle.
     */
    data object FixCost : ProductEditAction

    /** Quiere agregar una conversión de compra o venta a este producto. */
    data object NewConversion : ProductEditAction

    /** Abrió una conversión del producto para editarla. */
    data class EditConversion(val conversionId: Long) : ProductEditAction

    /** Abre la hoja del precio del producto en la lista [listId]. */
    data class EditListPrice(val listId: Long) : ProductEditAction

    data class SalePriceChange(val text: String) : ProductEditAction

    data class CostChange(val text: String) : ProductEditAction

    /**
     * Eligió escribir el costo por unidad o, con [byTotal], lo que costó todo el stock inicial.
     * Lo escrito se conserva: la cifra que sale de él se muestra debajo con su nombre.
     */
    data class CostModeChange(val byTotal: Boolean) : ProductEditAction

    data class InitialStockChange(val text: String) : ProductEditAction

    data class MinStockChange(val text: String) : ProductEditAction

    data class BarcodeChange(val text: String) : ProductEditAction

    /** Quiere leer el código con la cámara: lo resuelve la navegación, que abre el escáner. */
    data object ScanBarcode : ProductEditAction

    /**
     * Quiere un código propio para un producto guardado que no trae uno: `P` y su id. Se guarda en
     * el acto, y un choque con otro producto se marca en el campo.
     */
    data object GenerateBarcode : ProductEditAction

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

    /** Abre el selector de fotos del sistema. Lo resuelve la ruta. */
    data object ChoosePhoto : ProductEditAction

    /** La persona eligió una foto en el selector: [source] es el documento, que no se guarda. */
    data class PickPhoto(val source: String) : ProductEditAction

    /** Quita la foto. Al guardar, si nada más la usa, queda marcada para borrarse. */
    data object RemovePhoto : ProductEditAction

    /** Escribió en el campo propio [fieldId], tal como se ve; vacío lo deja sin valor. */
    data class ExtraValueChange(val fieldId: Long, val text: String) : ProductEditAction

    /** Puso o quitó la etiqueta [tagId]. */
    data class ExtraTagToggle(val tagId: Long) : ProductEditAction

    /** Quiere administrar los campos propios o las etiquetas: lo resuelve la navegación. */
    data object ManageExtras : ProductEditAction
}
