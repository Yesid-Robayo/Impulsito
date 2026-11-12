package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los desgloses de un período para las estadísticas: qué productos se vendieron, con qué métodos se
 * cobró, en qué se gastó y qué avisos explican las cifras. Solo lee, y cada lectura vuelve a emitir
 * con cada cambio confirmado en lo que suma.
 *
 * Cada documento cuenta en su propia fecha de negocio, y solo si está activo.
 */
interface PeriodBreakdownRepository {
    /** Lo vendido por producto en [period], con las variantes en su padre, en cualquier orden. */
    fun observeProductSales(period: ReportPeriod): Flow<List<ProductSales>>

    /**
     * Lo cobrado por método en [period], de mayor a menor. Una venta con dos métodos cuenta en los
     * dos, cada uno por su monto, y la suma de todos es lo que se cobró en las ventas.
     */
    fun observePaymentMethods(period: ReportPeriod): Flow<List<PaymentMethodTotal>>

    /** Lo gastado por categoría en [period], de mayor a menor. */
    fun observeExpenseCategories(period: ReportPeriod): Flow<List<ExpenseCategoryTotal>>

    /**
     * Los conteos de los avisos de [period]. [periodEnd] es el instante en que termina, el comienzo
     * del día siguiente en la zona del negocio: lo anulado o registrado desde ahí cambió un período
     * que ya había terminado.
     */
    fun observeNoticeCounts(period: ReportPeriod, periodEnd: Instant): Flow<PeriodNoticeCounts>
}
