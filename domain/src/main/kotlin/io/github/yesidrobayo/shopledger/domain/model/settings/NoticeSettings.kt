package io.github.yesidrobayo.shopledger.domain.model.settings

import kotlinx.datetime.LocalTime

/**
 * Cuándo avisa la app de algo que conviene revisar.
 *
 * - [costStaleDays]: días sin comprar un producto tras los que su costo se marca como viejo. Cero
 *   apaga el aviso.
 * - [expiryNoticeDays]: días antes del vencimiento de un lote en que se avisa, salvo que el
 *   producto fije los suyos.
 * - [revenueLimit]: el tope de ingresos a vigilar, o `null` si la persona no escribió uno.
 * - [dailySummaryTime]: la hora del resumen del día, en la zona del negocio, o `null` si está
 *   apagado.
 */
data class NoticeSettings(
    val costStaleDays: Int,
    val expiryNoticeDays: Int,
    val revenueLimit: RevenueLimit?,
    val dailySummaryTime: LocalTime?,
)
