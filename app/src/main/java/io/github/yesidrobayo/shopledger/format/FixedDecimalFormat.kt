package io.github.yesidrobayo.shopledger.format

import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import android.icu.util.ULocale

/**
 * Un formato de ICU con [fractionDigits] decimales exactos, ni uno más ni uno menos, en el estilo
 * [style] de la región, por ejemplo [NumberFormat.NUMBERSTYLE] o [NumberFormat.PERCENTSTYLE].
 *
 * Los valores llegan como decimales exactos que ya traen esos decimales. Si ICU quisiera redondear,
 * es un error de quien llama, y falla: un número redondeado en silencio sería un dato falso.
 */
internal fun fixedDecimalFormat(locale: ULocale, style: Int, fractionDigits: Int): DecimalFormat {
    val format = NumberFormat.getInstance(locale, style)
    return requireNotNull(format as? DecimalFormat) {
        "ICU no dio un formato decimal para la región"
    }.apply {
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = fractionDigits
        roundingMode = android.icu.math.BigDecimal.ROUND_UNNECESSARY
    }
}
