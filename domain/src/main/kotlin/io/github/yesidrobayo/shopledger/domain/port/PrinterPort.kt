package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult

/**
 * Lleva los bytes de un trabajo de impresión hasta una impresora, sea cual sea el canal. Lo
 * implementa `:data` con Bluetooth, solo hacia impresoras emparejadas, y las pruebas con una
 * impresora falsa que graba lo que recibe. Un fallo nunca afecta a lo que ya se guardó: solo se
 * informa.
 */
fun interface PrinterPort {
    suspend fun send(payload: ByteArray): PrintResult
}
