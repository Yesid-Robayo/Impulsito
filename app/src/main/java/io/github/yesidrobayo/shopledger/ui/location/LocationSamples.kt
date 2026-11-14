package io.github.yesidrobayo.shopledger.ui.location

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationSummary
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationsOverview
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockTransfer
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransfer
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransferDetail
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransferDetailLine
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransferSummary
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidShortfall
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.stock.inventoryItemOf
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * Datos inventados para las vistas previas y las pruebas de la pantalla: la ubicación inicial, un
 * puesto con un nombre de 40 caracteres, un depósito archivado y dos transferencias, una anulada.
 */
internal object LocationSamples {
    private val CREATED = Instant.fromEpochMilliseconds(0L)

    val UNIT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
    val KILOGRAM = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)

    val MAIN = location(1L, "MAIN", null, LocationKind.STORE)
    val STALL = location(2L, null, "Puesto de feria del parque principal 40", LocationKind.STALL)
    val WAREHOUSE = location(3L, null, PreviewSamples.LOCATION_SECOND, LocationKind.WAREHOUSE)
        .copy(archivedAt = CREATED)

    val OVERVIEW = LocationsOverview(
        active = listOf(LocationSummary(MAIN, 48), LocationSummary(STALL, 1)),
        archived = listOf(WAREHOUSE),
    )

    val SINGLE = LocationsOverview(
        active = listOf(LocationSummary(MAIN, 12)),
        archived = emptyList(),
    )

    val RICE = product(7L, PreviewSamples.SHORT_NAME, UNIT.id, onHand = 8_000L)
    val CHEESE = product(8L, PreviewSamples.LONG_NAME, KILOGRAM.id, onHand = -2_350L)

    val ITEMS = listOf(
        inventoryItemOf(RICE, mapOf(UNIT.id to UNIT)),
        inventoryItemOf(CHEESE, mapOf(KILOGRAM.id to KILOGRAM)),
    )

    val DAY: BusinessDate = BusinessDate(LocalDate(2026, 10, 28))

    val ACTIVE_TRANSFER = transfer(10L, voided = false)
    val VOIDED_TRANSFER = transfer(11L, voided = true)

    val TRANSFER_DAYS = listOf(
        TransferDay(
            DAY,
            listOf(
                StockTransferSummary(ACTIVE_TRANSFER, 3),
                StockTransferSummary(VOIDED_TRANSFER, 1),
            ),
        ),
    )

    val DETAIL = StockTransferDetail(
        transfer = ACTIVE_TRANSFER,
        lines = listOf(
            StockTransferDetailLine(RICE.id, RICE, UNIT, QuantityMilli(4_000L)),
            StockTransferDetailLine(CHEESE.id, CHEESE, KILOGRAM, QuantityMilli(1_500L)),
        ),
        voidShortfalls = listOf(VoidShortfall(RICE, UNIT, QuantityMilli(-3_000L))),
    )

    fun ready(tab: LocationsTab = LocationsTab.LOCATIONS) = LocationsUiState(
        tab = tab,
        content = LocationsContent.Ready(OVERVIEW),
        transfers = TransfersContent.Ready(TRANSFER_DAYS, hasOlder = false, loadingOlder = false),
    )

    private fun location(id: Long, key: String?, name: String?, kind: LocationKind) = Location(
        id = id,
        systemKey = key,
        details = LocationDetails(name, name?.lowercase(), kind),
        archivedAt = null,
        createdAt = CREATED,
        updatedAt = CREATED,
    )

    private fun transfer(id: Long, voided: Boolean) = StockTransfer(
        id = id,
        content = NewStockTransfer(
            fromLocationId = MAIN.id,
            toLocationId = STALL.id,
            note = null,
            occurredAt = CREATED,
            businessDate = DAY,
            createdAt = CREATED,
        ),
        voiding = if (voided) Voiding(CREATED, VoidReason.ENTRY_ERROR, null) else null,
    )

    fun product(id: Long, name: String, unitId: Long, onHand: Long) = Product(
        id = id,
        details = ProductDetails(
            name, name.lowercase(), null, null, unitId, ProductStockMode.TRACKED, false,
            Money.ZERO, QuantityMilli(5_000L), null, null, null, null, null, null, null, null,
            null, null, null, null,
        ),
        salePrice = Money(5_000L),
        initialCost = Money.ZERO,
        averageCost = Money(3_800L),
        stockValue = Money(91_200L),
        onHand = QuantityMilli(onHand),
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = null,
        createdAt = CREATED,
        updatedAt = CREATED,
    )
}
