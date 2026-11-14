package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.stock.DatedLotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.ExpiringLot
import io.github.yesidrobayo.shopledger.domain.model.stock.LotDue
import kotlinx.datetime.daysUntil

/**
 * Cuándo un lote entra en el aviso de vencimiento: si le quedan los días de aviso o menos. Los
 * días son los del producto, si los fijó, o los de los ajustes. Un lote ya vencido sigue en la
 * lista mientras tenga stock: se marca, no se esconde, porque todavía hay que sacarlo.
 */
object ExpiryRules {
    /** Los días de aviso que valen para un producto: los suyos, o los de los ajustes. */
    fun noticeDaysOf(productDays: Int?, settingsDays: Int): Int = productDays ?: settingsDays

    /** Los días que faltan desde [today] hasta [expiry]: cero vence hoy, negativo ya venció. */
    fun daysLeft(expiry: BusinessDate, today: BusinessDate): Int = today.date.daysUntil(expiry.date)

    /**
     * Cuándo vence un lote con fecha [expiry], visto desde [today], o `null` si no vence. Sin
     * [today], porque no se sabe la zona del negocio, el lote no se marca: ni pronto ni vencido.
     * [noticeDays] son los días de aviso que valen para su producto.
     */
    fun dueOf(expiry: BusinessDate?, today: BusinessDate?, noticeDays: Int): LotDue? {
        if (expiry == null) return null
        if (today == null) return LotDue(expiry, daysLeft = null, soon = false)
        val left = daysLeft(expiry, today)
        return LotDue(expiry, left, soon = left <= noticeDays)
    }

    /**
     * El lote dentro de su aviso, con los días que le faltan, o `null` si todavía no le toca. Con
     * cero días de aviso entra el mismo día que vence.
     */
    fun expiringOf(lot: DatedLotStock, today: BusinessDate, settingsDays: Int): ExpiringLot? {
        val left = daysLeft(lot.expiryDate, today)
        val notice = noticeDaysOf(lot.noticeDays, settingsDays)
        return if (left <= notice) ExpiringLot(lot, left) else null
    }

    /**
     * Los lotes de [lots] que están en su aviso, del que vence antes al que vence después, y entre
     * dos del mismo día, por id.
     */
    fun expiringOf(
        lots: List<DatedLotStock>,
        today: BusinessDate,
        settingsDays: Int,
    ): List<ExpiringLot> = lots
        .mapNotNull { expiringOf(it, today, settingsDays) }
        .sortedWith(compareBy({ it.lot.expiryDate }, { it.lot.lotId }))
}
