package io.github.yesidrobayo.shopledger.ui

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick

/**
 * Toca [node] cuando ya está quieto en pantalla. Una hoja modal entra animada y sube con el
 * teclado, que se anima con el reloj real, no con el de la prueba: un toque que llega mientras se
 * mueve lo toma la hoja para arrastrarse, o se levanta fuera del botón, y se pierde. En un equipo
 * cargado pasa a veces. La espera no cambia lo que la prueba comprueba: el toque llega una vez.
 */
internal fun ComposeTestRule.clickWhenStill(node: SemanticsNodeInteraction) {
    var last: Rect? = null
    var stillSince = 0L
    waitUntil(STILL_TIMEOUT_MS) {
        val bounds =
            runCatching { node.assertIsDisplayed().fetchSemanticsNode().boundsInWindow }
                .getOrNull()
        val now = SystemClock.uptimeMillis()
        if (bounds == null || bounds != last) stillSince = now
        last = bounds
        bounds != null && now - stillSince >= STILL_FOR_MS
    }
    node.performClick()
}

/** Lo que el nodo debe quedarse quieto: más que la animación del teclado o de la hoja. */
private const val STILL_FOR_MS = 500L

/** Tope de la espera: en un emulador muy cargado la hoja tarda, pero nunca tanto. */
private const val STILL_TIMEOUT_MS = 10_000L
