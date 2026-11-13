package io.github.yesidrobayo.shopledger.domain.rule.pricing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList

/**
 * El precio de lista de un producto para una línea de venta, en su unidad base: [price] nulo es un
 * precio que no se puede calcular, como uno que el ajuste lleva sobre el tope, y la línea solo se
 * vende con un precio escrito. [isDefault] dice si es la lista inicial: solo con ella vale el precio
 * propio de una conversión.
 */
data class LinePriceList(val price: Money?, val isDefault: Boolean) {
    companion object {
        /** El de siempre: el precio de venta del producto, en la lista inicial. */
        fun default(product: Product): LinePriceList =
            LinePriceList(product.salePrice, isDefault = true)
    }
}

/**
 * La lista de precios con que se arma una venta: la elegida, [listId], con todas las listas, por
 * id, y los precios propios de cada producto, por producto y lista. Es lo mismo que lee la venta al
 * confirmar, así que el precio que se ve es el que se guarda.
 */
data class SalePriceList(
    val listId: Long,
    val lists: Map<Long, PriceList>,
    val ownPrices: Map<Long, Map<Long, Money>>,
) {
    /** Si la elegida es la inicial, o no se encuentra y entonces nada se resuelve con ella. */
    val isDefault: Boolean get() = lists[listId]?.isDefault == true

    /** El precio de [product] en la lista elegida, en su unidad base. */
    fun priceOf(product: Product): LinePriceList {
        val result = ListPriceRules.resolve(
            listId,
            lists,
            product.salePrice,
            ownPrices[product.id].orEmpty(),
        )
        return LinePriceList((result as? ListPriceResult.Priced)?.price, isDefault)
    }
}
