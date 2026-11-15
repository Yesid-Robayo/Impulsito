package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryAvailability
import kotlinx.coroutines.flow.Flow

/**
 * Lo que el `Resumen de gestión` necesita saber de la base además de las sumas de `Números`: si
 * hay compras en el período, stock hasta su cierre y clientes o proveedores. Solo lee, con
 * consultas que se detienen en la primera fila.
 */
fun interface ManagementSummaryRepository {
    /**
     * Lo que hay para [period], de nuevo con cada cambio confirmado en compras, el libro de stock,
     * clientes o proveedores.
     */
    fun observeAvailability(period: ReportPeriod): Flow<SummaryAvailability>
}
