package io.github.yesidrobayo.shopledger.domain.model.phrase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind

/**
 * Lo que el intérprete de frases necesita saber del negocio para leer una frase: los nombres tal
 * como la persona los ve en su idioma, sin nada más que lo que sirve para reconocerlos.
 *
 * Es una foto en memoria: el intérprete no lee la base ni guarda nada. Quien lo llama arma el
 * catálogo con lo que ya tiene a mano, y una frase leída con un catálogo viejo solo propone algo
 * que la persona confirma después.
 *
 * [productCategories] son las categorías de producto, para reconocer la que se dice de un producto
 * nuevo ("en la categoría bebidas"). Vacía, ninguna se reconoce y el producto nuevo se crea sin
 * categoría.
 */
data class PhraseCatalog(
    val products: List<PhraseProduct>,
    val units: List<PhraseUnit>,
    val paymentMethods: List<PhraseName>,
    val suppliers: List<PhraseName>,
    val customers: List<PhraseName>,
    val expenseCategories: List<PhraseName>,
    val productCategories: List<PhraseName> = emptyList(),
)

/**
 * Un producto del catálogo. [conversions] son las otras unidades en que se compra o se vende, con
 * cuántas milésimas de la unidad base trae cada una. Los indicadores filtran lo que una operación
 * admite: en una venta solo lo que se vende, en una producción solo lo que tiene receta.
 *
 * [recipe] son las filas de su receta, lo que lleva una unidad, para mostrar en la tarjeta de una
 * producción en qué se aparta lo dicho de la receta. Vacía si no tiene o no se leyó.
 */
data class PhraseProduct(
    val id: Long,
    val name: String,
    val baseUnitId: Long,
    val conversions: List<PhraseConversion>,
    val forSale: Boolean,
    val forPurchase: Boolean,
    val hasRecipe: Boolean,
    val stockMode: ProductStockMode,
    val recipe: List<PhraseComponent> = emptyList(),
)

/**
 * Una fila de la receta de un producto: una unidad del producto lleva [quantity] milésimas de la
 * unidad base de [productId].
 */
data class PhraseComponent(val productId: Long, val quantity: QuantityMilli)

/**
 * Una unidad más de un producto: una de [unitId] trae [factorMilli] milésimas de su unidad base.
 */
data class PhraseConversion(val unitId: Long, val factorMilli: Long)

/**
 * Una unidad. [systemKey] es la clave estable de las unidades iniciales, que deja reconocer sus
 * sinónimos en cualquier idioma aunque la persona las haya renombrado; una unidad propia no la
 * tiene.
 *
 * [baseAmountMilli] es lo que mide una unidad entera en gramos, mililitros o metros, en milésimas,
 * para las de masa, volumen o longitud: así 500 gramos de un producto que se lleva por kilo se
 * aceptan aunque el producto no tenga esa conversión guardada. Las que se cuentan no lo tienen,
 * porque una caja no tiene tamaño fijo.
 */
data class PhraseUnit(
    val id: Long,
    val systemKey: String?,
    val name: String,
    val symbol: String?,
    val kind: UnitKind,
    val baseAmountMilli: Long?,
)

/**
 * Un nombre que se reconoce en la frase: un método de pago, un proveedor, un cliente, una
 * categoría de gasto o una de producto. [systemKey] es la clave de los que trae la app, con la que
 * se reconocen sus sinónimos; los que creó la persona no la tienen y se reconocen solo por su
 * nombre.
 */
data class PhraseName(val id: Long, val systemKey: String?, val name: String)
