package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementTotals
import kotlinx.coroutines.flow.Flow

/**
 * Lo que la ganancia si se repone lee de un período. Solo lee, y nunca toca lo que suma la ganancia
 * en ventas: las dos se leen aparte, para que una no pueda cambiar a la otra.
 */
interface ReplacementReportRepository {
    /**
     * La reposición congelada en las ventas activas de [period] y las líneas devueltas al stock en
     * sus devoluciones activas, de nuevo con cada cambio confirmado en ventas o devoluciones, sin
     * repetir un resultado igual al anterior.
     */
    fun observeTotals(period: ReportPeriod): Flow<ReplacementTotals>
}
