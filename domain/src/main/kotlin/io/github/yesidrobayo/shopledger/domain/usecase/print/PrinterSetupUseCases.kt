package io.github.yesidrobayo.shopledger.domain.usecase.print

import io.github.yesidrobayo.shopledger.domain.model.print.PaperWidth
import io.github.yesidrobayo.shopledger.domain.model.print.PrinterSetup
import io.github.yesidrobayo.shopledger.domain.model.print.SavedPrinter
import io.github.yesidrobayo.shopledger.domain.port.PrinterSetupStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Un cambio en las impresoras guardadas. */
sealed interface PrinterSetupChange {
    /** Guarda [printer], nueva o con otro papel. */
    data class Add(val printer: SavedPrinter) : PrinterSetupChange

    data class Remove(val address: String) : PrinterSetupChange

    data class MakeDefault(val address: String) : PrinterSetupChange

    data class ChangePaper(val address: String, val paper: PaperWidth) : PrinterSetupChange
}

/** Resultado de [UpdatePrinterSetupUseCase]. */
sealed interface UpdatePrinterSetupResult {
    /** Quedó guardado así. */
    data class Saved(val setup: PrinterSetup) : UpdatePrinterSetupResult

    /** Ya hay [PrinterSetup.MAX_PRINTERS] impresoras: una nueva no entra. No cambió nada. */
    data object Full : UpdatePrinterSetupResult

    /** El archivo no se pudo escribir. No cambió nada. */
    data object Failed : UpdatePrinterSetupResult
}

/** Las impresoras guardadas, de nuevo cada vez que cambian. */
class ObservePrinterSetupUseCase(private val store: PrinterSetupStore) {
    operator fun invoke(): Flow<PrinterSetup> = store.observe()
}

/**
 * Cambia las impresoras guardadas. No es un dato del negocio: no pasa por la base, no cuenta como
 * cambio sin respaldar y no viaja en una copia. Los cambios van de a uno, para que dos toques
 * seguidos no se pisen.
 */
class UpdatePrinterSetupUseCase(private val store: PrinterSetupStore) {
    private val writing = Mutex()

    suspend operator fun invoke(change: PrinterSetupChange): UpdatePrinterSetupResult =
        writing.withLock {
            val current = store.observe().first()
            val updated = when (change) {
                is PrinterSetupChange.Add -> {
                    val known = current.printers.any { it.address == change.printer.address }
                    if (!known && current.isFull) return@withLock UpdatePrinterSetupResult.Full
                    current.with(change.printer)
                }

                is PrinterSetupChange.Remove -> current.without(change.address)

                is PrinterSetupChange.MakeDefault -> current.withDefault(change.address)

                is PrinterSetupChange.ChangePaper ->
                    current.withPaper(change.address, change.paper)
            }
            if (store.save(updated)) {
                UpdatePrinterSetupResult.Saved(updated)
            } else {
                UpdatePrinterSetupResult.Failed
            }
        }
}
