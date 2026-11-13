package io.github.yesidrobayo.shopledger.ui.stats

import android.animation.ValueAnimator
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppMotion
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** Cuántas marcas tiene el eje de los montos, contando el cero. */
private const val VALUE_TICKS = 4

/** Cada cuántos días lleva número el eje de un mes: 1, 8, 15, 22 y 29. */
private const val MONTH_LABEL_SPACING = 7

/** Hasta cuántas columnas se dibujan como semana, anchas y con el nombre del día. */
private const val WEEK_COLUMNS = 7

/**
 * La gráfica de barras por día de un período: una barra por día, cero en los días sin ventas, sin
 * desplazamiento ni zoom. El valor de la barra tocada no va dentro de la gráfica: se escribe debajo,
 * como texto que TalkBack lee.
 *
 * Vico no expone nada a TalkBack, así que el contenedor lleva [summary], con el total y el rango, y
 * la lista de los mismos días es el camino accesible.
 *
 * Los montos pasan a `Double` solo aquí, para dibujar: ninguna cifra que se lea sale de la gráfica.
 *
 * @param points un punto por día, en orden.
 * @param metric la serie que se dibuja.
 * @param selectedDay el día tocado, con su guía, o `null`.
 * @param onSelectDay el día que se tocó, o `null` si se soltó.
 * @param height el alto del área, que crece en una ventana ancha.
 */
@Composable
fun DailyBarChart(
    points: List<DayPoint>,
    metric: ChartMetric,
    selectedDay: BusinessDate?,
    onSelectDay: (BusinessDate?) -> Unit,
    summary: String,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // El período cabe entero, sin desplazamiento: la separación es el ancho que sobra entre
        // columnas, descontado el eje de los montos.
        val columns = points.size.coerceAtLeast(1)
        val columnWidth =
            if (points.size <= WEEK_COLUMNS) {
                AppTheme.sizes.chartColumnWeek
            } else {
                AppTheme.sizes.chartColumnMonth
            }
        val spacing =
            ((maxWidth - AppTheme.spacing.s48) / columns - columnWidth)
                .coerceAtLeast(AppTheme.spacing.s2)
        ChartContent(points, metric, selectedDay, onSelectDay, summary, height, spacing)
    }
}

