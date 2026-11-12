package io.github.yesidrobayo.shopledger.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Lo que pasó al elegir una paleta. */
enum class PaletteChoice {
    /** Se ve y quedó guardada. */
    SAVED,

    /** No se pudo guardar: vuelve la anterior, y la pantalla lo dice con `Reintentar`. */
    NOT_SAVED,
}

/**
 * Cambia la paleta de toda la app al instante: primero la muestra, en el mismo cuadro, y luego la
 * guarda en su propia columna. No cambia el modo claro u oscuro.
 *
 * Si no se puede guardar, vuelve la anterior: nunca queda en pantalla una paleta distinta de la
 * guardada. Si mientras tanto la persona eligió otra, esa no se toca.
 *
 * @param shown la apariencia en pantalla, o `null` mientras no se leen los ajustes.
 * @param show pone una apariencia en pantalla.
 * @param save guarda una preferencia.
 */
class PaletteChooser(
    private val shown: () -> Appearance?,
    private val show: (Appearance) -> Unit,
    private val save: suspend (PreferenceChange) -> ChangePreferenceResult,
) {
    suspend fun choose(palette: ColorPalette): PaletteChoice {
        val before = shown() ?: return PaletteChoice.NOT_SAVED
        if (before.palette == palette) return PaletteChoice.SAVED
        show(before.copy(palette = palette))
        // Ya está en pantalla: aunque se cierre Ajustes, se termina de guardar o se deshace.
        val result = withContext(NonCancellable) { save(PreferenceChange.Palette(palette)) }
        return when (result) {
            ChangePreferenceResult.Saved -> PaletteChoice.SAVED

            is ChangePreferenceResult.Failed -> {
                // Solo deshace lo suyo: otra elección posterior sigue en pantalla.
                val now = shown()
                if (now?.palette == palette) show(now.copy(palette = before.palette))
                PaletteChoice.NOT_SAVED
            }
        }
    }
}

/**
 * El selector de paletas que da la actividad, o `null` fuera de ella, como en las vistas previas y
 * en el rescate, donde no hay ajustes que guardar.
 */
val LocalPaletteChooser = staticCompositionLocalOf<PaletteChooser?> { null }
