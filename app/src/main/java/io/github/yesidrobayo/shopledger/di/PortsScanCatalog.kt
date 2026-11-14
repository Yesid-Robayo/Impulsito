package io.github.yesidrobayo.shopledger.di

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.CodeMatch
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.AssignBarcodeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.AssignBarcodeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.FindProductByCodeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.ui.scanner.ScanCandidate
import io.github.yesidrobayo.shopledger.ui.scanner.ScanCatalog
import kotlinx.coroutines.flow.first

/** El catálogo del escáner sobre la base abierta, con los casos de uso de siempre. */
class PortsScanCatalog(private val ports: DataPorts, clock: Clock) : ScanCatalog {
    private val findCode =
        FindProductByCodeUseCase(ports.transactions, ports.products, ports.productUnits)
    private val assignCode =
        AssignBarcodeUseCase(ports.transactions, ports.products, ports.productUnits, clock)
    private val reactivateProduct =
        ReactivateProductUseCase(
            ports.transactions,
            ports.products,
            ports.lowStockAlerts,
            BusinessCalendarSource(ports.settings),
            clock,
            ports.productVariants,
        )

    override suspend fun find(code: String): CodeMatch = findCode(code)

    override suspend fun withoutCode(): List<ScanCandidate> =
        ports.products.observe(ArchiveFilter.ACTIVE).first()
            .filter { it.details.barcode == null }
            .map { ScanCandidate(it.id, it.details.name, it.details.normalizedName) }

    override suspend fun assign(productId: Long, code: String): AssignBarcodeResult =
        assignCode(productId, code)

    override suspend fun reactivate(productId: Long): ReactivateProductResult =
        reactivateProduct(productId)
}
