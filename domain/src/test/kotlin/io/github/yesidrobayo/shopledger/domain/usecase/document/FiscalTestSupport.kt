package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.domain.model.document.SaleFiscalMark
import io.github.yesidrobayo.shopledger.domain.port.SaleFiscalMarkRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Una venta de prueba: su estado, fecha, total y cliente. Datos inventados. */
internal data class FakeSale(
    val id: Long,
    val date: BusinessDate,
    val total: Money,
    val status: DocumentStatus = DocumentStatus.ACTIVE,
    val customerName: String? = null,
    val hasReturn: Boolean = false,
)

/** El orden de la lista: por fecha de negocio y número, de la más nueva a la más antigua. */
private val NEWEST_FIRST: Comparator<FiscalSale> =
    compareByDescending<FiscalSale> { it.businessDate }.thenByDescending { it.saleId }

/** Lo que guarda el repositorio falso: las ventas y sus marcas, con la fecha de cada cambio. */
internal data class FiscalTables(
    val sales: Map<Long, FakeSale> = emptyMap(),
    val marks: Map<Long, SaleFiscalMark> = emptyMap(),
    val updatedAt: Map<Long, Instant> = emptyMap(),
)

/**
 * Las marcas en memoria, con lo que hace la base: una marca por venta, la venta intacta y una
 * falla inyectable en la escritura número [failAtWrite] de cada transacción.
 */
internal class FakeSaleFiscalMarkRepository : SaleFiscalMarkRepository {
    val tables = MutableStateFlow(FiscalTables())
    var failAtWrite: Int? = null
    var writes: Int = 0

    fun seed(vararg sales: FakeSale) {
        tables.value = tables.value.copy(sales = tables.value.sales + sales.associateBy { it.id })
    }

    fun seedMark(mark: SaleFiscalMark) {
        tables.value = tables.value.copy(marks = tables.value.marks + (mark.saleId to mark))
    }

    override fun observeSales(
        period: ReportPeriod,
        status: FiscalMarkStatus,
    ): Flow<List<FiscalSale>> = tables.map { current ->
        current.sales.values
            .filter { it.status == DocumentStatus.ACTIVE && it.date in period }
            .map { fiscalSale(it, current.marks[it.id]) }
            .filter { it.status == status }
            .sortedWith(NEWEST_FIRST)
    }

    override fun observeSale(saleId: Long): Flow<FiscalSale?> = tables.map { current ->
        current.sales[saleId]
            ?.takeIf { it.status == DocumentStatus.ACTIVE }
            ?.let { fiscalSale(it, current.marks[saleId]) }
    }

    override fun observeHasMarks(): Flow<Boolean> = tables.map { it.marks.isNotEmpty() }

    override fun observeMark(saleId: Long): Flow<SaleFiscalMark?> = tables.map { it.marks[saleId] }

    override suspend fun findMark(saleId: Long): SaleFiscalMark? = tables.value.marks[saleId]

    override suspend fun findSaleStatus(saleId: Long): DocumentStatus? =
        tables.value.sales[saleId]?.status

    override suspend fun insert(mark: SaleFiscalMark, updatedAt: Instant) {
        write()
        check(mark.saleId !in tables.value.marks) { "La venta ya tiene marca" }
        store(mark, updatedAt)
    }

    override suspend fun update(mark: SaleFiscalMark, updatedAt: Instant) {
        write()
        check(mark.saleId in tables.value.marks) { "La venta no tiene marca" }
        store(mark, updatedAt)
    }

    private fun write() {
        writes++
        if (writes == failAtWrite) throw StorageFailureException(StorageFailure.DISK_FULL)
    }

    private fun store(mark: SaleFiscalMark, updatedAt: Instant) {
        val current = tables.value
        tables.value = current.copy(
            marks = current.marks + (mark.saleId to mark),
            updatedAt = current.updatedAt + (mark.saleId to updatedAt),
        )
    }

    private fun fiscalSale(sale: FakeSale, mark: SaleFiscalMark?) = FiscalSale(
        saleId = sale.id,
        businessDate = sale.date,
        total = sale.total,
        customerName = sale.customerName,
        status = mark?.status ?: FiscalMarkStatus.PENDING,
        externalReference = mark?.externalReference,
        hasReturn = sale.hasReturn,
    )
}

/**
 * Una transacción sobre el repositorio falso: guarda una foto al empezar y la repone si se deshace
 * o falla, como la base. Cuenta cómo terminó cada una.
 */
internal class SnapshotTransactionRunner(private val marks: FakeSaleFiscalMarkRepository) :
    TransactionRunner {
    var commits: Int = 0
    var rollbacks: Int = 0

    override suspend fun <T> write(block: suspend () -> TxResult<T>): T {
        val before = marks.tables.value
        marks.writes = 0
        val result = try {
            block()
        } catch (failure: Throwable) {
            marks.tables.value = before
            rollbacks++
            throw failure
        }
        return when (result) {
            is TxResult.Commit -> {
                commits++
                result.value
            }

            is TxResult.CommitUncounted -> result.value

            is TxResult.Rollback -> {
                marks.tables.value = before
                rollbacks++
                result.value
            }
        }
    }

    override suspend fun <T> read(block: suspend () -> T): T = block()
}
