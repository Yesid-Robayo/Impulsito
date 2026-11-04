package io.github.yesidrobayo.shopledger.data.log

import android.util.Log
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine

/**
 * El registro de la app sobre el de Android. Es el único archivo del código de la app que escribe
 * en él: la compilación lo comprueba en cada `check`.
 *
 * Recibe la línea ya armada por [EventLog], sin datos del negocio ni mensajes de excepción, y nunca
 * pasa una excepción al registro de Android, que escribiría su mensaje y su traza.
 *
 * Todas las líneas van con la misma etiqueta, [TAG], para leerlas juntas. En release, la compilación
 * borra las de información: solo quedan avisos y errores.
 */
internal class AndroidEventLog : EventLog {
    override fun write(level: LogLevel, line: LogLine) {
        when (level) {
            LogLevel.INFO -> Log.i(TAG, line.text)
            LogLevel.WARN -> Log.w(TAG, line.text)
            LogLevel.ERROR -> Log.e(TAG, line.text)
        }
    }

    companion object {
        /** La etiqueta de todas las líneas de la app. */
        const val TAG = "ShopLedger"
    }
}