@Composable
private fun ChartContent(
    points: List<DayPoint>,
    metric: ChartMetric,
    selectedDay: BusinessDate?,
    onSelectDay: (BusinessDate?) -> Unit,
    summary: String,
    height: Dp,
    spacing: Dp,
) {
    val colors = AppTheme.colors
    val sizes = AppTheme.sizes
    val formats = LocalAppFormats.current
    val discreet = LocalDiscreetMode.current
    val week = points.size <= WEEK_COLUMNS
    val values = remember(points, metric) { points.map { it.valueOf(metric).minor } }
    val selectedIndex = points.indexOfFirst { it.date == selectedDay }.takeIf { it >= 0 }

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        producer.runTransaction {
            columnSeries { series(values.indices.toList(), values) }
        }
    }

    val width = if (week) sizes.chartColumnWeek else sizes.chartColumnMonth
    val tip = if (week) sizes.chartColumnTipWeek else sizes.chartColumnTipMonth
    val up = RoundedCornerShape(topStart = tip, topEnd = tip)
    val down = RoundedCornerShape(bottomStart = tip, bottomEnd = tip)
    val positive = if (metric == ChartMetric.PROFIT) colors.chartProfit else colors.chartBar
    val selectedColor = if (metric ==
        ChartMetric.PROFIT
    ) {
        colors.chartProfit
    } else {
        colors.chartBarSelected
    }
    val othersColor =
        when {
            selectedIndex == null -> positive
            metric == ChartMetric.PROFIT -> colors.chartProfit
            else -> colors.chartBarMuted
        }
    val upColumn = rememberLineComponent(fill = Fill(othersColor), thickness = width, shape = up)
    val upSelected =
        rememberLineComponent(fill = Fill(selectedColor), thickness = width, shape = up)
    // Lo que queda bajo cero es pérdida, con color y dirección, nunca solo con color.
    val downColumn =
        rememberLineComponent(fill = Fill(colors.chartLoss), thickness = width, shape = down)
    val provider =
        remember(upColumn, upSelected, downColumn, selectedIndex) {
            DayColumns(upColumn, upSelected, downColumn, selectedIndex)
        }

    val labelStyle = AppTheme.amounts.caption.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Sin montos en modo discreto: las barras conservan su forma, sin eje de valores.
    val valueLabel =
        rememberAxisLabelComponent(
            style = if (discreet) labelStyle.copy(color = Color.Transparent) else labelStyle,
        )
    val solidGuide =
        rememberLineComponent(fill = Fill(colors.chartGrid), thickness = sizes.hairline)
    val valueFormatter =
        remember(formats.money) {
            CartesianValueFormatter { _, value, _ -> formats.money.compact(Money(value.toLong())) }
        }
    val dayFormatter =
        remember(points, formats.date, week) {
            CartesianValueFormatter { _, value, _ ->
                val point = points.getOrNull(value.toInt())
                when {
                    point == null -> ""
                    week -> formats.date.weekdayShort(point.date.date.dayOfWeek)
                    else -> point.date.date.day.toString()
                }
            }
        }

    // La guía de la barra tocada: una línea sólida, sin globo; el valor va debajo como texto.
    val marker =
        rememberDefaultCartesianMarker(
            label = rememberTextComponent(style = labelStyle.copy(color = Color.Transparent)),
            valueFormatter = remember { DefaultCartesianMarker.ValueFormatter { _, _ -> "" } },
            indicator = null,
            guideline = rememberLineComponent(
                fill = Fill(MaterialTheme.colorScheme.onSurface),
                thickness = sizes.hairline,
            ),
        )
    val select = rememberUpdatedState(onSelectDay)
    val listener = remember(points) { daySelection(points) { select.value(it) } }

    val chart =
        rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = provider,
                columnCollectionSpacing = spacing,
            ),
            startAxis =
                VerticalAxis.rememberStart(
                    line = null,
                    label = valueLabel,
                    valueFormatter = valueFormatter,
                    tick = null,
                    guideline = solidGuide,
                    itemPlacer = remember { VerticalAxis.ItemPlacer.count({ VALUE_TICKS }) },
                ),
            bottomAxis =
                HorizontalAxis.rememberBottom(
                    line = solidGuide,
                    label = rememberAxisLabelComponent(style = labelStyle),
                    valueFormatter = dayFormatter,
                    tick = null,
                    guideline = null,
                    itemPlacer =
                        remember(week) {
                            HorizontalAxis.ItemPlacer.aligned(
                                spacing = { if (week) 1 else MONTH_LABEL_SPACING },
                            )
                        },
                ),
            marker = marker,
            markerVisibilityListener = listener,
            persistentMarkers = { _ -> selectedIndex?.let { marker at it } },
            markerController = CartesianMarkerController.rememberToggleOnTap(),
        )

    Box(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = summary }) {
        CartesianChartHost(
            chart = chart,
            modelProducer = producer,
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            // Lo que la separación no alcanzó a ajustar lo ajusta el zoom: siempre el período entero.
            zoomState =
                rememberVicoZoomState(
                    zoomEnabled = false,
                    initialZoom = Zoom.Content,
                    minZoom = Zoom.Content,
                    maxZoom = Zoom.Content,
                ),
            // Con las animaciones quitadas, la gráfica aparece entera de una vez.
            animationSpec =
                if (ValueAnimator.areAnimatorsEnabled()) {
                    tween(AppMotion.CHART, easing = AppMotion.StandardEasing)
                } else {
                    null
                },
            chartAreaHeight = height,
        )
    }
}

/** El monto de [metric] en un día. */
internal fun DayPoint.valueOf(metric: ChartMetric): Money = when (metric) {
    ChartMetric.SALES -> netSales
    ChartMetric.COLLECTED -> collected
    ChartMetric.PROFIT -> grossProfit
}

/**
 * Las columnas: hacia arriba con la punta redondeada, hacia abajo en pérdida, y la tocada en su
 * color. La base de cada columna queda recta, sobre el cero.
 */
private class DayColumns(
    private val up: LineComponent,
    private val upSelected: LineComponent,
    private val down: LineComponent,
    private val selectedIndex: Int?,
) : ColumnCartesianLayer.ColumnProvider {
    override fun getColumn(
        entry: ColumnCartesianLayerModel.Entry,
        extraStore: ExtraStore,
    ): LineComponent = when {
        entry.y < 0.0 -> down
        entry.x.toInt() == selectedIndex -> upSelected
        else -> up
    }

    override fun getWidestSeriesColumn(seriesIndex: Int, extraStore: ExtraStore): LineComponent = up
}

/**
 * Lo que avisa la guía de Vico, traducido a días: al mostrarse o moverse, el día de la barra bajo
 * la guía; al ocultarse, ninguno. La `x` de cada barra es su posición en [points].
 */
internal fun daySelection(
    points: List<DayPoint>,
    select: (BusinessDate?) -> Unit,
): CartesianMarkerVisibilityListener = object : CartesianMarkerVisibilityListener {
    override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
        targets.firstOrNull()?.let { select(points.getOrNull(it.x.toInt())?.date) }
    }

    override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
        onShown(marker, targets)
    }

    override fun onHidden(marker: CartesianMarker) {
        select(null)
    }
}
