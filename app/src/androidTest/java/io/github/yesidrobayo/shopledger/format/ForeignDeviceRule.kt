package io.github.yesidrobayo.shopledger.format

import java.util.Locale
import java.util.TimeZone
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Un idioma y una zona del teléfono distintos de cualquier formato regional y zona de negocio de
 * las pruebas: si un formateador leyera los del proceso en vez de los que recibe, sus resultados
 * cambiarían.
 */
data class ForeignDevice(val languageTag: String, val zoneId: String) {
    override fun toString(): String = "$languageTag $zoneId"

    companion object {
        val ALL: List<ForeignDevice> =
            listOf(
                // Cifras arábigo-índicas, separadores propios y escritura de derecha a izquierda.
                ForeignDevice("ar-EG", "Pacific/Kiritimati"),
                // Cifras tailandesas y calendario budista, con el año 543 años adelante.
                ForeignDevice("th-TH-u-ca-buddhist-nu-thai", "Etc/GMT+12"),
                // Coma decimal y punto de miles: al revés que una región de punto decimal.
                ForeignDevice("de-DE", "Asia/Kathmandu"),
            )
    }
}

/**
 * Pone [device] como idioma y zona por defecto del proceso mientras corre cada prueba, como si el
 * teléfono o la app estuvieran así, y deja los anteriores al terminar, aunque la prueba falle.
 */
class ForeignDeviceRule(private val device: ForeignDevice) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val previousLocale = Locale.getDefault()
                val previousZone = TimeZone.getDefault()
                Locale.setDefault(Locale.forLanguageTag(device.languageTag))
                TimeZone.setDefault(TimeZone.getTimeZone(device.zoneId))
                try {
                    base.evaluate()
                } finally {
                    Locale.setDefault(previousLocale)
                    TimeZone.setDefault(previousZone)
                }
            }
        }
}
