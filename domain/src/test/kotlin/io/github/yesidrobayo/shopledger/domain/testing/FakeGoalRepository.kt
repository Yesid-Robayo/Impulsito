package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.report.MonthGoals
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.port.GoalRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.YearMonth

/**
 * Las metas en memoria, un mes por fila como la tabla real. Cada escritura queda anotada en
 * [writes], y [failNextWrite] hace fallar la siguiente con esa excepción. El límite de ingresos se
 * escribe en [settings], como en la base vive en los ajustes.
 */
internal class FakeGoalRepository(private val settings: FakeAppSettingsRepository? = null) :
    GoalRepository {
    val goals = MutableStateFlow(emptyMap<YearMonth, MonthGoals>())
    val writes = mutableListOf<String>()
    var failNextWrite: Throwable? = null

    override fun observe(month: YearMonth): Flow<MonthGoals?> = goals.map { it[month] }

    override suspend fun find(month: YearMonth): MonthGoals? = goals.value[month]

    override suspend fun save(goals: MonthGoals, updatedAt: Instant) {
        failIfAsked()
        check(goals.sales != null || goals.profit != null) { "Sin metas, la fila se quita" }
        writes += "save ${goals.month}"
        this.goals.value = this.goals.value + (goals.month to goals)
    }

    override suspend fun remove(month: YearMonth) {
        failIfAsked()
        writes += "remove $month"
        goals.value = goals.value - month
    }

    override suspend fun saveRevenueLimit(limit: RevenueLimit?, updatedAt: Instant) {
        failIfAsked()
        writes += "limit $limit"
        val fake = requireNotNull(settings) { "Esta prueba no escribe el límite" }
        val current = fake.settings.value
        fake.settings.value =
            current.copy(notices = current.notices.copy(revenueLimit = limit))
    }

    private fun failIfAsked() {
        failNextWrite?.let {
            failNextWrite = null
            throw it
        }
    }
}
