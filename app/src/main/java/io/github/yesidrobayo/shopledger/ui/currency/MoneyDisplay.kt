package io.github.yesidrobayo.shopledger.ui.currency

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.format.IcuTextNormalizer
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.format.MoneyParts
import io.github.yesidrobayo.shopledger.format.SignPosition
import io.github.yesidrobayo.shopledger.ui.theme.CurrencySymbolFamily

/**
 * Un monto listo para una pantalla: el texto, con el símbolo en su propia corrida, y lo que lee
 * TalkBack, con el nombre de la moneda en lugar del símbolo.
 */
@Immutable
data class MoneyDisplay(val text: AnnotatedString, val description: String)

/** Abre el aislamiento de dirección del símbolo, de izquierda a derecha. */
const val LEFT_TO_RIGHT_ISOLATE: Char = '\u2066'

/** Cierra el aislamiento de dirección del símbolo. */
const val POP_DIRECTIONAL_ISOLATE: Char = '\u2069'

/**
 * El texto de un monto, en el orden de la región: signo, símbolo y cifras.
 *
 * Las cifras y el signo heredan el estilo del monto, con sus cifras tabulares. El símbolo va en sus
 * propios tramos: lo que dibuja Figtree hereda todo, y lo demás cambia solo de familia, así que
 * peso, tamaño y alto de línea siguen siendo los del monto, y un símbolo ancho nunca mueve una cifra.
 *
 * El símbolo va aislado entre LRI y PDI. Sin eso, un símbolo árabe delante de las cifras las vuelve
 * parte de un texto de derecha a izquierda, y el símbolo se dibuja después del monto. El espacio
 * entre símbolo y cifras es duro, para que el monto nunca se parta en dos líneas. Si ninguna fuente
 * tiene el símbolo, va el código ISO, siempre separado de las cifras: `ABC 1.200` y no `ABC1.200`.
 */
fun moneyText(
    parts: MoneyParts,
    symbol: ResolvedSymbol,
    currencyFamily: FontFamily = CurrencySymbolFamily,
): AnnotatedString = buildAnnotatedString {
    val space = parts.symbolSpace || symbol.usesIsoCode
    val sign = if (parts.negative) IcuTextNormalizer.MINUS_SIGN.toString() else ""

    fun symbolRun() {
        append(LEFT_TO_RIGHT_ISOLATE)
        for (run in symbol.runs) {
            when (run.font) {
                SymbolFont.APP -> append(run.text)

                SymbolFont.CURRENCY_SYMBOLS ->
                    withStyle(SpanStyle(fontFamily = currencyFamily)) { append(run.text) }

                SymbolFont.SYSTEM ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Default)) { append(run.text) }
            }
        }
        append(POP_DIRECTIONAL_ISOLATE)
    }

    fun space() {
        if (space) append(IcuTextNormalizer.NO_BREAK_SPACE)
    }

    fun signAt(position: SignPosition) {
        if (parts.signPosition == position) append(sign)
    }

    signAt(SignPosition.LEADING)
    if (parts.symbolFirst) {
        symbolRun()
        signAt(SignPosition.NEXT_TO_SYMBOL)
        space()
        signAt(SignPosition.NEXT_TO_NUMBER)
        append(parts.number)
    } else {
        append(parts.number)
        signAt(SignPosition.NEXT_TO_NUMBER)
        space()
        signAt(SignPosition.NEXT_TO_SYMBOL)
        symbolRun()
    }
    signAt(SignPosition.TRAILING)
}

/**
 * Lo que dice TalkBack de un monto: las cifras y el nombre de la moneda en el idioma de la interfaz,
 * como `menos 1.200,00 euros`. Nunca el símbolo, que cada motor de voz lee a su manera o no lee.
 */
fun moneyDescription(parts: MoneyParts, resources: Resources): String {
    val amount = resources.getString(R.string.a11y_money_amount, parts.number, parts.currencyName)
    return if (parts.negative) resources.getString(R.string.a11y_minus_amount, amount) else amount
}

/**
 * El monto [amount] formateado con [formatter], listo para dibujar con su lectura para TalkBack.
 * Las fuentes de los símbolos se leen una sola vez por proceso, y el texto se arma de nuevo solo si
 * cambia el monto, el formateador o el idioma de los recursos.
 */
@Composable
fun rememberMoneyDisplay(amount: Money, formatter: MoneyFormatter): MoneyDisplay {
    val resources = LocalResources.current
    val chain = remember(resources) { CurrencySymbolFonts.chain(resources) }
    return remember(amount, formatter, chain, resources) {
        val parts = formatter.parts(amount)
        MoneyDisplay(
            text = moneyText(parts, chain.resolve(parts.symbol, parts.currencyCode)),
            description = moneyDescription(parts, resources),
        )
    }
}
