package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayCollection
import kotlinx.coroutines.flow.Flow

/** Lo vendido y lo cobrado por día, y si el negocio vende a crédito. Solo lee. */
interface CollectionReportRepository {
    /**
     * Lo vendido y lo cobrado de cada día de [period] con alguna venta o abono, del más antiguo al
     * más nuevo, de nuevo con cada cambio confirmado. Una venta cuenta en su fecha, con sus pagos;
     * un abono, en la suya. Una venta anulada y un abono anulado no cuentan.
     */
    fun observeDaily(period: ReportPeriod): Flow<List<DayCollection>>

    /**
     * Si el libro de clientes tiene alguna fila: solo entonces hay algo cobrado después de vender, y
     * `Cobrado` se ofrece aparte. Quien no vende a crédito no ve ningún paso más.
     */
    fun observeOnCredit(): Flow<Boolean>
}
