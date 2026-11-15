package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.report.MonthGoals
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.YearMonth

/**
 * Las metas de cada mes que escribe la persona, una fila por mes como mucho, y su límite de
 * ingresos, que vive en los ajustes del negocio.
 *
 * Las escrituras solo corren dentro de la transacción del caso de uso, y cuentan como un cambio
 * del negocio: viajan en la siguiente copia.
 */
interface GoalRepository {
    /** Las metas de [month], de nuevo con cada cambio. `null` si el mes no tiene metas. */
    fun observe(month: YearMonth): Flow<MonthGoals?>

    /** Las metas de [month], o `null` si no tiene. */
    suspend fun find(month: YearMonth): MonthGoals?

    /**
     * Guarda [goals]: crea la fila del mes si no tiene, o cambia la que tiene. Al menos una de las
     * dos metas tiene valor; para quitarlas está [remove].
     */
    suspend fun save(goals: MonthGoals, updatedAt: Instant)

    /** Quita las metas de [month]. Nada depende de ellas, así que la fila se borra. */
    suspend fun remove(month: YearMonth)

    /**
     * Guarda el límite de ingresos que escribió la persona, con su monto y sus meses juntos, o lo
     * quita con `null`.
     */
    suspend fun saveRevenueLimit(limit: RevenueLimit?, updatedAt: Instant)
}
