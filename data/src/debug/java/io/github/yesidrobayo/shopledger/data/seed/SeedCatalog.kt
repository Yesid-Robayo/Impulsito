package io.github.yesidrobayo.shopledger.data.seed

import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode

/**
 * Las unidades base del negocio inventado, con las cantidades que se venden de una vez. Son las
 * cinco que la estrategia de pruebas mezcla: unidad, kilogramo, gramo, litro y mililitro.
 *
 * Las cantidades van en milésimas de la unidad base: [saleMin] a [saleMax], de [step] en [step].
 */
internal enum class SeedUnit(
    val systemKey: String,
    val saleMin: Long,
    val saleMax: Long,
    val step: Long,
) {
    UNIT("UNIT", 1_000L, 4_000L, 1_000L),
    KILOGRAM("KILOGRAM", 250L, 3_000L, 50L),
    GRAM("GRAM", 50_000L, 500_000L, 10_000L),
    LITER("LITER", 500L, 3_000L, 250L),
    MILLILITER("MILLILITER", 100_000L, 1_000_000L, 50_000L),
    ;

    /** Una cantidad de venta al azar, en milésimas y en múltiplos de [step]. */
    fun saleQuantity(random: SeedRandom): Long =
        random.between(saleMin / step, saleMax / step) * step

    /** La venta media, que da la escala del stock inicial y de cada reposición. */
    val averageSale: Long get() = (saleMin + saleMax) / 2 / step * step

    /** [quantity] redondeada hacia arriba a un múltiplo de [step], y al menos uno. */
    fun roundUp(quantity: Long): Long = maxOf(step, (quantity + step - 1) / step * step)
}

/** Un producto del catálogo inventado, con lo que el generador necesita recordar de él. */
internal class SeedProduct(
    val id: Long,
    val name: String,
    val mode: ProductStockMode,
    val unit: SeedUnit,
    val unitId: Long,
    var salePrice: Long,
    /** Lo que cuesta comprar una unidad base al empezar la historia. Sube con el tiempo. */
    val costBasis: Long,
    val fixedUnitCost: Long,
    var replacementCost: Long,
    val minStockMilli: Long,
    /** Si también tiene stock en la segunda ubicación. */
    val dual: Boolean,
    /** Si nace con inventario inicial. Uno sin él queda sin stock toda la historia. */
    val stocked: Boolean,
    /** Si se repone con compras. Uno sin reposición termina con stock negativo. */
    val restocked: Boolean,
    val components: List<SeedComponent>,
) {
    val tracked: Boolean get() = mode == ProductStockMode.TRACKED
}

/** Un componente de un combo: el producto y cuánto lleva una unidad del combo, en milésimas. */
internal class SeedComponent(val product: SeedProduct, val quantityMilli: Long)

/** Un cliente inventado, con su peso para elegirlo: unos pocos compran mucho más. */
internal class SeedCustomer(val id: Long, val weight: Long)

/** Un proveedor inventado y los días de plazo que da. */
internal class SeedSupplier(val id: Long, val termsDays: Int)

/** Un método de pago inicial, ya ligado o no a una cuenta de dinero. */
internal class SeedPaymentMethod(
    val id: Long,
    val kind: String,
    val name: String,
    val accountId: Long?,
    val feeBp: Long,
    val settlementDays: Int,
)

/**
 * Todo lo que el catálogo inventado dejó en la base y la historia necesita: ids, pesos y las
 * cuentas, ubicaciones y métodos ya configurados.
 */
internal class SeedCatalog(
    val products: List<SeedProduct>,
    val customers: List<SeedCustomer>,
    val suppliers: List<SeedSupplier>,
    val expenseCategoryIds: List<Long>,
    val methods: Map<String, SeedPaymentMethod>,
    val cashAccountId: Long,
    val bankAccountId: Long,
    val mainLocationId: Long,
    val secondLocationId: Long,
    val priceListId: Long,
    /** El producto que concentra unos 50.000 movimientos, si el perfil lo pide. */
    val heavyProduct: SeedProduct?,
    /** Peso de venta de cada producto vendible, por id. */
    val saleWeights: Map<Long, Long>,
    /** Las palabras del idioma pedido, para las notas que se inventan durante la historia. */
    val vocabulary: SeedVocabulary,
)
