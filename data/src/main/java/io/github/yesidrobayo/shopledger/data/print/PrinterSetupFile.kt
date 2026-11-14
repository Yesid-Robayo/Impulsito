package io.github.yesidrobayo.shopledger.data.print

import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.data.destination.FlatJson
import io.github.yesidrobayo.shopledger.domain.model.print.PaperWidth
import io.github.yesidrobayo.shopledger.domain.model.print.PrinterSetup
import io.github.yesidrobayo.shopledger.domain.model.print.SavedPrinter
import io.github.yesidrobayo.shopledger.domain.port.PrinterSetupStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Las impresoras guardadas, en `noBackupFilesDir/printers.json`: la dirección, el nombre y el
 * papel de cada una, y cuál va por defecto. Son de la instalación: no viajan en una copia, y
 * restaurar no las toca.
 *
 * Se escribe con temporal, sincronización y renombrado. Es un objeto plano, sin listas: cada
 * impresora ocupa tres claves con su número. Un archivo que no se entiende, que pasa del tope o
 * con una dirección que no es de Bluetooth se lee como vacío: elegirlas de nuevo cuesta un toque,
 * y nunca se usa una dirección dudosa.
 *
 * @param file la ruta del archivo. Se pide al leer o escribir, en [ioContext].
 */
internal class PrinterSetupFile(
    private val file: () -> File,
    private val ioContext: CoroutineContext,
) : PrinterSetupStore {
    private val state = MutableStateFlow<PrinterSetup?>(null)
    private val access = Mutex()

    override fun observe(): Flow<PrinterSetup> =
        state.onStart { if (state.value == null) load() }.filterNotNull()

    override suspend fun save(setup: PrinterSetup): Boolean = access.withLock {
        withContext(ioContext) {
            try {
                DurableFile.write(file(), encode(setup).encodeToByteArray())
                state.value = setup
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    private suspend fun load() = access.withLock {
        if (state.value != null) return@withLock
        state.value = withContext(ioContext) { read() }
    }

    private fun read(): PrinterSetup {
        val bytes =
            try {
                file().readBytes()
            } catch (_: FileNotFoundException) {
                return PrinterSetup.EMPTY
            } catch (_: IOException) {
                return PrinterSetup.EMPTY
            }
        if (bytes.size > MAX_BYTES) return PrinterSetup.EMPTY
        return decode(bytes.decodeToString()) ?: PrinterSetup.EMPTY
    }

    private fun encode(setup: PrinterSetup): String = FlatJson.write(
        buildList {
            add(COUNT to FlatJson.Value.Number(setup.printers.size.toString()))
            setup.defaultAddress?.let { add(DEFAULT to FlatJson.Value.Text(it)) }
            setup.printers.forEachIndexed { index, printer ->
                add(key(index, ADDRESS) to FlatJson.Value.Text(printer.address))
                printer.name?.let { add(key(index, NAME) to FlatJson.Value.Text(it)) }
                add(key(index, PAPER) to FlatJson.Value.Text(printer.paper.name))
            }
        },
    )

    private fun decode(text: String): PrinterSetup? {
        val members = FlatJson.parse(text) ?: return null
        val count = (members[COUNT] as? FlatJson.Value.Number)?.text?.toIntOrNull() ?: return null
        if (count !in 0..PrinterSetup.MAX_PRINTERS) return null
        val printers = (0 until count).map { index ->
            val address = (members[key(index, ADDRESS)] as? FlatJson.Value.Text)?.text
                ?.takeIf(BLUETOOTH_ADDRESS::matches) ?: return null
            val name = when (val value = members[key(index, NAME)]) {
                null -> null

                is FlatJson.Value.Text ->
                    value.text.takeIf { it.length <= MAX_NAME } ?: return null

                else -> return null
            }
            val paper = (members[key(index, PAPER)] as? FlatJson.Value.Text)?.text
                ?.let { stored -> PaperWidth.entries.firstOrNull { it.name == stored } }
                ?: return null
            SavedPrinter(address, name, paper)
        }
        if (printers.map { it.address }.toSet().size != printers.size) return null
        val default = when (val value = members[DEFAULT]) {
            null -> null

            is FlatJson.Value.Text -> value.text.takeIf { address ->
                printers.any { it.address == address }
            } ?: return null

            else -> return null
        }
        val known = setOf(COUNT, DEFAULT) +
            (0 until count).flatMap { listOf(key(it, ADDRESS), key(it, NAME), key(it, PAPER)) }
        if (!known.containsAll(members.keys)) return null
        return PrinterSetup(printers, default)
    }

    private fun key(index: Int, field: String): String = "p${index}_$field"

    companion object {
        /** El nombre del archivo dentro de `noBackupFilesDir`. */
        const val FILE_NAME = "printers.json"

        /** Ocho impresoras con nombres largos caben con holgura. */
        private const val MAX_BYTES = 16 * 1_024

        /** El tope de un nombre Bluetooth, en bytes, que en caracteres nunca es mayor. */
        private const val MAX_NAME = 248
        private const val COUNT = "count"
        private const val DEFAULT = "default"
        private const val ADDRESS = "address"
        private const val NAME = "name"
        private const val PAPER = "paper"
        private val BLUETOOTH_ADDRESS = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    }
}
