package io.github.yesidrobayo.shopledger.ui.stats

import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La guía de la gráfica traducida a días: la `x` que avisa Vico es la posición del día en la serie,
 * y al ocultarse no queda ninguno. Días inventados.
 */
class DaySelectionTest {
    private val points =
        (1..7).map { day ->
            DayPoint(BusinessDate(LocalDate(2026, 11, day)), Money(day * 1_000L), Money.ZERO)
        }
    private val selected = mutableListOf<BusinessDate?>()
    private val listener = daySelection(points) { selected += it }
    private val marker = object : CartesianMarker {}

    @Test
    fun `the guide over a bar selects its day and moving it selects the next`() {
        listener.onShown(marker, listOf(target(2.0)))
        listener.onUpdated(marker, listOf(target(6.0)))

        assertEquals(listOf(date(3), date(7)), selected)
    }

    @Test
    fun `hiding the guide releases the day`() {
        listener.onShown(marker, listOf(target(0.0)))
        listener.onHidden(marker)

        assertEquals(listOf(date(1), null), selected)
    }

    @Test
    fun `a guide without targets or outside the series selects nothing`() {
        listener.onShown(marker, emptyList())
        listener.onShown(marker, listOf(target(9.0)))

        assertEquals(listOf<BusinessDate?>(null), selected)
    }

    private fun target(x: Double) = object : CartesianMarker.Target {
        override val x: Double = x
        override val canvasX: Float = 0f
    }

    private fun date(day: Int) = BusinessDate(LocalDate(2026, 11, day))
}
