package io.github.yesidrobayo.shopledger.ui.theme

import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Elegir una paleta: se ve antes de guardarse, guarda solo la paleta y nunca el tema, y si no se
 * guarda vuelve la anterior, sin pisar otra elegida después. Lo ya elegido no escribe nada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaletteChooserTest {
    private var shown: Appearance? = Appearance(ColorPalette.INDIGO_MUSTARD, ThemeMode.DARK)
    private val saved = mutableListOf<PreferenceChange>()

    private fun chooser(save: suspend (PreferenceChange) -> ChangePreferenceResult) =
        PaletteChooser(
            shown = { shown },
            show = { shown = it },
            save = { change ->
                saved += change
                save(change)
            },
        )

    @Test
    fun `the palette shows at once and only the palette is saved`() = runTest {
        val gate = CompletableDeferred<ChangePreferenceResult>()
        val chooser = chooser { gate.await() }

        var result: PaletteChoice? = null
        launch { result = chooser.choose(ColorPalette.FOREST_HONEY) }
        advanceUntilIdle()

        // Aún sin guardar, ya se ve, y el tema sigue oscuro.
        assertEquals(Appearance(ColorPalette.FOREST_HONEY, ThemeMode.DARK), shown)
        gate.complete(ChangePreferenceResult.Saved)
        advanceUntilIdle()

        assertEquals(PaletteChoice.SAVED, result)
        assertEquals(
            listOf<PreferenceChange>(PreferenceChange.Palette(ColorPalette.FOREST_HONEY)),
            saved,
        )
    }

    @Test
    fun `a failed save brings back the previous palette`() = runTest {
        val chooser = chooser { ChangePreferenceResult.Failed(StorageFailure.IO_ERROR) }

        val result = chooser.choose(ColorPalette.PLUM_CORAL)

        assertEquals(PaletteChoice.NOT_SAVED, result)
        assertEquals(Appearance(ColorPalette.INDIGO_MUSTARD, ThemeMode.DARK), shown)
    }

    @Test
    fun `a late failure does not undo a later choice`() = runTest {
        val first = CompletableDeferred<ChangePreferenceResult>()
        val chooser =
            chooser { change ->
                if ((change as PreferenceChange.Palette).palette == ColorPalette.PLUM_CORAL) {
                    first.await()
                } else {
                    ChangePreferenceResult.Saved
                }
            }

        launch { chooser.choose(ColorPalette.PLUM_CORAL) }
        advanceUntilIdle()
        chooser.choose(ColorPalette.GARNET_HONEY)
        first.complete(ChangePreferenceResult.Failed(StorageFailure.IO_ERROR))
        advanceUntilIdle()

        assertEquals(ColorPalette.GARNET_HONEY, shown?.palette)
    }

    @Test
    fun `the palette already shown writes nothing`() = runTest {
        val chooser = chooser { ChangePreferenceResult.Saved }

        assertEquals(PaletteChoice.SAVED, chooser.choose(ColorPalette.INDIGO_MUSTARD))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `before the settings are read nothing changes`() = runTest {
        shown = null
        val chooser = chooser { ChangePreferenceResult.Saved }

        assertEquals(PaletteChoice.NOT_SAVED, chooser.choose(ColorPalette.FOREST_HONEY))
        assertTrue(saved.isEmpty())
    }
}
