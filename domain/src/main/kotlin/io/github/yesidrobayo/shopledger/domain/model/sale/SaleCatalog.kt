package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
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
 * - [today] y [weekStartDay]: la fecha de negocio de hoy y el primer día de la semana, para elegir
 *   una fecha anterior. Faltan si el teléfono no conoce la zona del negocio.
 * - [cashRounding]: el paso al que se redondea un pago en efectivo, en unidad menor. Cero es sin
 *   redondeo.
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
)
