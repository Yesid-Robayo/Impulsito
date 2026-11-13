package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodMetrics
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.usecase.report.ComparedPeriod
import io.github.yesidrobayo.shopledger.domain.usecase.report.PeriodBreakdown
import io.github.yesidrobayo.shopledger.domain.usecase.report.SoldAndCollectedView
import kotlinx.datetime.DayOfWeek

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
     * @param kind día, semana o mes, o `null` en un rango propio.
     * @param comparison la variación frente al período comparable; `null` en un rango propio, que
     *   no se compara, o mientras llega.
     * @param breakdown los destacados, los desgloses y los avisos, o `null` mientras llegan.
     * @param units las unidades por id, para nombrar las cantidades de los más vendidos.
     * @param ranking qué ranking de los más vendidos se muestra.
     * @param choosingRange si está abierta la elección de un rango de fechas.
     * @param weekStartDay el primer día de las semanas del negocio, para el calendario del rango.
     * @param otherIncome los otros ingresos del período, aparte de la ganancia, o `null` sin ninguno.
     * @param collection lo vendido y lo cobrado del período, aparte, o `null` si el negocio no vende
     *   a crédito.
     */
    @Immutable
    data class Content(
        val kind: PeriodKind?,
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
        val comparison: ComparedPeriod? = null,
        val breakdown: PeriodBreakdown? = null,
        val units: Map<Long, MeasureUnit> = emptyMap(),
        val ranking: RankingKind = RankingKind.SALES,
        val choosingRange: Boolean = false,
        val weekStartDay: DayOfWeek? = null,
        val otherIncome: Money? = null,
        val collection: SoldAndCollectedView? = null,
    ) : StatisticsUiState {
        /** Las métricas que se ofrecen: `Cobrado` solo si el negocio vende a crédito. */
        val offeredMetrics: List<ChartMetric>
            get() = ChartMetric.entries.filter { it != ChartMetric.COLLECTED || collection != null }
    }
}

/** La serie de la gráfica y la cifra grande: una sola a la vez, nunca dos escalas juntas. */
enum class ChartMetric {
    /** Lo vendido sin impuesto. */
    SALES,

    /**
     * Lo cobrado: pagos al contado y abonos, en la fecha en que entró el dinero. Solo se ofrece si
     * el negocio vende a crédito, y nunca se suma con lo vendido.
     */
    COLLECTED,

    /** La ganancia en ventas: un gasto pagado un día no hunde ese día. */
    PROFIT,
}

/** Por qué se ordena `Lo que más se vendió`. */
enum class RankingKind {
    /** Por lo vendido sin impuesto. */
    SALES,

    /** Por la ganancia en ventas. */
    PROFIT,

    /** Por cantidad, separada por unidad: nunca se suman kilos con unidades. */
    QUANTITY,
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

    /** Elige el orden de los más vendidos. */
    data class SelectRanking(val ranking: RankingKind) : StatisticsAction

    /** Abre la elección de un rango de fechas. */
    data object ChooseRange : StatisticsAction

    /** Cierra la elección del rango sin cambiar nada. */
    data object DismissRange : StatisticsAction

    /** Muestra las cifras del rango entre [from] y [to], las dos incluidas, en cualquier orden. */
    data class ShowRange(val from: BusinessDate, val to: BusinessDate) : StatisticsAction

    /** Vuelve a leer tras un error. */
    data object Retry : StatisticsAction

    /** Abre la venta, desde el estado sin datos. */
    data object NewSale : StatisticsAction

    /** Abre la elección de zona, cuando la guardada no existe en este teléfono. */
    data object ChooseZone : StatisticsAction
}
