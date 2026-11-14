package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.document.FiscalSaleRow
import io.github.yesidrobayo.shopledger.data.local.document.SaleFiscalMarkEntity
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.domain.model.document.SaleFiscalMark
import io.github.yesidrobayo.shopledger.domain.port.SaleFiscalMarkRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las marcas del puente fiscal sobre `sale_fiscal_marks`. Nunca escribe en `sales`: la guarda de
 * una venta solo admite su anulación, y marcarla no es cambiarla.
 */
internal class RoomSaleFiscalMarkRepository(database: AppDatabase) : SaleFiscalMarkRepository {
    private val marks = database.saleFiscalMarkDao()
    private val writes = RepositoryWrites(database)

    override fun observeSales(
        period: ReportPeriod,
        status: FiscalMarkStatus,
    ): Flow<List<FiscalSale>> = marks
        .observeSales(period.storedStart, period.storedEnd, status.name)
        .map { rows -> rows.map(::toFiscalSale) }
        .distinctUntilChanged()

    override fun observeSale(saleId: Long): Flow<FiscalSale?> = marks
        .observeSale(saleId)
        .map { row -> row?.let(::toFiscalSale) }
        .distinctUntilChanged()

    override fun observeHasMarks(): Flow<Boolean> = marks.observeAny().distinctUntilChanged()

    override fun observeMark(saleId: Long): Flow<SaleFiscalMark?> = marks
        .observeBySale(saleId)
        .map { row -> row?.let(::toMark) }
        .distinctUntilChanged()

    override suspend fun findMark(saleId: Long): SaleFiscalMark? =
        marks.findBySale(saleId)?.let(::toMark)

    override suspend fun findSaleStatus(saleId: Long): DocumentStatus? =
        marks.findSaleStatus(saleId)?.let { storedEnum<DocumentStatus>(it) }

    override suspend fun insert(mark: SaleFiscalMark, updatedAt: Instant) {
        writes.inTransaction {
            marks.insert(
                SaleFiscalMarkEntity(
                    saleId = mark.saleId,
                    status = mark.status.name,
                    externalReference = mark.externalReference,
                    recordedAt = mark.recordedAt?.toStoredMillis(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    override suspend fun update(mark: SaleFiscalMark, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                marks.update(
                    saleId = mark.saleId,
                    status = mark.status.name,
                    externalReference = mark.externalReference,
                    recordedAt = mark.recordedAt?.toStoredMillis(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    private fun toFiscalSale(row: FiscalSaleRow) = FiscalSale(
        saleId = row.saleId,
        businessDate = storedDate(row.businessDate),
        total = Money(row.total),
        customerName = row.customerName,
        status = row.status?.let { storedEnum<FiscalMarkStatus>(it) } ?: FiscalMarkStatus.PENDING,
        externalReference = row.externalReference,
        hasReturn = row.hasReturn.toFlag(),
    )

    private fun toMark(row: SaleFiscalMarkEntity): SaleFiscalMark {
        val status = storedEnum<FiscalMarkStatus>(row.status)
        // La guarda exige la fecha con `RECORDED`, pero no la prohíbe con otra marca: una fila así
        // vino de fuera de la app, y se lee sin esa fecha en vez de romper la pantalla.
        val recorded = status == FiscalMarkStatus.RECORDED
        val recordedAt = row.recordedAt?.toInstant()?.takeIf { recorded }
        if (recorded && recordedAt == null) brokenRow()
        return SaleFiscalMark(row.saleId, status, row.externalReference, recordedAt)
    }
}
