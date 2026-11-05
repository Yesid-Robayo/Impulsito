package io.github.yesidrobayo.shopledger.ui.currency

import java.text.Normalizer

/** La fuente que dibuja una corrida del símbolo. */
enum class SymbolFont {
    /** Figtree, la fuente de la app. */
    APP,

    /** `currency_symbols`, el subconjunto propio de símbolos de moneda. */
    CURRENCY_SYMBOLS,

    /** La fuente del sistema, con todo su respaldo. */
    SYSTEM,
}

/** Responde si una fuente dibuja un punto de código por sí sola. */
fun interface GlyphCoverage {
    fun covers(codePoint: Int): Boolean
}

/** Un tramo del símbolo que se dibuja con una sola fuente. */
data class SymbolRun(val text: String, val font: SymbolFont)

/**
 * Un símbolo listo para dibujar, en tramos por fuente. [usesIsoCode] dice si ninguna fuente lo tenía
 * y quedó el código ISO de la moneda en su lugar.
 */
data class ResolvedSymbol(val runs: List<SymbolRun>, val usesIsoCode: Boolean) {
    val text: String get() = runs.joinToString(separator = "") { it.text }
}

/**
 * Decide, carácter por carácter, qué fuente dibuja un símbolo de moneda, para que nunca salga un
 * cuadro vacío. Un símbolo puede mezclar letras y signos, como `GH₵`, y por eso no se decide por
 * símbolo entero.
 *
 * El orden es Figtree, `currency_symbols`, la forma NFKC con esas dos fuentes, y la fuente del
 * sistema. El glifo propio va antes que NFKC para que `₨` se dibuje como símbolo y no como las
 * letras `Rs`. Las fuentes propias responden por su tabla `cmap`; la del sistema, por
 * `Paint.hasGlyph`, que es la única pregunta que sirve para ella.
 *
 * Si un solo carácter no tiene fuente, el símbolo entero se cambia por el código ISO, que Figtree
 * siempre dibuja: medio símbolo confundiría más que el código.
 */
class CurrencySymbolChain(
    private val appFont: GlyphCoverage,
    private val currencyFont: GlyphCoverage,
    private val systemFont: GlyphCoverage,
) {
    fun resolve(symbol: String, isoCode: String): ResolvedSymbol {
        val runs = mutableListOf<SymbolRun>()
        for (codePoint in codePointsOf(symbol)) {
            val resolved = resolveCodePoint(codePoint)
                ?: return ResolvedSymbol(
                    listOf(SymbolRun(isoCode, SymbolFont.APP)),
                    usesIsoCode = true,
                )
            runs += resolved
        }
        return ResolvedSymbol(mergeAdjacent(runs), usesIsoCode = false)
    }

    private fun resolveCodePoint(codePoint: Int): List<SymbolRun>? {
        val original = String(Character.toChars(codePoint))
        if (appFont.covers(codePoint)) return listOf(SymbolRun(original, SymbolFont.APP))
        if (currencyFont.covers(
                codePoint,
            )
        ) {
            return listOf(SymbolRun(original, SymbolFont.CURRENCY_SYMBOLS))
        }
        val normalized = Normalizer.normalize(original, Normalizer.Form.NFKC)
        if (normalized != original) {
            val runs =
                codePointsOf(normalized).map { point ->
                    val text = String(Character.toChars(point))
                    when {
                        appFont.covers(point) -> SymbolRun(text, SymbolFont.APP)
                        currencyFont.covers(point) -> SymbolRun(text, SymbolFont.CURRENCY_SYMBOLS)
                        else -> null
                    }
                }
            if (runs.isNotEmpty() && runs.all { it != null }) return runs.filterNotNull()
        }
        if (systemFont.covers(codePoint)) return listOf(SymbolRun(original, SymbolFont.SYSTEM))
        return null
    }

    companion object {
        /** Une tramos vecinos de la misma fuente, para que el texto se forme de una vez. */
        private fun mergeAdjacent(runs: List<SymbolRun>): List<SymbolRun> {
            val merged = mutableListOf<SymbolRun>()
            for (run in runs) {
                val last = merged.lastOrNull()
                if (last != null && last.font == run.font) {
                    merged[merged.lastIndex] = SymbolRun(last.text + run.text, run.font)
                } else {
                    merged += run
                }
            }
            return merged
        }

        /** Los puntos de código de [text], uniendo los pares sustitutos. */
        fun codePointsOf(text: String): List<Int> {
            val points = mutableListOf<Int>()
            var index = 0
            while (index < text.length) {
                val point = text.codePointAt(index)
                points += point
                index += Character.charCount(point)
            }
            return points
        }
    }
}
