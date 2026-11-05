package io.github.yesidrobayo.shopledger.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * La escala de espaciado, de 4 en 4 dp con una sola excepción de 2 dp para ajustes finos. Cada token
 * se llama por su valor, así nadie duda de cuánto mide.
 */
@Immutable
class AppSpacing internal constructor() {
    /** Entre una cifra y su etiqueta. */
    val s2: Dp = 2.dp

    /** Entre un icono y el texto de una etiqueta pequeña. */
    val s4: Dp = 4.dp

    /** Entre chips, o entre un icono y su texto. */
    val s8: Dp = 8.dp

    /** Relleno vertical de filas, y entre tarjetas. */
    val s12: Dp = 12.dp

    /** Margen lateral de la pantalla y relleno de tarjeta. */
    val s16: Dp = 16.dp

    /** Relleno del héroe. */
    val s20: Dp = 20.dp

    /** Entre secciones. */
    val s24: Dp = 24.dp

    /** Antes de un bloque principal. */
    val s32: Dp = 32.dp

    /** Alrededor de un estado vacío. */
    val s40: Dp = 40.dp

    /** Alto mínimo de lo que se toca. */
    val s48: Dp = 48.dp

    /** Reserva superior de un estado vacío. */
    val s64: Dp = 64.dp
}

/**
 * Las medidas fijas del sistema de diseño: barras, botones y áreas táctiles. Son las únicas medidas
 * en dp fuera de la escala de espaciado.
 */
@Immutable
class AppSizes internal constructor() {
    /** Alto mínimo de la barra superior. */
    val topBar: Dp = 64.dp

    /** Alto de la barra inferior, sin contar la barra del sistema. */
    val bottomBar: Dp = 72.dp

    /** Ancho de la casilla central de la barra inferior, donde vive Vender. */
    val bottomBarCenterSlot: Dp = 92.dp

    /** Ancho del botón Vender. */
    val sellButtonWidth: Dp = 76.dp

    /** Alto del botón Vender. */
    val sellButtonHeight: Dp = 60.dp

    /** Cuánto sobresale Vender por encima de la barra inferior. */
    val sellButtonOverhang: Dp = 14.dp

    /** Sombra del botón Vender, el único elemento con sombra de la app. */
    val sellButtonShadow: Dp = 6.dp

    /** Ancho de la píldora que marca la pestaña activa. */
    val tabIndicatorWidth: Dp = 52.dp

    /** Alto de la píldora que marca la pestaña activa. */
    val tabIndicatorHeight: Dp = 28.dp

    /** Alto del botón principal. */
    val primaryButton: Dp = 56.dp

    /** Área táctil mínima. */
    val touchTarget: Dp = 48.dp

    /** Ancho máximo del contenido de lectura: más ancho cansa la vista. */
    val maxContentWidth: Dp = 600.dp

    /** Círculo de fondo del icono de un estado vacío. */
    val emptyStateCircle: Dp = 72.dp

    /** Icono por defecto. */
    val icon: Dp = 24.dp

    /** Icono dentro del círculo de un estado vacío. */
    val emptyStateIcon: Dp = 32.dp

    /** Indicador de trabajo dentro de un botón. */
    val buttonProgress: Dp = 20.dp

    /** Filo de tarjetas y filetes. */
    val hairline: Dp = 1.dp

    /**
     * Las etiquetas de la barra inferior crecen con la letra del sistema solo hasta este factor: cinco
     * etiquetas no caben de otra forma. Presión larga y TalkBack dan la etiqueta completa.
     */
    val bottomBarMaxFontScale: Float = 1.15f

    /** Tamaño mínimo al que bajan las etiquetas de la barra en una pantalla angosta. */
    val bottomBarMinLabel = 10.sp

    /** Tamaño normal de las etiquetas de la barra. */
    val bottomBarMaxLabel = 12.sp
}

/**
 * Formas más redondeadas que las de Material por defecto: se sienten blandas en la mano. Sin
 * píldoras completas en botones de texto, que suenan a app genérica.
 */
val AppShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
