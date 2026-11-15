package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexView
import io.github.yesidrobayo.shopledger.domain.model.report.RealComparison
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceIndexRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodComparison
import io.github.yesidrobayo.shopledger.domain.rule.report.PeriodMetricsCalculator
import io.github.yesidrobayo.shopledger.domain.rule.report.RealTermsRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.stamp
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.YearMonth

// El índice de precios que escribe o importa la persona, y la comparación en términos reales que
// sale de él. Ningún valor se descarga ni se supone.

/**
 * El índice para su pantalla: los meses del más reciente al más antiguo, y los que faltan entre el
 * primero y el último. Vuelve a emitir con cada cambio.
 */
class ObservePriceIndexUseCase(private val index: PriceIndexRepository) {
    operator fun invoke(): Flow<PriceIndexView> = index.observeEntries().map { entries ->
        PriceIndexView(
            entries = entries.sortedByDescending { it.month },
            missing = RealTermsRules.missingBetween(entries.map { it.month }),
        )
    }.distinctUntilChanged()
}

/** Resultado de [SavePriceIndexMonthUseCase]. */
sealed interface SavePriceIndexResult {
    /** Quedó guardado. */
    data object Saved : SavePriceIndexResult

    /** El mes ya tiene un valor y se estaba agregando uno nuevo. No cambió nada. */
    data object MonthTaken : SavePriceIndexResult

    /** El mes es posterior al de hoy: el cambio de un mes se escribe cuando ya empezó. */
    data object FutureMonth : SavePriceIndexResult

    /** El cambio es de `−100 %` o menos, o pasa del tope. No cambió nada. */
    data object ChangeOutOfRange : SavePriceIndexResult

    /** La fuente pasa de [RealTermsRules.NOTE_MAX] caracteres. No cambió nada. */
    data object NoteTooLong : SavePriceIndexResult

    /** El teléfono no conoce la zona del negocio: no se sabe qué mes es hoy. */
    data object UnknownTimeZone : SavePriceIndexResult

    /** La base no pudo escribir, por [failure]. No cambió nada. */
    data class Failed(val failure: StorageFailure) : SavePriceIndexResult
}

/**
 * Guarda el cambio de precios de un mes, escrito por la persona, en su propia transacción. Es un
 * dato del negocio y viaja en la copia: cuenta como cambio sin respaldar. Agregar un mes que ya
 * tiene valor se rechaza; editar uno cambia su valor y su fuente. Guardar lo mismo no escribe.
 */
class SavePriceIndexMonthUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val index: PriceIndexRepository,
    private val clock: Clock,
) {
    private val calendar = BusinessCalendarSource(settings)

    /**
     * Guarda [change] y [note] en [month]. Con [editing], el mes ya existe y se cambia; sin él, se
     * agrega.
     */
    suspend operator fun invoke(
        month: YearMonth,
        change: BasisPoints,
        note: String?,
        editing: Boolean,
    ): SavePriceIndexResult {
        if (!RealTermsRules.isValidChange(change)) return SavePriceIndexResult.ChangeOutOfRange
        val trimmed = note?.trim()?.takeIf { it.isNotEmpty() }
        if ((trimmed?.length ?: 0) > RealTermsRules.NOTE_MAX) {
            return SavePriceIndexResult.NoteTooLong
        }
        val entry = PriceIndexEntry(month, change, trimmed)
        return try {
            transactions.write { save(entry, editing) }
        } catch (failure: StorageFailureException) {
            SavePriceIndexResult.Failed(failure.failure)
        }
    }

    private suspend fun save(
        entry: PriceIndexEntry,
        editing: Boolean,
    ): TxResult<SavePriceIndexResult> {
        val now = clock.now()
        val today = calendar.stamp(now)?.businessDate
            ?: return TxResult.Rollback(SavePriceIndexResult.UnknownTimeZone)
        if (entry.month > yearMonthOf(today)) {
            return TxResult.Rollback(SavePriceIndexResult.FutureMonth)
        }
        val current = index.observeEntries().first().firstOrNull { it.month == entry.month }
        return when {
            current == null -> {
                index.insertEntry(entry, now)
                TxResult.Commit(SavePriceIndexResult.Saved)
            }

            !editing -> TxResult.Rollback(SavePriceIndexResult.MonthTaken)

            current == entry -> TxResult.Rollback(SavePriceIndexResult.Saved)

            else -> {
                index.updateEntry(entry, now)
                TxResult.Commit(SavePriceIndexResult.Saved)
            }
        }
    }
}

/** Resultado de [DeletePriceIndexMonthUseCase]. */
sealed interface DeletePriceIndexResult {
    /** El mes ya no tiene valor: la comparación real deja de cubrirlo. */
    data object Deleted : DeletePriceIndexResult

    /** La base no pudo escribir, por [failure]. No cambió nada. */
    data class Failed(val failure: StorageFailure) : DeletePriceIndexResult
}

/**
 * Quita el cambio de un mes, en su propia transacción. Nada depende de él: la comparación real de
 * ese mes vuelve a decir que falta. Si el mes ya no tenía valor, no escribe nada.
 */
class DeletePriceIndexMonthUseCase(
    private val transactions: TransactionRunner,
    private val index: PriceIndexRepository,
) {
    suspend operator fun invoke(month: YearMonth): DeletePriceIndexResult = try {
        transactions.write {
            if (index.delete(month.toString())) {
                TxResult.Commit(DeletePriceIndexResult.Deleted)
            } else {
                TxResult.Rollback(DeletePriceIndexResult.Deleted)
            }
        }
    } catch (failure: StorageFailureException) {
        DeletePriceIndexResult.Failed(failure.failure)
    }
}

/**
 * Lo vendido de un mes frente a su comparable, en moneda corriente y a precios del mes del
 * comparable, con el índice escrito por la persona. Solo un mes se compara así, porque el índice
 * es mensual: un día, una semana o un rango propio emiten `null`. Vuelve a emitir con cada cambio
 * en ventas, devoluciones o el índice.
 */
class ObserveRealComparisonUseCase(
    private val reports: PeriodReportRepository,
    private val index: PriceIndexRepository,
) {
    operator fun invoke(period: ReportPeriod, today: BusinessDate): Flow<RealComparison?> {
        val month = period as? ReportPeriod.Month ?: return flowOf(null)
        val comparable = PeriodComparison.comparableOf(month, today) ?: return flowOf(null)
        return combine(
            reports.observeTotals(month),
            reports.observeTotals(comparable),
            index.observeEntries(),
        ) { now, before, entries ->
            val nominal = PeriodMetricsCalculator.of(now).valueOrNull()?.profit?.netSales
            val base = PeriodMetricsCalculator.of(before).valueOrNull()?.profit?.netSales
            if (nominal == null || base == null) {
                null
            } else {
                RealComparison(
                    period = month,
                    comparedTo = comparable,
                    base = base,
                    nominal = nominal,
                    nominalVariation = PeriodComparison.variation(nominal, base),
                    real =
                        RealTermsRules.compare(
                            baseValue = base,
                            nominal = nominal,
                            base = yearMonthOf(comparable.start),
                            month = month.yearMonth,
                            index = entries.associate { it.month to it.change },
                        ),
                )
            }
        }.distinctUntilChanged()
    }
}

/** El mes de [date]. */
internal fun yearMonthOf(date: BusinessDate): YearMonth = YearMonth(date.date.year, date.date.month)
