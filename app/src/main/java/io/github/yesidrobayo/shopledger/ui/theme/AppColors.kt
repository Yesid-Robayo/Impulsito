package io.github.yesidrobayo.shopledger.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Los colores que Material 3 no tiene y la app necesita: el relleno de marca, el héroe con su
 * acento, la tarjeta, los estados del dinero y del respaldo, y las series de las gráficas.
 *
 * Se leen con `AppTheme.colors`. Los del respaldo repiten hoy algunos valores de la ganancia, la
 * información o la pérdida, pero son tokens aparte: una copia al día y una ganancia significan
 * cosas distintas, y deben poder cambiar por separado.
 */
@Immutable
data class AppColors(
    /** Relleno del botón principal y de Vender. En oscuro sigue siendo un color pleno, no un tono lavado. */
    val brandFill: Color,
    val onBrandFill: Color,
    /** Fondo del héroe: un solo bloque por pantalla. */
    val heroContainer: Color,
    /** Cifra y texto fuerte sobre el héroe. */
    val onHero: Color,
    /** Etiquetas sobre el héroe. */
    val onHeroVariant: Color,
    /** El acento: la cifra secundaria del héroe, y solo ahí. Fuera del héroe deja de ser joya. */
    val heroAccent: Color,
    /** Filete decorativo dentro del héroe. */
    val heroDivider: Color,
    /** Papel algo más claro que el fondo, para tarjetas, campos y chips. */
    val cardSurface: Color,
    /** Filo de 1 dp de la tarjeta. Decorativo. */
    val cardBorder: Color,
    val profit: Color,
    val profitContainer: Color,
    val onProfitContainer: Color,
    /** Pérdida, agotado o stock negativo. Nunca se confunde con la marca. */
    val loss: Color,
    val lossContainer: Color,
    val onLossContainer: Color,
    /** Por agotarse y avisos. */
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Copia al día. Nunca con la nube del teléfono, que solo recibe la copia sin confirmarla. */
    val backupOk: Color,
    val backupOkContainer: Color,
    val onBackupOkContainer: Color,
    /** Cambios sin respaldar, subiendo o sin internet. */
    val backupPending: Color,
    val backupPendingContainer: Color,
    val onBackupPendingContainer: Color,
    /** Respaldo fallido, permiso perdido o conflicto. */
    val backupError: Color,
    val backupErrorContainer: Color,
    val onBackupErrorContainer: Color,
    /** Serie 1 de las gráficas y barras de ranking. */
    val chartBar: Color,
    val chartBarMuted: Color,
    val chartBarSelected: Color,
    val chartSeries2: Color,
    val chartSeries3: Color,
    val chartProfit: Color,
    val chartLoss: Color,
    /** Guías y eje de las gráficas. Decorativo. */
    val chartGrid: Color,
)

/**
 * Los colores de la paleta vigente. No tiene valor por defecto: un composable fuera de `AppTheme`
 * fallaría en vez de pintarse con colores de plantilla.
 */
val LocalAppColors =
    staticCompositionLocalOf<AppColors> {
        error("Falta AppTheme: los colores salen de la paleta elegida")
    }
