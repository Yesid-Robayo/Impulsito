package io.github.yesidrobayo.shopledger.domain.testing

import java.util.Locale
import java.util.TimeZone
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Una zona horaria y un idioma por defecto de la JVM, distintos de cualquier zona de negocio de las
 * pruebas, para demostrar que las reglas de fechas no dependen del equipo donde corren.
 */
data class ForeignSystemDefaults(val zoneId: String, val languageTag: String) {
    override fun toString(): String = "default $zoneId $languageTag"

    companion object {
        /**
         * Los dos desfases extremos que existen, con cifras y calendario de otro sistema: si una
         * regla leyera la zona o el idioma del equipo, el día o el texto cambiarían.
         */
        val ALL: List<ForeignSystemDefaults> =
            listOf(
                ForeignSystemDefaults("Etc/GMT-14", "th-TH-u-ca-buddhist-nu-thai"),
                ForeignSystemDefaults("Etc/GMT+12", "ar-SA-u-nu-arab"),
            )
    }
}

/**
 * Fija [defaults] como zona e idioma por defecto de la JVM mientras corre cada prueba, y deja los
 * anteriores al terminar, aunque la prueba falle.
 */
class ForeignSystemDefaultsRule(private val defaults: ForeignSystemDefaults) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val previousZone = TimeZone.getDefault()
                val previousLocale = Locale.getDefault()
                TimeZone.setDefault(TimeZone.getTimeZone(defaults.zoneId))
                Locale.setDefault(Locale.forLanguageTag(defaults.languageTag))
                try {
                    base.evaluate()
                } finally {
                    TimeZone.setDefault(previousZone)
                    Locale.setDefault(previousLocale)
                }
            }
        }
}
