package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.product.SAMPLE_UNIT
import io.github.yesidrobayo.shopledger.ui.product.sampleProduct
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

// Datos inventados para las pruebas y capturas de la venta: nombres sin marcas, montos cualquiera.

internal val SAMPLE_KILO = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)

private val AT = Instant.fromEpochMilliseconds(1_700_000_000_000L)

/** Productos de una tienda de barrio, con un nombre de 80 caracteres y uno sin precio. */
internal fun saleProducts(): List<Product> = listOf(
    sampleProduct(1L, "Aceite 900 ml", 12_500L, 9_800L, 3_000L),
    sampleProduct(2L, "Arroz 1 kg", 4_200L, 3_000L, 23_000L),
    sampleProduct(3L, "Huevos x 30", 16_800L, 15_000L, 11_000L),
    sampleProduct(4L, "Queso fresco", 22_000L, 15_000L, 6_000L).let {
        it.copy(details = it.details.copy(baseUnitId = SAMPLE_KILO.id))
    },
    sampleProduct(5L, "Azúcar x 2", 3_800L, 2_500L, 3_000L),
    sampleProduct(
        6L,
        "Café molido de altura en bolsa resellable con válvula, tostión media, 500 gramos",
        14_900L,
        9_000L,
        9_000L,
    ),
    sampleProduct(7L, "Corte de cabello", 15_000L, 0L, 0L, 0L, ProductStockMode.UNTRACKED, 4_000L),
    sampleProduct(8L, "Muestra gratis", 0L, 0L, 40_000L),
)

internal fun saleMethod(id: Long, key: String, qr: Boolean = false) = PaymentMethodRecord(
    id = id,
    systemKey = key,
    details = PaymentMethodDetails(
        name = null,
        normalizedName = null,
        kind = PaymentMethodKind.valueOf(key),
        feeRate = BasisPoints.ZERO,
        feeFixed = Money.ZERO,
        settlementDays = 0,
        priceAdjustment = BasisPoints.ZERO,
        moneyAccountId = null,
        instructionsText = if (qr) "Datos de cobro inventados" else null,
        instructionsMediaId = null,
    ),
    sortOrder = id,
    archivedAt = null,
    createdAt = AT,
    updatedAt = AT,
)

internal val SALE_TODAY = BusinessDate(LocalDate(2026, 11, 2))

internal fun saleCatalog(
    products: List<Product> = saleProducts(),
    archived: List<Product> = emptyList(),
) = SaleCatalog(
    products = products.sortedBy { it.details.normalizedName },
    frequent = products,
    archived = archived,
    units = listOf(SAMPLE_UNIT, SAMPLE_KILO).associateBy { it.id },
    methods = listOf(
        saleMethod(1L, "CASH"),
        saleMethod(2L, "CARD"),
        saleMethod(3L, "TRANSFER", qr = true),
        saleMethod(4L, "WALLET", qr = true),
        saleMethod(6L, "OTHER"),
    ),
    taxes = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
    taxRates = emptyMap(),
    today = SALE_TODAY,
    weekStartDay = DayOfWeek.MONDAY,
)

private val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

/** Un ViewModel de venta sobre un catálogo fijo, que anota cada venta que confirma. */
internal fun saleViewModel(
    catalog: SaleCatalog = saleCatalog(),
    requests: MutableList<SaleRequest> = mutableListOf(),
    result: RegisterSaleResult = RegisterSaleResult.Registered(1_204L),
): NewSaleViewModel = NewSaleViewModel(
    catalogSource = { MutableStateFlow(catalog) },
    registerSale = {
        requests += it
        result
    },
    reactivate = { true },
    savedState = SavedStateHandle(),
    numbers = SaleNumberFormat(SYMBOLS, 0, SYMBOLS, SYMBOLS),
    computation = Dispatchers.Default,
)
