package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ProductListPrice
import io.github.yesidrobayo.shopledger.ui.payment.ImageProblem

/** Los campos del formulario de un producto que pueden llevar un error. */
enum class ProductEditField {
    NAME,
    SALE_PRICE,
    COST,
    INITIAL_STOCK,
    MIN_STOCK,
    BARCODE,
    REPLACEMENT_COST,
    REFERENCE_PRICE,
}

/** Por qué un campo no se puede guardar así. La pantalla lo dice con su texto. */
sealed interface FieldProblem {
    /** Falta: el nombre es obligatorio. */
    data object Empty : FieldProblem

    /** Pasa de [max] caracteres. */
    data class TooLong(val max: Int) : FieldProblem

    /** Otro producto sin archivar ya se llama [existingName], o ya tiene ese código. */
    data class Duplicate(val existingName: String) : FieldProblem

    /** No es un número. */
    data object NotANumber : FieldProblem

    /** Tiene más decimales que los [allowed] que admite. */
    data class TooManyDecimals(val allowed: Int) : FieldProblem

    /** Es demasiado alto: casi siempre, un error al escribir. */
    data object TooHigh : FieldProblem

    /** La unidad no admite decimales. */
    data object FractionNotAllowed : FieldProblem
}

/**
 * Lo que dice la tarjeta de ganancia mientras se escriben el precio y el costo. La ganancia y el
 * margen se miden sin el impuesto que el precio lleva dentro.
 */
sealed interface ProfitPreview {
    /** Sin precio no hay ganancia que mostrar. */
    data object NoPrice : ProfitPreview

    /** Sin costo no se puede calcular la ganancia real. No impide guardar. */
    data object NoCost : ProfitPreview

    /** Cada venta deja [profit] por unidad, con [margin]. */
    data class Profit(val profit: Money, val margin: Margin) : ProfitPreview

    /** Cada venta pierde [loss] por unidad. */
    data class Loss(val loss: Money) : ProfitPreview
}

/** La foto del formulario, tal como la persona la dejó. */
sealed interface EditPhoto {
    /** Sin foto. */
    data object None : EditPhoto

    /** La que el producto ya tiene guardada, por su id. */
    data class Saved(val mediaId: Long) : EditPhoto

    /** Una elegida ahora, ya recodificada en su archivo: se anota al guardar el producto. */
    data class Picked(val image: StoredImage) : EditPhoto
}

/** Un aviso del formulario sobre el guardado, arriba del botón. Lo escrito siempre se conserva. */
sealed interface ProductEditNotice {
    /** No se pudo guardar; [outOfSpace] si fue por falta de espacio. */
    data class SaveFailed(val outOfSpace: Boolean) : ProductEditNotice

    /** Con precio o costo, falta confirmar la moneda del negocio. */
    data object CurrencyUnconfirmed : ProductEditNotice
}

/**
 * Lo que la persona escribe, tal cual, y lo que eligió. [stockMode] empieza con stock propio; sin
 * stock, el costo es el costo fijo de cada unidad vendida y no hay stock que contar ni vigilar.
 * [replacementCost] vacío es sin dato: la pantalla no pone ninguna cifra en su lugar.
 * [referencePrice] va en la moneda de referencia, con sus decimales; vacío también es sin dato.
 * [photo] es la foto, elegida o guardada. [tracksLots] controla el producto por lotes, y solo
 * cuenta con stock propio.
 */
@Immutable
data class ProductEditForm(
    val name: String = "",
    val categoryId: Long? = null,
    val unitId: Long? = null,
    val stockMode: ProductStockMode = ProductStockMode.TRACKED,
    val salePrice: String = "",
    val cost: String = "",
    val initialStock: String = "",
    val minStock: String = "",
    val barcode: String = "",
    val replacementCost: String = "",
    val referencePrice: String = "",
    val photo: EditPhoto = EditPhoto.None,
    val tracksLots: Boolean = false,
)

/**
 * El estado del formulario de un producto.
 *
 * [loading] mientras se lee el producto que se edita. [notFound] si ya no existe, y [readFailed]
 * si una lectura falló: se dice, con `Reintentar`, y lo escrito sigue aquí. Con [costLocked], el
 * costo lo lleva el libro y se muestra [averageCost] solo para leer. Con [unitLocked] y
 * [stockModeLocked], la unidad base y el modo de stock ya no cambian, porque el producto tiene
 * historia, y con [lotsLocked] tampoco el control por lotes. [product] es el producto guardado, y
 * [conversions] sus conversiones de compra y venta: un producto nuevo no tiene, porque una
 * conversión necesita el producto. [savedProductId] avisa a la pantalla que puede cerrar porque se
 * guardó, y [closed], que la persona cerró sin guardar.
 *
 * [pricing] es lo que el negocio fija para medir márgenes y sugerir precios, o `null` mientras se
 * lee. [suggestTarget] es el margen objetivo con que la tarjeta ofrece sugerir un precio, o `null`
 * si no hay de dónde sugerirlo, y [suggestion] la hoja de la sugerencia, si está abierta.
 * [reference] es la moneda de referencia elegida, o `null` sin ella: entonces no se pide precio de
 * referencia. [importingPhoto] mientras se recodifica la foto elegida, y [photoProblem] si no se
 * pudo traer: la que había sigue igual. [extras] son los campos propios y las etiquetas del
 * producto, que se guardan después del producto, en su propia transacción.
 */
@Immutable
data class ProductEditUiState(
    val isNew: Boolean,
    val loading: Boolean = false,
    val notFound: Boolean = false,
    val readFailed: Boolean = false,
    val form: ProductEditForm = ProductEditForm(),
    val categories: List<Category> = emptyList(),
    val units: List<MeasureUnit> = emptyList(),
    val unitLocked: Boolean = false,
    val stockModeLocked: Boolean = false,
    val lotsLocked: Boolean = false,
    val product: Product? = null,
    val conversions: List<ProductConversion> = emptyList(),
    val costLocked: Boolean = false,
    val averageCost: Money = Money.ZERO,
    val errors: Map<ProductEditField, FieldProblem> = emptyMap(),
    val profit: ProfitPreview = ProfitPreview.NoPrice,
    val saving: Boolean = false,
    val notice: ProductEditNotice? = null,
    val dirty: Boolean = false,
    val confirmingDiscard: Boolean = false,
    val savedProductId: Long? = null,
    /** Lo guardado es un combo sin receta todavía: al cerrar se abre su receta. */
    val opensRecipe: Boolean = false,
    val closed: Boolean = false,
    val pricing: PricingContext? = null,
    val suggestTarget: BasisPoints? = null,
    val suggestion: PriceSuggestionDraft? = null,
    val listPrices: List<ProductListPrice> = emptyList(),
    val reference: ReferenceCurrency? = null,
    val importingPhoto: Boolean = false,
    val photoProblem: ImageProblem? = null,
    val extras: ProductExtrasForm = ProductExtrasForm(),
) {
    /** La categoría elegida, o `null` si es sin categoría. */
    val category: Category? get() = categories.firstOrNull { it.id == form.categoryId }

    /** La unidad elegida, o `null` mientras no se leen las unidades. */
    val unit: MeasureUnit? get() = units.firstOrNull { it.id == form.unitId }

    /** Si el producto lleva stock propio: solo entonces se cuentan y se vigilan sus existencias. */
    val tracksStock: Boolean get() = form.stockMode == ProductStockMode.TRACKED
}
