package io.github.yesidrobayo.shopledger.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: una pantalla recibe su ViewModel armado con los puertos de la base abierta por
 * la compuerta del arranque, y el mismo ViewModel sobrevive a que la pantalla se vuelva a dibujar.
 */
@RunWith(AndroidJUnit4::class)
class AppViewModelTest {
    @get:Rule
    val compose = createComposeRule()

    private class ProbeViewModel(val ports: DataPorts) : ViewModel()

    @Test
    fun theScreenGetsItsViewModelWithTheOpenDatabaseAndKeepsIt() {
        val seen = mutableListOf<ScreenModel<ProbeViewModel>>()
        var created = 0
        val redraw = mutableIntStateOf(0)
        compose.setContent {
            // Leer el contador hace que la pantalla se vuelva a dibujar cuando cambia.
            redraw.intValue
            seen += rememberAppViewModel { dependencies ->
                created++
                ProbeViewModel(dependencies.ports)
            }
        }
        compose.waitUntil(TIMEOUT_MS) { seen.lastOrNull() is ScreenModel.Ready }
        val first = seen.last() as ScreenModel.Ready
        compose.runOnIdle { redraw.intValue++ }
        compose.waitForIdle()

        val second = seen.last() as ScreenModel.Ready
        assertSame(first.viewModel, second.viewModel)
        assertEquals(1, created)
        // Antes de abrir la base no hay ViewModel, y nunca se llega al rescate en una base sana.
        assertTrue(seen.none { it is ScreenModel.Unavailable })
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
