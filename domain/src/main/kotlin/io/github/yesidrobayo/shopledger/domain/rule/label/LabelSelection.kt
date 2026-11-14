package io.github.yesidrobayo.shopledger.domain.rule.label

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.label.LabelTemplate

/** Un producto elegido para etiquetas, con lo que la etiqueta puede llevar. */
data class LabelItem(
    val productId: Long,
    val name: String,
    val price: Money,
    val code: String?,
    val copies: Int,
)

/**
 * Lo que sale con una plantilla: [printable] se imprime; [withoutPrice] y [withoutCode] quedan por
 * fuera, y la pantalla dice cuántos y por qué. [labels] es el total de etiquetas, con las copias.
 */
data class LabelPlan(
    val printable: List<LabelItem>,
    val withoutPrice: List<LabelItem>,
    val withoutCode: List<LabelItem>,
) {
    val labels: Int get() = printable.sumOf { it.copies }
}

/**
 * Qué productos entran en cada plantilla. Un producto sin precio no entra en una que muestra el
 * precio: una etiqueta en cero confunde a quien compra. Uno sin código no entra en una que lo
 * muestra, y la pantalla ofrece generarlo. Si le faltan los dos, cuenta como sin precio, porque
 * generar el código no lo haría imprimible.
 */
object LabelSelection {
    fun plan(items: List<LabelItem>, template: LabelTemplate): LabelPlan {
        val printable = mutableListOf<LabelItem>()
        val withoutPrice = mutableListOf<LabelItem>()
        val withoutCode = mutableListOf<LabelItem>()
        for (item in items) {
            when {
                template.showsPrice && item.price <= Money.ZERO -> withoutPrice += item
                template.showsCode && item.code.isNullOrBlank() -> withoutCode += item
                else -> printable += item
            }
        }
        return LabelPlan(printable, withoutPrice, withoutCode)
    }
}

/**
 * Cuánto mide cada módulo de un Code 128 en una etiqueta, en puntos enteros de 203 ppp, los de una
 * térmica. La hoja PDF se dibuja con esos mismos puntos y luego se escala a su tamaño.
 *
 * El código lleva una zona de silencio de [QUIET_ZONE_MODULES] módulos a cada lado, la que pide la
 * norma para que un lector encuentre dónde empieza. El módulo más grande que cabe se elige entre
 * [minimum] y [MAX_MODULE]: más chico que el mínimo no se lee bien, y más grande no se lee mejor.
 */
object LabelBarcodeFit {
    const val QUIET_ZONE_MODULES = 10

    /** En térmica, 3 puntos son 0,375 mm a 203 ppp: lo más chico que una cámara lee sin dudar. */
    const val MIN_THERMAL_MODULE = 3

    /** En la hoja, 2 puntos son 0,25 mm: una impresora de tinta o láser los dibuja nítidos. */
    const val MIN_SHEET_MODULE = 2

    const val MAX_MODULE = 4

    /**
     * El ancho de cada módulo para un código de [symbolModules] módulos en [available] unidades, o
     * `null` si no cabe legible.
     */
    fun moduleWidth(symbolModules: Int, available: Int, minimum: Int): Int? {
        require(symbolModules > 0) { "Un código tiene al menos un módulo" }
        require(minimum in 1..MAX_MODULE) { "Mínimo de módulo fuera de rango" }
        val total = symbolModules + 2 * QUIET_ZONE_MODULES
        val widest = minOf(available / total, MAX_MODULE)
        return widest.takeIf { it >= minimum }
    }
}
