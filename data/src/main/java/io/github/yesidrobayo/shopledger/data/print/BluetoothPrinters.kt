package io.github.yesidrobayo.shopledger.data.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrinter
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrintersResult
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.PrinterPort
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Las impresoras térmicas por Bluetooth, solo las que la persona ya emparejó en los ajustes del
 * teléfono. Nunca busca ni empareja: por eso no necesita permisos de búsqueda ni de ubicación, y
 * tampoco cancela una búsqueda, que los exigiría. No usa SDK de fabricante.
 *
 * El registro solo lleva estados, tamaños y duraciones: nunca el nombre ni la dirección de una
 * impresora.
 */
class BluetoothPrinters internal constructor(
    private val context: Context,
    private val log: EventLog,
    private val ioContext: CoroutineContext,
) {
    /** Las emparejadas, por nombre, o por qué no se pueden leer. */
    // El permiso se comprueba en adapterOrFailure antes de leer la lista.
    @SuppressLint("MissingPermission")
    fun paired(): PairedPrintersResult {
        val adapter =
            when (val access = adapterOrFailure(context)) {
                is AdapterAccess.Ready -> access.adapter
                is AdapterAccess.Blocked -> return PairedPrintersResult.Unavailable(access.reason)
            }
        val printers =
            adapter.bondedDevices.orEmpty().map { PairedPrinter(it.name, it.address) }
        return PairedPrintersResult.Listed(printers.sortedBy { it.name.orEmpty() })
    }

    /** La impresora de [address], que se vuelve a buscar entre las emparejadas al enviar. */
    fun printer(address: String): PrinterPort = BluetoothPrinterTransport(address)

    /**
     * Envía por el perfil de puerto serie (RFCOMM con el UUID estándar), el que usan las térmicas
     * de bolsillo.
     */
    private inner class BluetoothPrinterTransport(private val address: String) : PrinterPort {
        // El permiso se comprueba en adapterOrFailure antes de tocar el dispositivo.
        @SuppressLint("MissingPermission")
        override suspend fun send(payload: ByteArray): PrintResult = withContext(ioContext) {
            val adapter =
                when (val access = adapterOrFailure(context)) {
                    is AdapterAccess.Ready -> access.adapter
                    is AdapterAccess.Blocked -> return@withContext failed(access.reason)
                }
            // Solo un dispositivo emparejado: una dirección cualquiera no se acepta.
            val device =
                adapter.bondedDevices.orEmpty().firstOrNull { it.address == address }
                    ?: return@withContext failed(PrintFailure.NOT_BONDED)
            val started = SystemClock.elapsedRealtime()
            val socket =
                try {
                    device.createRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE)
                } catch (e: IOException) {
                    log.warn(PrintEvent.CHANNEL_FAILED, e)
                    return@withContext failed(PrintFailure.CONNECT_FAILED)
                }
            try {
                if (!connectWithTimeout(socket)) {
                    return@withContext failed(PrintFailure.CONNECT_FAILED)
                }
                try {
                    val output = socket.outputStream
                    var offset = 0
                    while (offset < payload.size) {
                        val length = minOf(CHUNK_BYTES, payload.size - offset)
                        output.write(payload, offset, length)
                        offset += length
                    }
                    output.flush()
                } catch (e: IOException) {
                    log.warn(PrintEvent.WRITE_FAILED, e)
                    return@withContext failed(PrintFailure.WRITE_FAILED)
                }
                // Cerrar enseguida puede descartar lo que la pila aún no mandó.
                delay(DRAIN_MILLIS)
                log.info(
                    PrintEvent.SENT,
                    LogField.count("bytes", payload.size.toLong()),
                    LogField.durationMillis("envio", SystemClock.elapsedRealtime() - started),
                )
                PrintResult.Sent(payload.size)
            } finally {
                closeQuietly(socket)
            }
        }

        /**
         * `connect()` bloquea sin plazo propio. Cerrar el canal desde otro hilo lo desbloquea con
         * una `IOException`, así que un vigilante lo cierra al vencer el plazo.
         */
        private suspend fun connectWithTimeout(socket: BluetoothSocket): Boolean = coroutineScope {
            val watchdog =
                launch {
                    delay(CONNECT_TIMEOUT_MILLIS)
                    closeQuietly(socket)
                }
            try {
                socket.connect()
                true
            } catch (e: IOException) {
                log.warn(PrintEvent.CONNECT_FAILED, e)
                false
            } finally {
                watchdog.cancel()
            }
        }
    }

    private fun failed(reason: PrintFailure): PrintResult {
        log.warn(PrintEvent.NOT_SENT, LogField.state("motivo", reason))
        return PrintResult.Failed(reason)
    }

    private fun closeQuietly(socket: BluetoothSocket) {
        try {
            socket.close()
        } catch (e: IOException) {
            log.warn(PrintEvent.CLOSE_FAILED, e)
        }
    }

    private companion object {
        /** El perfil de puerto serie de Bluetooth, igual en todas las impresoras de este tipo. */
        val SERIAL_PORT_PROFILE: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val CONNECT_TIMEOUT_MILLIS = 12_000L
        const val DRAIN_MILLIS = 800L
        const val CHUNK_BYTES = 1024
    }
}

/** Lo que registra la impresión. Ninguna línea lleva el nombre ni la dirección de la impresora. */
internal enum class PrintEvent(override val text: String) : LogEvent {
    SENT("Trabajo enviado a la impresora"),
    NOT_SENT("Trabajo de impresión no enviado"),
    CHANNEL_FAILED("No se pudo crear el canal con la impresora"),
    CONNECT_FAILED("No se pudo conectar con la impresora"),
    WRITE_FAILED("El envío a la impresora se cortó"),
    CLOSE_FAILED("No se pudo cerrar el canal con la impresora"),
}

private sealed interface AdapterAccess {
    data class Ready(val adapter: BluetoothAdapter) : AdapterAccess

    data class Blocked(val reason: PrintFailure) : AdapterAccess
}

private fun adapterOrFailure(context: Context): AdapterAccess {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
        return AdapterAccess.Blocked(PrintFailure.NO_ADAPTER)
    }
    val adapter =
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return AdapterAccess.Blocked(PrintFailure.NO_ADAPTER)
    if (!hasConnectPermission(context)) {
        return AdapterAccess.Blocked(PrintFailure.PERMISSION_MISSING)
    }
    if (!adapter.isEnabled) return AdapterAccess.Blocked(PrintFailure.BLUETOOTH_OFF)
    return AdapterAccess.Ready(adapter)
}

/**
 * Desde Android 12 hace falta `BLUETOOTH_CONNECT`, que se pide en contexto. Hasta Android 11 basta
 * `BLUETOOTH`, que se concede al instalar y no muestra diálogo.
 */
private fun hasConnectPermission(context: Context): Boolean {
    val permission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
    return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
