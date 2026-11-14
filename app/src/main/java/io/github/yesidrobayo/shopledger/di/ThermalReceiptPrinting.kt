package io.github.yesidrobayo.shopledger.di

import io.github.yesidrobayo.shopledger.data.print.BluetoothPrinters
import io.github.yesidrobayo.shopledger.data.print.ThermalImagePrinter
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.model.print.SavedPrinter
import io.github.yesidrobayo.shopledger.domain.port.PrinterPort
import io.github.yesidrobayo.shopledger.ui.document.ReceiptAssets
import io.github.yesidrobayo.shopledger.ui.document.ReceiptRenderer
import kotlin.coroutines.CoroutineContext

/**
 * Imprime comprobantes en una térmica guardada: el documento se dibuja a su ancho de papel, en
 * blanco y negro puros, con el mismo motor de la vista previa, y sale por [printerFor] como un
 * solo trabajo ESC/POS con su avance y su corte. Las pruebas cambian [printerFor] por una
 * impresora falsa. Un fallo nunca toca la venta: solo se informa.
 */
class ThermalReceiptPrinting(
    private val printerFor: (String) -> PrinterPort,
    cpuContext: CoroutineContext,
) {
    private val thermal = ThermalImagePrinter(cpuContext)

    /** Imprime [document] en [printer], dibujado por [renderer] con [assets]. */
    suspend fun printReceipt(
        printer: SavedPrinter,
        renderer: ReceiptRenderer,
        document: ReceiptDocument,
        assets: ReceiptAssets,
    ): PrintResult = thermal.printImages(
        printer = printerFor(printer.address),
        items = listOf(document),
        copies = { 1 },
        render = { renderer.renderThermal(it, assets, printer.paper.dots) },
    )

    /** Imprime la página de prueba, con [title] y [body], en [printer]. */
    suspend fun printTest(
        printer: SavedPrinter,
        renderer: ReceiptRenderer,
        title: String,
        body: String,
    ): PrintResult = thermal.printImages(
        printer = printerFor(printer.address),
        items = listOf(title),
        copies = { 1 },
        render = { renderer.renderThermalTest(it, body, printer.paper.dots) },
    )

    companion object {
        /** Sobre las impresoras emparejadas de verdad, por Bluetooth. */
        fun bluetooth(
            printers: BluetoothPrinters,
            cpuContext: CoroutineContext,
        ): ThermalReceiptPrinting = ThermalReceiptPrinting(printers::printer, cpuContext)
    }
}
