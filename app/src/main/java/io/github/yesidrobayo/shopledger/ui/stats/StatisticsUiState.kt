package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodMetrics
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate

/** Lo que muestra `Números`: cargando, un error, sin zona, sin datos o las cifras de un período. */
sealed interface StatisticsUiState {
    /** Todavía no llegan las cifras: esqueleto, solo después de un instante. */
    data object Loading : StatisticsUiState

    /** No se pudo leer la base: se ofrece reintentar. */
    data object ReadError : StatisticsUiState

    /**
     * Este teléfono no conoce la zona guardada del negocio: sin zona no se sabe qué día es, y no se
     * supone otra.
     */
    data object UnknownZone : StatisticsUiState

    /** El negocio todavía no tiene ventas ni gastos: los números aparecen con la primera venta. */
    data object NoData : StatisticsUiState

    /**
     * Las cifras de [period].
     *
     * @param metrics las métricas del período, o `null` si una cifra no cabe en `Long`.
     * @param series un punto por día para la gráfica, o `null` en un día, que es una cifra y no
     *   lleva gráfica, o si una cifra no cabe.
     * @param selectedDay el día tocado en la gráfica, cuyo valor se escribe debajo.
     * @param tax el impuesto estimado, o `null` si el negocio no cobra impuesto.
     */
    @Immutable
    data class Content(
        val kind: PeriodKind,
        val period: ReportPeriod,
        val today: BusinessDate,
        val canGoPrevious: Boolean,
        val canGoNext: Boolean,
        val metric: ChartMetric,
        val metrics: PeriodMetrics?,
        val series: List<DayPoint>?,
        val selectedDay: BusinessDate?,
        val showList: Boolean,
        val tax: TaxEstimate?,
    ) : StatisticsUiState
}

/** La serie de la gráfica y la cifra grande: una sola a la vez, nunca dos escalas juntas. */
enum class ChartMetric {
    /** Lo vendido sin impuesto. */
    SALES,

    /** La ganancia en ventas: un gasto pagado un día no hunde ese día. */
    PROFIT,
}

/** Lo que la persona hace en `Números`. */
sealed interface StatisticsAction {
    /** Elige día, semana o mes. */
    data class SelectKind(val kind: PeriodKind) : StatisticsAction

    /** Va al período anterior. */
    data object Previous : StatisticsAction

    /** Va al período siguiente. */
    data object Next : StatisticsAction

    /** Cambia la serie de la gráfica y la cifra grande. */
    data class SelectMetric(val metric: ChartMetric) : StatisticsAction

    /** Toca una barra, o la misma otra vez para soltarla. */
    data class SelectDay(val date: BusinessDate?) : StatisticsAction

    /** Muestra u oculta la gráfica como lista. */
    data object ToggleList : StatisticsAction

    /** Vuelve a leer tras un error. */
    data object Retry : StatisticsAction

    /** Abre la venta, desde el estado sin datos. */
    data object NewSale : StatisticsAction

    /** Abre la elección de zona, cuando la guardada no existe en este teléfono. */
    data object ChooseZone : StatisticsAction
}
