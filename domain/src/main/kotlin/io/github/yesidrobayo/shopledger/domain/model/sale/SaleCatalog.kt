package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.rule.pricing.SalePriceList
import io.github.yesidrobayo.shopledger.domain.rule.stock.LocationRules
import kotlinx.datetime.DayOfWeek

/**
 * Lo que la pantalla de venta necesita del negocio para armar una venta.
 *
 * - [products]: los productos sin archivar, por nombre normalizado, donde busca la venta.
 * - [frequent]: los que se ofrecen sin buscar, en el orden de `Más vendidos`.
 * - [archived]: los archivados, por nombre: no se venden, pero la búsqueda ofrece reactivarlos.
 * - [units]: todas las unidades, también las archivadas, por id.
 * - [methods]: los métodos de pago con que se puede cobrar ahora, en el orden de la persona. El
 *   primero es el que se elige solo.
 * - [taxes] y [taxRates]: el modo de impuesto del negocio y cada tasa por id, también las
 *   archivadas, que un producto conserva. Con ellos la venta muestra el total que cobrará.
 * - [conversions]: por producto, las unidades en que se vende además de la base, con su factor y
 *   su precio, por id de unidad. Solo las que se ofrecen al vender.
 * - [today] y [weekStartDay]: la fecha de negocio de hoy y el primer día de la semana, para elegir
 *   una fecha anterior. Faltan si el teléfono no conoce la zona del negocio.
 * - [cashRounding]: el paso al que se redondea un pago en efectivo, en unidad menor. Cero es sin
 *   redondeo.
 * - [tipsEnabled]: si el cobro ofrece propina. Apagadas, no aparece.
 * - [loyalty]: el programa de puntos. Apagado, el cobro no menciona puntos.
 * - [priceLists]: todas las listas de precios, también las archivadas, que una relativa puede tener
 *   de base; y [ownPrices], los precios propios de cada producto, por producto y lista. Con ellos la
 *   venta muestra el precio de la lista elegida, el mismo que guarda al confirmar.
 * - [comboAvailability]: cuántos combos enteros alcanzan con el stock de sus componentes, por id
 *   del combo; `null` si ningún componente lleva stock. Un combo no tiene stock propio, así que la
 *   tarjeta muestra esto, y avisa si la venta pide más, sin bloquearla.
 * - [modifiers]: los extras que se ofrecen al vender cada producto, por id del producto y en su
 *   orden. Pueden nombrar un producto archivado: la venta solo ofrece los vigentes.
 */
data class SaleCatalog(
    val products: List<Product>,
    val frequent: List<Product>,
    val archived: List<Product>,
    val units: Map<Long, MeasureUnit>,
    val methods: List<PaymentMethodRecord>,
    val taxes: TaxSettings,
    val taxRates: Map<Long, BasisPoints>,
    val today: BusinessDate?,
    val weekStartDay: DayOfWeek?,
    val cashRounding: Money = Money.ZERO,
    val conversions: Map<Long, Map<Long, ConversionDetails>> = emptyMap(),
    val tipsEnabled: Boolean = false,
    val creditMethods: List<PaymentMethodRecord> = emptyList(),
    val loyalty: LoyaltySettings = LoyaltySettings.OFF,
    val priceLists: List<PriceList> = emptyList(),
    val ownPrices: Map<Long, Map<Long, Money>> = emptyMap(),
    val comboAvailability: Map<Long, Long?> = emptyMap(),
    val modifiers: Map<Long, List<Long>> = emptyMap(),
    val locations: List<Location> = emptyList(),
    val lastSaleLocationId: Long? = null,
) {
    /**
     * Las ubicaciones que se ofrecen al vender: las vigentes, en el orden en que se crearon, o
     * ninguna mientras haya una sola. Entonces no se pregunta dónde y todo va a la inicial.
     */
    val saleLocations: List<Location>
        get() = locations.takeIf { LocationRules.showsLocations(it.size) }.orEmpty()

    /**
     * Dónde se vende si la persona no elige: donde se hizo la última venta, si esa ubicación sigue
     * vigente, o la inicial. `null` solo sin ubicaciones leídas, como en una vista previa.
     */
    val defaultLocationId: Long?
        get() = locations.firstOrNull { it.id == lastSaleLocationId }?.id
            ?: locations.firstOrNull { it.isInitial }?.id
            ?: locations.firstOrNull()?.id

    /**
     * Dónde se vende con [chosen] elegida: ella, si se sigue ofreciendo, o [defaultLocationId]. Una
     * elegida que se archivó, o que dejó de ofrecerse porque quedó una sola, no se usa.
     */
    fun saleLocationId(chosen: Long?): Long? =
        chosen?.takeIf { id -> saleLocations.any { it.id == id } } ?: defaultLocationId

    /** Las listas que se ofrecen al vender: las vigentes, la inicial primero. */
    val activePriceLists: List<PriceList> get() = priceLists.filterNot { it.isArchived }

    /**
     * Con qué se resuelven los precios de la lista [listId], o `null` con la inicial, con ninguna o
     * con una que ya no se ofrece: entonces vale el precio de venta.
     */
    fun salePriceList(listId: Long?): SalePriceList? {
        val list = priceLists.firstOrNull { it.id == listId }
        if (list == null || list.isDefault || list.isArchived) return null
        return SalePriceList(list.id, priceLists.associateBy { it.id }, ownPrices)
    }

    /**
     * Los métodos con que se cobra en la pantalla de cobro: los de la venta y, aparte, los métodos
     * a crédito, que exigen un cliente y por eso no se ofrecen en la venta nueva.
     */
    val payable: List<PaymentMethodRecord> get() = methods + creditMethods
}
