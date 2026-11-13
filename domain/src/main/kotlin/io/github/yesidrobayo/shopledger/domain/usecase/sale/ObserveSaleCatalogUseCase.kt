package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleFrequencySource
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.FrequentProducts
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * El catálogo de la venta: los productos que se venden, los más vendidos primero, los archivados
 * que la búsqueda ofrece reactivar, las unidades y los métodos con que se cobra. Vuelve a emitir
 * con cada cambio confirmado de cualquiera de ellos y cuando cambia la zona del negocio. No escribe
 * nada.
 *
 * - Los más vendidos cuentan los últimos treinta días según la fecha de negocio de hoy. Si el
 *   teléfono no conoce la zona del negocio no hay fecha de hoy: el orden es alfabético, y la venta
 *   muestra el problema al confirmar.
 * - El modo de impuesto y las tasas van con el catálogo: la venta muestra el total que cobrará.
 * - Las conversiones de venta de cada producto van con el catálogo: la línea elige su unidad.
 * - Un método a crédito no se ofrece en la venta nueva, porque exige un cliente: va aparte, en
 *   `creditMethods`, para la pantalla de cobro, que elige el cliente. Los archivados no se leen.
 * - El paso del redondeo del efectivo va con el catálogo, de [cashRounding]: la venta muestra lo
 *   que cobra en efectivo ya redondeado. Sin él, no se redondea.
 * - Si el cobro ofrece propina, de [tipsEnabled]. Sin él, no la ofrece.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveSaleCatalogUseCase(
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val frequency: SaleFrequencySource,
    private val methods: PaymentMethodRepository,
    private val calendar: BusinessCalendarSource,
    private val taxSettings: TaxSettingsRepository,
    private val taxRates: TaxRateRepository,
    private val clock: Clock,
    private val cashRounding: () -> Flow<Money> = { flowOf(Money.ZERO) },
    private val productUnits: ProductUnitRepository? = null,
    private val tipsEnabled: () -> Flow<Boolean> = { flowOf(false) },
) {
    operator fun invoke(): Flow<SaleCatalog> = calendar.observe().flatMapLatest { result ->
        val ready = result as? BusinessCalendarResult.Ready
        val today = ready?.calendar?.businessDateOf(clock.now())
        val counts = if (today == null) {
            flowOf(emptyMap())
        } else {
            frequency.observeLineCounts(FrequentProducts.since(today))
        }
        val catalog = combine(
            products.observe(ArchiveFilter.ACTIVE),
            products.observe(ArchiveFilter.ARCHIVED),
            units.observeAll(),
            counts,
            methods.observe(ArchiveFilter.ACTIVE),
        ) { list, archived, allUnits, lineCounts, allMethods ->
            SaleCatalog(
                products = list,
                frequent = FrequentProducts.rank(list, lineCounts),
                archived = archived,
                units = allUnits.associateBy { it.id },
                methods = allMethods.filter { it.details.kind != PaymentMethodKind.ON_ACCOUNT },
                creditMethods =
                    allMethods.filter { it.details.kind == PaymentMethodKind.ON_ACCOUNT },
                taxes = NO_TAXES,
                taxRates = emptyMap(),
                today = today,
                weekStartDay = ready?.calendar?.weekStartDay,
            )
        }
        // Las tasas archivadas también cuentan: un producto conserva la suya.
        val rates = combine(
            taxRates.observe(ArchiveFilter.ACTIVE),
            taxRates.observe(ArchiveFilter.ARCHIVED),
        ) { active, archived -> (active + archived).associate { it.id to it.details.rate } }
        // Solo las conversiones que se ofrecen al vender: las de compra no se eligen aquí.
        val conversions = productUnits?.observeAll()?.map { all ->
            all.filter { it.details.forSale }
                .groupBy { it.productId }
                .mapValues { (_, list) -> list.associate { it.details.unitId to it.details } }
        } ?: flowOf(emptyMap())
        combine(catalog, taxSettings.observe(), rates, cashRounding(), conversions) {
                sale,
                taxes,
                byId,
                step,
                byProduct,
            ->
            sale.copy(taxes = taxes, taxRates = byId, cashRounding = step, conversions = byProduct)
        }.combine(tipsEnabled()) { sale, tips -> sale.copy(tipsEnabled = tips) }
    }
}

/** El catálogo antes de leer los impuestos, que se suman al final. */
private val NO_TAXES = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false)
