package io.github.yesidrobayo.shopledger.ui.document

import android.content.res.Resources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.currency.CurrencySymbolFonts
import io.github.yesidrobayo.shopledger.ui.currency.LEFT_TO_RIGHT_ISOLATE
import io.github.yesidrobayo.shopledger.ui.currency.POP_DIRECTIONAL_ISOLATE
import io.github.yesidrobayo.shopledger.ui.currency.moneyText
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.theme.CurrencySymbolFamily

/** La fuente con que se dibuja un tramo de un monto en papel. */
enum class DocumentFont {
    /**
     * Figtree: cifras, signo y lo que Figtree tiene del símbolo. Las cifras son tabulares en la
     * imagen del comprobante y en la térmica, y de ancho propio en un PDF, para poder copiarlas.
     */
    APP,

    /** `currency_symbols`, para los símbolos que Figtree no trae. */
    CURRENCY,

    /** La fuente del sistema, con su respaldo, para lo que ninguna de las dos tiene. */
    SYSTEM,
}

/** Un tramo de un monto que se dibuja con una sola fuente. */
data class DocumentRun(val text: String, val font: DocumentFont)

/**
 * Los montos de un documento, en tramos por fuente y en el orden de la región.
 *
 * Sale del mismo [moneyText] que la pantalla, así que el orden de signo, símbolo y cifras, el
 * espacio duro y el cambio a código ISO son idénticos. En papel cada tramo se dibuja aparte, con
 * su fuente, y el monto entero se alinea a la derecha: un símbolo ancho nunca mueve una cifra. Los
 * aislamientos de dirección se quitan, porque cada tramo ya tiene su lugar.
 */
internal class DocumentMoney(resources: Resources, private val formats: AppFormats) {
    private val chain = CurrencySymbolFonts.chain(resources)

    fun runsOf(amount: Money): List<DocumentRun> {
        val parts = formats.money.parts(amount)
        val text = moneyText(
            parts,
            chain.resolve(parts.symbol, parts.currencyCode),
            currencyFamily = CurrencySymbolFamily,
        )
        return runsOf(text)
    }

    private fun runsOf(text: AnnotatedString): List<DocumentRun> {
        val runs = mutableListOf<DocumentRun>()
        text.text.forEachIndexed { index, char ->
            if (char == LEFT_TO_RIGHT_ISOLATE || char == POP_DIRECTIONAL_ISOLATE) {
                return@forEachIndexed
            }
            val family = text.spanStyles
                .firstOrNull { index >= it.start && index < it.end }
                ?.item?.fontFamily
            val font = when (family) {
                CurrencySymbolFamily -> DocumentFont.CURRENCY
                FontFamily.Default -> DocumentFont.SYSTEM
                else -> DocumentFont.APP
            }
            val last = runs.lastOrNull()
            if (last != null && last.font == font) {
                runs[runs.lastIndex] = last.copy(text = last.text + char)
            } else {
                runs += DocumentRun(char.toString(), font)
            }
        }
        return runs
    }
}
