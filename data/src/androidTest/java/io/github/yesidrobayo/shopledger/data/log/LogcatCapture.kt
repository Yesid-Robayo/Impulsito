package io.github.yesidrobayo.shopledger.data.log

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Lee el registro del sistema desde una marca, como lo vería `adb logcat -d`: todas las apps y el
 * sistema, también los búferes de caídas y de eventos, donde Android anota el mensaje de una
 * excepción que cerró una app.
 *
 * Se usa así:
 * ```
 * val capture = LogcatCapture.start()
 * // recorrer el camino con los valores de LogSentinels
 * capture.assertClean()
 * ```
 *
 * Lee con el usuario del shell, que puede ver el registro de todos los procesos. No lo borra: la
 * marca separa lo que escribió esta prueba de lo anterior.
 */
internal class LogcatCapture private constructor(private val marker: String) {
    /**
     * Las líneas escritas desde la marca. Espera a que llegue una marca de cierre, porque el
     * registro se escribe por otro proceso y puede llegar con retraso.
     */
    fun lines(): List<String> {
        val end = "fin $marker"
        Log.i(MARKER_TAG, end)
        val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
        while (true) {
            val all = readLogcat()
            val startIndex = all.indexOfLast { it.contains("inicio $marker") }
            val endIndex = all.indexOfLast { it.contains(end) }
            check(startIndex >= 0 || endIndex < 0) {
                "La marca de inicio ya no está en el registro: el búfer dio la vuelta"
            }
            if (startIndex in 0 until endIndex) return all.subList(startIndex + 1, endIndex)
            check(SystemClock.elapsedRealtime() < deadline) {
                "La marca de cierre no llegó al registro a tiempo"
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Las líneas que traen alguno de [sentinels], para mostrarlas si la prueba falla. */
    fun leaks(sentinels: List<String> = LogSentinels.all): List<String> =
        lines().filter { line -> sentinels.any { line.contains(it) } }

    /** Ninguna línea escrita desde la marca trae un centinela. */
    fun assertClean(sentinels: List<String> = LogSentinels.all) {
        val leaks = leaks(sentinels)
        assertEquals("El registro trae datos marcados:\n" + leaks.joinToString("\n"), 0, leaks.size)
    }

    /** Alguna línea de la app, con la etiqueta de la app, trae [text]. */
    fun assertAppLogged(text: String) {
        val tag = AndroidEventLog.TAG
        assertTrue(
            "No se registró: $text",
            lines().any { it.contains(tag) && it.contains(text) },
        )
    }

    companion object {
        private const val MARKER_TAG = "LogcatCapture"
        private const val WAIT_MILLIS = 10_000L
        private const val POLL_MILLIS = 100L

        /** Escribe una marca nueva en el registro. Lo leído empieza después de ella. */
        fun start(): LogcatCapture {
            val marker = UUID.randomUUID().toString()
            Log.i(MARKER_TAG, "inicio $marker")
            return LogcatCapture(marker)
        }

        private fun readLogcat(): List<String> {
            // Sin shell de por medio: cada palabra es un argumento.
            val command = "logcat -d -v threadtime -b main -b system -b crash -b events"
            val output =
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    command,
                )
            return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use {
                it.readLines()
            }
        }
    }
}
