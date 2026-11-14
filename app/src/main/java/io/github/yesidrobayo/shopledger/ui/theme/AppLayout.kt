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

    /** Icono solo sobre el héroe a sangre, como el candado de la pantalla del bloqueo. */
    val heroIcon: Dp = 48.dp

    /** Ancho de una casilla del código de recuperación: cuatro cifras tabulares y su relleno. */
    val recoveryCodeGroup: Dp = 96.dp

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

    /** Icono en línea con texto, como el de un aviso o el de un error de campo. */
    val iconInline: Dp = 20.dp

    /** Icono pequeño dentro de una etiqueta de estado o de un chip de método de pago. */
    val iconTag: Dp = 14.dp

    /** Alto de una etiqueta de estado. */
    val statusTag: Dp = 24.dp

    /** Alto visible de un chip de opciones, dentro de su área táctil de 48 dp. */
    val compactChip: Dp = 40.dp

    /** Alto de un campo, de la búsqueda y de los chips de métodos de pago. */
    val field: Dp = 56.dp

    /** Alto mínimo de una fila de lista con dos líneas. */
    val listRow: Dp = 72.dp

    /** Alto mínimo de una fila de lista con una línea. */
    val listRowSingle: Dp = 56.dp

    /** Alto mínimo de la tarjeta de un producto en la venta. */
    val saleTile: Dp = 116.dp

    /** Ancho mínimo del valor entre los dos botones del selector de cantidad. */
    val stepperValue: Dp = 40.dp

    /** Grosor de la barra de un ranking. */
    val rankingBar: Dp = 6.dp

    /** Borde de lo que está agregado o tiene el foco. */
    val focusBorder: Dp = 2.dp

    /** Borde de un chip seleccionado. */
    val selectedBorder: Dp = 1.5.dp

    /** Grosor del indicador de trabajo dentro de un botón. */
    val progressStroke: Dp = 2.dp

    /** Ancho de la muestra de una paleta en el selector de colores. */
    val paletteSwatchWidth: Dp = 56.dp

    /** Alto de la muestra de una paleta en el selector de colores. */
    val paletteSwatchHeight: Dp = 40.dp

    /** Ancho de una hoja inferior en tableta, cuando pasa a diálogo lateral. */
    val sideSheet: Dp = 400.dp

    /** Parte de la pantalla que ocupa como máximo una hoja inferior. */
    val sheetMaxFraction: Float = 0.9f

    /** Alto del área de una gráfica, sin sus ejes. */
    val chartArea: Dp = 200.dp

    /** Alto del área de una gráfica en una ventana ancha. */
    val chartAreaExpanded: Dp = 280.dp

    /** Ancho de una columna de la gráfica de una semana: siete caben con aire. */
    val chartColumnWeek: Dp = 14.dp

    /** Ancho de una columna de la gráfica de un mes: hasta 31 en un teléfono angosto. */
    val chartColumnMonth: Dp = 6.dp

    /** Punta redondeada de una columna de la semana. La base queda recta. */
    val chartColumnTipWeek: Dp = 4.dp

    /** Punta redondeada de una columna del mes. */
    val chartColumnTipMonth: Dp = 3.dp

    /** Ancho de la ventana del escáner, la parte de la cámara sin velo. */
    val scannerWindowWidth: Dp = 280.dp

    /** Alto de la ventana del escáner: la forma de un código de barras. */
    val scannerWindowHeight: Dp = 160.dp

    /** El ancho máximo de `LabelPreview`: más ancha, ya no se parece a una etiqueta en la mano. */
    val labelPreviewMaxWidth: Dp = 360.dp

    /** Ancho mínimo al que se achica la ventana del escáner con la letra grande. */
    val scannerWindowMinWidth: Dp = 160.dp

    /** Grosor de las esquinas de la ventana del escáner. */
    val scannerCornerStroke: Dp = 3.dp

    /** Ancho del panel del escáner junto a la cámara, en una ventana horizontal ancha. */
    val scannerSidePanel: Dp = 360.dp

    /**
     * El ancho máximo de `ReceiptPreview`: la columna del comprobante con su margen, a un punto
     * por dp. Más ancha, el papel se agranda sin mostrar nada más.
     */
    val receiptPreviewMaxWidth: Dp = 408.dp
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
