package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadUsage
import kotlinx.datetime.number

/**
 * El consumo de datos del mes: cuánto subió la app a WebDAV desde el primer día del mes en la zona
 * del negocio. Suma por igual Wi-Fi y datos móviles, porque un router con datos móviles puede verse
 * como Wi-Fi, y este número es lo que lo delata.
 */
object UploadUsageRule {
    /** El mes de [date], como `AAAA-MM`: el mismo texto en cualquier idioma y región. */
    fun monthOf(date: BusinessDate): String {
        val year = date.date.year.toString().padStart(YEAR_DIGITS, '0')
        val month = date.date.month.number.toString().padStart(MONTH_DIGITS, '0')
        return "$year-$month"
    }

    /**
     * Suma [bytes] enviados en [month] a lo guardado. Otro mes empieza de cero: el contador cuenta
     * solo el mes en curso. Un total que no cabe queda en el máximo, nunca negativo.
     */
    fun add(stored: UploadUsage?, month: String, bytes: Long): UploadUsage {
        require(bytes >= 0) { "Los bytes no pueden ser negativos" }
        val before = stored?.takeIf { it.month == month }?.bytes ?: 0L
        val total = if (before > Long.MAX_VALUE - bytes) Long.MAX_VALUE else before + bytes
        return UploadUsage(month, total)
    }

    /** Lo subido en [month]: cero si lo guardado es de otro mes o no hay nada. */
    fun inMonth(stored: UploadUsage?, month: String): Long =
        stored?.takeIf { it.month == month }?.bytes ?: 0L

    private const val YEAR_DIGITS = 4
    private const val MONTH_DIGITS = 2
}
