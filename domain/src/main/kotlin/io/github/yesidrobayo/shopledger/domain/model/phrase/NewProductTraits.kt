package io.github.yesidrobayo.shopledger.domain.model.phrase

import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage

/**
 * Lo que la frase dijo de un producto que todavía no existe, para crearlo ya con eso: "esto es un
 * insumo", "es para vender", "es un servicio", "en la categoría materias primas". Nada de esto se
 * guarda solo: viaja con el producto nuevo de la tarjeta y se usa al tocar `Crear «…»` o al
 * registrar un producto nuevo.
 *
 * - [usage] es para qué es, o `null` si no se dijo: entonces el producto se vende, como siempre.
 * - [stockMode] es [ProductStockMode.UNTRACKED] si se dijo que es un servicio, y `null` si no.
 * - [categoryId] es la categoría de producto dicha, si se encontró una sola en el catálogo.
 * - [newCategory] es la categoría dicha tal como se dijo, con mayúscula inicial, si no se encontró
 *   una sola en el catálogo: se crea con el producto, en su misma transacción, y si para entonces
 *   ya hay una con ese nombre, se usa esa.
 */
data class NewProductTraits(
    val usage: ProductUsage? = null,
    val stockMode: ProductStockMode? = null,
    val categoryId: Long? = null,
    val newCategory: String? = null,
) {
    /** Estos rasgos con lo que dice [other] encima: lo que [other] no dice, queda como estaba. */
    fun with(other: NewProductTraits): NewProductTraits {
        val saysCategory = other.categoryId != null || other.newCategory != null
        return NewProductTraits(
            usage = other.usage ?: usage,
            stockMode = other.stockMode ?: stockMode,
            categoryId = if (saysCategory) other.categoryId else categoryId,
            newCategory = if (saysCategory) other.newCategory else newCategory,
        )
    }

    companion object {
        /** Sin nada dicho. */
        val NONE: NewProductTraits = NewProductTraits()
    }
}
