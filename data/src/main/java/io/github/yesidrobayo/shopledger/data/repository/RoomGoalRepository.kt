package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.report.BusinessGoalEntity
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.MonthGoals
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.port.GoalRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.YearMonth
import kotlinx.datetime.number

/**
 * Las metas de cada mes sobre `business_goals`, una fila por mes `AAAA-MM`, y el límite de
 * ingresos sobre la única fila de `app_settings`.
 *
 * Un mes nuevo se inserta y uno que ya tiene metas se actualiza: nunca `INSERT OR REPLACE`, que
 * borraría la fila y escribiría otra. Las escrituras exigen la transacción del caso de uso.
 */
internal class RoomGoalRepository(database: AppDatabase) : GoalRepository {
    private val rows = database.businessGoalDao()
    private val settings = database.appSettingsDao()
    private val writes = RepositoryWrites(database)

    override fun observe(month: YearMonth): Flow<MonthGoals?> = rows
        .observeByMonth(month.toStoredMonth())
        .map { row -> row?.toGoals(month) }
        .distinctUntilChanged()

    override suspend fun find(month: YearMonth): MonthGoals? =
        rows.findByMonth(month.toStoredMonth())?.toGoals(month)

    override suspend fun save(goals: MonthGoals, updatedAt: Instant) {
        writes.inTransaction {
            val month = goals.month.toStoredMonth()
            val at = updatedAt.toStoredMillis()
            if (rows.findByMonth(month) == null) {
                rows.insert(
                    BusinessGoalEntity(
                        month = month,
                        salesGoal = goals.sales?.minor,
                        profitGoal = goals.profit?.minor,
                        updatedAt = at,
                    ),
                )
            } else {
                requireOneRow(
                    rows.update(month, goals.sales?.minor, goals.profit?.minor, at),
                )
            }
        }
    }

    override suspend fun remove(month: YearMonth) {
        writes.inTransaction { rows.delete(month.toStoredMonth()) }
    }

    override suspend fun saveRevenueLimit(limit: RevenueLimit?, updatedAt: Instant) {
        writes.inTransaction {
            requireOneRow(
                settings.updateRevenueLimit(
                    amount = limit?.amount?.minor,
                    months = limit?.months?.toLong(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    /** La fila leída como las metas de [month], que es el mes por el que se buscó. */
    private fun BusinessGoalEntity.toGoals(month: YearMonth): MonthGoals =
        MonthGoals(month, salesGoal?.let(::Money), profitGoal?.let(::Money))
}

/**
 * El texto `AAAA-MM` con que se guarda un mes. Se arma con las cifras, sin el formato del
 * teléfono: con otra región, `String.format` podría escribir cifras de otro sistema.
 */
internal fun YearMonth.toStoredMonth(): String =
    year.toString().padStart(YEAR_DIGITS, '0') + "-" +
        month.number.toString().padStart(MONTH_DIGITS, '0')

private const val YEAR_DIGITS = 4
private const val MONTH_DIGITS = 2
