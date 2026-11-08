package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Los productos de una lista, con todas las unidades por id para escribir sus cantidades. Las
 * unidades van todas, también las archivadas: un producto puede seguir en una que ya se archivó.
 */
data class ProductCatalog(val products: List<Product>, val units: Map<Long, MeasureUnit>)
