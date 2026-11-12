package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayTotals
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import kotlinx.coroutines.flow.Flow

/**
 * Las sumas de ganancia de un período, agrupadas por fecha de negocio. Solo lee.
 *
 * Un período es un rango de fechas con sus dos extremos: un día, una semana que empieza en el día
 * que eligió el negocio, un mes o un rango cualquiera. La semana y el mes se arman en `:domain`
 * como rangos, nunca sumando días ni semanas en la base, así que una semana que parte dos meses
 * cuenta cada día en el mes al que pertenece.
 *
 * Cada documento cuenta en su propia fecha de negocio, la que quedó guardada al escribirlo con la
 * zona del negocio de ese momento: cambiar la zona después no mueve nada. Una anulación saca al
 * documento de su fecha original, y una devolución resta en la suya.
 */
interface PeriodReportRepository {
    /**
     * Las sumas de [period], de nuevo cada vez que se confirma un cambio en ventas, pagos,
     * devoluciones, gastos o el libro de stock, sin repetir un resultado igual al anterior.
     */
    fun observeTotals(period: ReportPeriod): Flow<PeriodTotals>

    /**
     * Las sumas de cada día de [period] que tiene algún documento, del más antiguo al más nuevo, y
     * de nuevo con cada cambio confirmado. Los días sin documentos no aparecen.
     */
    fun observeDailyTotals(period: ReportPeriod): Flow<List<DayTotals>>

    /**
     * La fecha de negocio más reciente con una venta o un gasto activos, o `null` si no hay
     * ninguno, de nuevo con cada cambio confirmado. Puede ser posterior a hoy si el reloj del
     * teléfono estuvo adelantado, y las estadísticas deben poder llegar hasta ella.
     */
    fun observeLastActiveDate(): Flow<BusinessDate?>
}
