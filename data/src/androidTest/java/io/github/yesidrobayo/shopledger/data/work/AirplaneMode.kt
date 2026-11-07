package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * El modo avión de verdad en el emulador: la radio apagada y ninguna red activa. Lo enciende la
 * línea de comandos del sistema, y al terminar lo deja como estaba.
 *
 * Desde las versiones que traen `cmd connectivity airplane-mode`, la prueba lo enciende sola.
 * Antes, solo un proceso del sistema puede avisar del cambio: la prueba lo hace si la
 * instrumentación corre como root, como en un emulador tras `adb root`, y si no, se salta con el
 * motivo a la vista. Nunca da por buena una red que sigue activa.
 */
internal class AirplaneMode(context: Context) {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val connectivity =
        requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private var turnedOn = false

    /** Si ahora no hay ninguna red activa. */
    val offline: Boolean get() = connectivity.activeNetwork == null

    /** Enciende el modo avión y espera a que no quede ninguna red activa. */
    fun turnOn() {
        if (!isOn()) {
            assumeTrue(
                "Esta versión no deja que la prueba encienda el modo avión sin root: " +
                    "corre la prueba tras adb root",
                hasCommand() || shell("id -u").trim() == ROOT_UID,
            )
            set(on = true)
            turnedOn = true
        }
        check(awaitOffline(expected = true)) { "La red siguió activa con el modo avión" }
    }

    /** Lo apaga si lo encendió esta prueba, y espera a que vuelva una red. */
    fun restore() {
        if (!turnedOn) return
        set(on = false)
        turnedOn = false
        // Si la red tarda en volver, no es un fallo de la prueba: la siguiente no la necesita.
        awaitOffline(expected = false)
    }

    private fun set(on: Boolean) {
        if (hasCommand()) {
            shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
        } else {
            shell("settings put global airplane_mode_on ${if (on) 1 else 0}")
            shell("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on")
        }
    }

    private fun isOn(): Boolean = shell("settings get global airplane_mode_on").trim() == "1"

    /** Sin argumentos, el comando responde el estado; donde no existe, dice otra cosa. */
    private fun hasCommand(): Boolean =
        shell("cmd connectivity airplane-mode").trim() in setOf("enabled", "disabled")

    private fun awaitOffline(expected: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + NETWORK_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (offline == expected) return true
            Thread.sleep(POLL_MILLIS)
        }
        return offline == expected
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.bufferedReader().readText()
        }

    private companion object {
        const val ROOT_UID = "0"
        const val NETWORK_WAIT_MILLIS = 30_000L
        const val POLL_MILLIS = 250L
    }
}
