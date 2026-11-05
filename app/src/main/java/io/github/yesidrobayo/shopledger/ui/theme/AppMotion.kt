package io.github.yesidrobayo.shopledger.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Duraciones y curvas del movimiento. Rápido y sobrio: el movimiento confirma lo que pasó, nunca
 * hace esperar a quien tiene gente en el mostrador. Con la escala de animación del sistema en cero,
 * Compose termina cada animación en el siguiente cuadro.
 */
object AppMotion {
    /** Respuesta al toque, cambio de color. */
    const val FAST = 100

    /** Cambio de dígitos, aparición de iconos y cambio de pestaña. */
    const val SHORT = 150

    /** Paneles, chips y avisos. */
    const val MEDIUM = 250

    /** Transición entre pantallas. */
    const val LONG = 350

    /** Entrada y cambio de gráficas. */
    const val CHART = 450

    /** El esqueleto solo aparece si la carga pasa de este tiempo: los datos son locales. */
    const val SKELETON_DELAY = 200L

    /** Un ciclo del brillo del esqueleto. */
    const val SKELETON_CYCLE = 1_200

    /** La curva por defecto. */
    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Lo que entra. */
    val EnterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Lo que sale, a la mitad del tiempo de su entrada. */
    val ExitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Rebote de la tarjeta de un producto al agregarlo a la venta. */
    val TactileSpring: SpringSpec<Float> =
        spring(dampingRatio = TACTILE_DAMPING, stiffness = Spring.StiffnessMedium)

    /** Hasta dónde se encoge la tarjeta al tocarla: lo justo para sentir el toque. */
    const val TACTILE_SCALE = 0.97f

    /** Espera antes de repetir el toque sostenido del selector de cantidad. */
    const val REPEAT_DELAY = 400L

    /** Intervalo de la repetición del toque sostenido. */
    const val REPEAT_INTERVAL = 120L

    private const val TACTILE_DAMPING = 0.7f
}
