package io.github.yesidrobayo.shopledger.di

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.print.BluetoothPrinters
import io.github.yesidrobayo.shopledger.data.print.ThermalImagePrinter
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.label.LabelTemplate
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrinter
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrintersResult
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PrinterPort
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelItem
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.GenerateOwnBarcodesResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.GenerateOwnBarcodesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ListRepricedProductsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RepricedProductsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.ui.label.LabelCatalog
import io.github.yesidrobayo.shopledger.ui.label.LabelPrinting
import io.github.yesidrobayo.shopledger.ui.label.LabelRenderer
import io.github.yesidrobayo.shopledger.ui.label.LabelSize
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow

/** Lo que las etiquetas leen y escriben, con la base abierta y los casos de uso de siempre. */
class PortsLabelCatalog(private val ports: DataPorts, clock: Clock) : LabelCatalog {
    private val listRepriced =
        ListRepricedProductsUseCase(ports.transactions, ports.systemRows, ports.priceHistory)
    private val generate =
        GenerateOwnBarcodesUseCase(ports.transactions, ports.products, ports.productUnits, clock)

    override fun products(): Flow<List<Product>> = ports.products.observe(ArchiveFilter.ACTIVE)

    override fun categories(): Flow<List<Category>> = ports.categories.observeAll()

    override fun calendar(): Flow<BusinessCalendarResult> =
        BusinessCalendarSource(ports.settings).observe()

    override suspend fun repriced(from: BusinessDate): RepricedProductsResult = listRepriced(from)

    override suspend fun generateCodes(productIds: Collection<Long>): GenerateOwnBarcodesResult =
        generate(productIds)
}

/**
 * Imprime etiquetas en una térmica: cada una se dibuja con [renderer], la misma imagen de la vista
 * previa, y sale por [printerFor] como un solo trabajo ESC/POS. Las pruebas cambian [printerFor]
 * por una impresora falsa.
 */
class ThermalLabelPrinting(
    private val listPaired: () -> PairedPrintersResult,
    private val printerFor: (PairedPrinter) -> PrinterPort,
    private val renderer: LabelRenderer,
    cpuContext: CoroutineContext,
) : LabelPrinting {
    private val thermal = ThermalImagePrinter(cpuContext)

    override fun paired(): PairedPrintersResult = listPaired()

    override suspend fun printLabels(
        printer: PairedPrinter,
        labels: List<LabelItem>,
        template: LabelTemplate,
        size: LabelSize,
    ): PrintResult = thermal.printImages(
        printer = printerFor(printer),
        items = labels,
        copies = { it.copies },
        render = { renderer.render(it, template, size) },
    )

    companion object {
        /** Sobre las impresoras emparejadas de verdad, por Bluetooth. */
        fun bluetooth(
            printers: BluetoothPrinters,
            renderer: LabelRenderer,
            cpuContext: CoroutineContext,
        ): ThermalLabelPrinting = ThermalLabelPrinting(
            listPaired = printers::paired,
            printerFor = { printers.printer(it.address) },
            renderer = renderer,
            cpuContext = cpuContext,
        )
    }
}
