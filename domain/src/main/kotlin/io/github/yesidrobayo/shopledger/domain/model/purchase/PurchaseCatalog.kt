package io.github.yesidrobayo.shopledger.domain.model.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import kotlinx.datetime.DayOfWeek

/**
 * Lo que la pantalla de compra necesita del negocio para armar una compra.
 *
 * - [products]: los productos sin archivar que se pueden comprar, por nombre normalizado: los que
 *   llevan stock propio.
 * - [archived]: los archivados que se podrían comprar: la búsqueda ofrece reactivarlos.
 * - [units]: todas las unidades, también las archivadas, por id.
 * - [suppliers]: los proveedores sin archivar, por nombre: los que se eligen en una compra nueva.
 * - [taxes] y [taxRates]: el modo de impuesto del negocio y cada tasa por id, también las
 *   archivadas, que un producto conserva. Con ellos la compra muestra el total y el costo que deja.
 * - [today] y [weekStartDay]: la fecha de negocio de hoy y el primer día de la semana, para elegir
 *   una fecha anterior. Faltan si el teléfono no conoce la zona del negocio.
 * - [conversions]: por producto, las unidades en que se compra además de la base, con su factor,
 *   por id de unidad. Solo las que se ofrecen al comprar.
 * - [lots]: por producto, los lotes con código que no se cerraron, en el orden en que entraron:
 *   escribir el código de uno de ellos suma a ese lote en vez de crear otro.
 */
data class PurchaseCatalog(
    val products: List<Product>,
    val archived: List<Product>,
    val units: Map<Long, MeasureUnit>,
    val suppliers: List<Supplier>,
    val taxes: TaxSettings,
    val taxRates: Map<Long, TaxRateDetails>,
    val today: BusinessDate?,
    val weekStartDay: DayOfWeek?,
    val conversions: Map<Long, Map<Long, ConversionDetails>> = emptyMap(),
    val lots: Map<Long, List<StockLot>> = emptyMap(),
)
