package io.github.yesidrobayo.shopledger.domain.rule.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/** De dónde sale el precio de un producto en una lista. */
enum class ListPriceOrigin {
    /** El precio de venta del producto: la lista inicial, o una fija sin precio propio. */
    SALE_PRICE,

    /** El precio que la persona escribió para el producto en esa lista fija. */
    OWN,

    /** Calculado desde otra lista con el ajuste de una relativa. */
    CALCULATED,
}

/** El precio de un producto en una lista. */
sealed interface ListPriceResult {
    data class Priced(val price: Money, val origin: ListPriceOrigin) : ListPriceResult

    /** El ajuste lleva el precio sobre el tope de dinero por unidad: no se puede vender así. */
    data object AboveLimit : ListPriceResult

    /**
     * La lista, o alguna de su cadena, no existe, o la cadena vuelve sobre sí misma. La base no lo
     * permite: solo pasa con datos dañados, y entonces no se inventa un precio.
     */
    data object Unresolvable : ListPriceResult
}

/**
 * El precio de un producto en una lista de precios.
 *
 * | Lista | Precio |
 * |---|---|
 * | Inicial | El precio de venta del producto |
 * | Fija | Su precio propio; sin él, el precio de venta |
 * | Relativa | `roundDiv(base × (10000 + ajuste), 10000)`, con `base` el precio en su lista base |
 *
 * La base de una relativa puede ser otra relativa: la cadena se recorre hasta una fija. Con un
 * precio de venta de `10000`, una relativa de −15,00 % da `8500`, y otra relativa a esa con
 * +5,00 % da `8925`. Una relativa no se redondea a un paso: el precio queda congelado en la línea tal
 * como se calculó, y la persona lo puede cambiar solo para esa línea.
 */
object ListPriceRules {
    /**
     * El precio de [base] con [adjustment], o `null` si pasa del tope de dinero por unidad. Con el
     * ajuste mínimo, −100 %, el precio es cero.
     */
    fun relativePrice(base: Money, adjustment: BasisPoints): Money? =
        mulDivRound(base.minor, BASIS + adjustment.bp, BASIS, Limit.UNIT_MONEY)
            .valueOrNull()
            ?.let(::Money)

    /**
     * El precio en la lista [listId] de un producto con precio de venta [salePrice] y precios
     * propios [ownPrices], por id de lista. [lists] son todas las listas, por id, también las
     * archivadas: una relativa vigente puede partir de una archivada.
     */
    fun resolve(
        listId: Long,
        lists: Map<Long, PriceList>,
        salePrice: Money,
        ownPrices: Map<Long, Money>,
    ): ListPriceResult = resolveFrom(listId, lists, salePrice, ownPrices, mutableSetOf())

    private fun resolveFrom(
        listId: Long,
        lists: Map<Long, PriceList>,
        salePrice: Money,
        ownPrices: Map<Long, Money>,
        visited: MutableSet<Long>,
    ): ListPriceResult {
        val list = lists[listId]
        // Una lista que ya se visitó cierra un ciclo: la base no lo admite, pero no se recorre sin fin.
        if (list == null || !visited.add(listId)) return ListPriceResult.Unresolvable
        if (list.isDefault) return ListPriceResult.Priced(salePrice, ListPriceOrigin.SALE_PRICE)
        return when (val pricing = list.pricing) {
            PriceListPricing.Fixed -> {
                val own = ownPrices[listId]
                if (own == null) {
                    ListPriceResult.Priced(salePrice, ListPriceOrigin.SALE_PRICE)
                } else {
                    ListPriceResult.Priced(own, ListPriceOrigin.OWN)
                }
            }

            is PriceListPricing.Relative ->
                when (
                    val base = resolveFrom(
                        pricing.baseId,
                        lists,
                        salePrice,
                        ownPrices,
                        visited,
                    )
                ) {
                    is ListPriceResult.Priced -> relativePrice(base.price, pricing.adjustment)
                        ?.let { ListPriceResult.Priced(it, ListPriceOrigin.CALCULATED) }
                        ?: ListPriceResult.AboveLimit

                    ListPriceResult.AboveLimit, ListPriceResult.Unresolvable -> base
                }
        }
    }

    private const val BASIS = 10_000L
}
