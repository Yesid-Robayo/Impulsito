package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cuántas veces se componen las cifras de una fila de libreta. Al lado del texto, que es lo común
 * en una lista larga, se componen una sola vez: componerlas dos por fila se notaba al desplazar.
 * Debajo del texto se componen otra vez, alineadas a la izquierda.
 */
@RunWith(AndroidJUnit4::class)
class LedgerLineLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun figuresBesideTheTextAreComposedOnce() {
        val composed = mutableListOf<Alignment.Horizontal>()
        rule.setContent {
            Box(Modifier.width(WIDE)) {
                LedgerLineLayout(gap = GAP, leading = { BasicText(NAME) }) { alignment ->
                    SideEffect { composed += alignment }
                    BasicText(SHORT_FIGURE)
                }
            }
        }
        rule.waitForIdle()

        assertEquals(listOf(Alignment.End), composed)
    }

    @Test
    fun figuresBelowTheTextAreComposedAgainAlignedToTheStart() {
        val composed = mutableListOf<Alignment.Horizontal>()
        rule.setContent {
            Box(Modifier.width(NARROW)) {
                LedgerLineLayout(gap = GAP, leading = { BasicText(NAME) }) { alignment ->
                    SideEffect { composed += alignment }
                    BasicText(LONG_FIGURE)
                }
            }
        }
        rule.waitForIdle()

        assertEquals(listOf(Alignment.End, Alignment.Start), composed)
    }

    private companion object {
        val WIDE = 360.dp
        val NARROW = 120.dp
        val GAP = 8.dp
        const val NAME = "Arroz clásico"
        const val SHORT_FIGURE = "12"
        const val LONG_FIGURE = "1234567890123"
    }
}
