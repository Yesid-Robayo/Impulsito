package io.github.yesidrobayo.shopledger.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las nueve paletas cumplen, en claro y en oscuro, los contrastes del documento de accesibilidad y
 * las comprobaciones de las series de gráfica. Una paleta que no pasa no se publica.
 *
 * Los pares son una copia de las tablas de ese documento: cada uno nombra sus dos colores y su
 * mínimo, 4,5:1 para texto y 3:1 para componentes con significado. La fórmula es la de WCAG, con el
 * umbral de 0,03928 que usaron las medidas publicadas.
 */
class PaletteContrastTest {
    @Test
    fun theFormulaGivesTheKnownValues() {
        assertEquals(21.0, contrast(Color(0xFF000000), Color(0xFFFFFFFF)), 0.005)
        assertEquals(4.48, contrast(Color(0xFF777777), Color(0xFFFFFFFF)), 0.005)
    }

    @Test
    fun everyPaletteHasItsEntryAndAllItsTokens() {
        assertEquals(ColorPalette.entries.toList(), AppPalettes.keys.toList())
        for ((palette, set) in AppPalettes) {
            for (dark in listOf(false, true)) {
                val tokens = tokensOf(set, dark)
                assertEquals("$palette: 83 tokens", TOKEN_COUNT, tokens.size)
                assertTrue(
                    "$palette: ningún token sin color",
                    tokens.values.none {
                        it ==
                            Color.Unspecified
                    },
                )
            }
            // Los roles fijos no cambian con el modo, y el blanco va sobre el héroe y el relleno de marca.
            val light = tokensOf(set, dark = false)
            val dark = tokensOf(set, dark = true)
            for (name in FIXED_ROLES) {
                assertEquals(
                    "$palette: $name",
                    light.getValue(name),
                    dark.getValue(name),
                )
            }
            for (name in listOf("onHero", "onBrandFill")) {
                assertEquals("$palette claro: $name", WHITE, light.getValue(name))
                assertEquals("$palette oscuro: $name", WHITE, dark.getValue(name))
            }
        }
    }

    @Test
    fun everyPairMeetsItsMinimumInBothModes() {
        val failures = mutableListOf<String>()
        var checked = 0
        for ((palette, set) in AppPalettes) {
            for (dark in listOf(false, true)) {
                val tokens = tokensOf(set, dark)
                val requirements = textOnSurfaces + rolePairs + componentPairs + extendedPairs
                assertEquals(PAIRS_PER_MODE, requirements.size)
                for (requirement in requirements) {
                    checked++
                    val ratio =
                        contrast(
                            tokens.getValue(requirement.foreground),
                            tokens.getValue(requirement.background),
                        )
                    if (ratio < requirement.minimum) {
                        val mode = if (dark) "oscuro" else "claro"
                        val shown = "%.2f".format(ratio)
                        val pair = "${requirement.foreground} sobre ${requirement.background}"
                        failures +=
                            "$palette $mode: $pair da $shown, mínimo ${requirement.minimum} " +
                            "(${requirement.use})"
                    }
                }
            }
        }
        assertEquals(AppPalettes.size * 2 * PAIRS_PER_MODE, checked)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun generatedDocumentsAreReadableOnWhitePaper() {
        // Los documentos no siguen el tema: van sobre papel blanco con los valores claros de la paleta.
        for ((palette, set) in AppPalettes) {
            val tokens = tokensOf(set, dark = false)
            for (requirement in paperPairs) {
                val ratio = contrast(tokens.getValue(requirement.foreground), WHITE)
                assertTrue(
                    "$palette: ${requirement.use} da ${"%.2f".format(ratio)}",
                    ratio >= requirement.minimum,
                )
            }
        }
    }

    @Test
    fun chartSeriesStaySeparateAndReadable() {
        for ((palette, set) in AppPalettes) {
            for (dark in listOf(false, true)) {
                val tokens = tokensOf(set, dark)
                val mode = if (dark) "oscuro" else "claro"
                val series = SERIES.map { tokens.getValue(it) }
                val band = if (dark) DARK_BAND else LIGHT_BAND
                for ((name, color) in SERIES.zip(series)) {
                    val (lightness, chroma) = oklch(color)
                    assertTrue(
                        "$palette $mode: $name con luminosidad $lightness fuera de $band",
                        lightness in band,
                    )
                    assertTrue("$palette $mode: $name con croma $chroma", chroma >= MIN_CHROMA)
                    val ratio = contrast(color, tokens.getValue("cardSurface"))
                    assertTrue(
                        "$palette $mode: $name da ${"%.2f".format(ratio)} sobre la tarjeta",
                        ratio >= COMPONENT,
                    )
                }
                // Solo entre vecinas: una serie se compara con la que tiene al lado en la gráfica.
                for ((a, b) in series.zipWithNext()) {
                    val normal = deltaE(a, b, null)
                    val blind = minOf(deltaE(a, b, PROTAN), deltaE(a, b, DEUTAN))
                    assertTrue(
                        "$palette $mode: series vecinas a $normal",
                        normal >= MIN_DELTA_NORMAL,
                    )
                    assertTrue(
                        "$palette $mode: series vecinas a $blind con daltonismo",
                        blind >= MIN_DELTA_CVD,
                    )
                }
            }
        }
    }

    private data class Requirement(
        val foreground: String,
        val background: String,
        val minimum: Double,
        val use: String,
    )

    // Texto sobre cada superficie: doce colores de texto sobre ocho superficies, mínimo 4,5:1.
    private val textOnSurfaces =
        listOf(
            "onSurface", "onSurfaceVariant", "primary", "secondary", "tertiary", "error",
            "profit", "loss", "warning", "backupOk", "backupPending", "backupError",
        ).flatMap { text ->
            listOf(
                "surface",
                "cardSurface",
                "surfaceContainerLowest",
                "surfaceContainerLow",
                "surfaceContainer",
                "surfaceContainerHigh",
                "surfaceContainerHighest",
                "surfaceBright",
            ).map { surface -> Requirement(text, surface, TEXT, "texto sobre superficie") }
        }

    // Pares de rol: texto sobre su fondo, mínimo 4,5:1.
    private val rolePairs =
        listOf(
            Requirement("onPrimary", "primary", TEXT, "par de rol"),
            Requirement("onPrimaryContainer", "primaryContainer", TEXT, "par de rol"),
            Requirement("primary", "primaryContainer", TEXT, "par de rol"),
            Requirement("onSecondary", "secondary", TEXT, "par de rol"),
            Requirement("onSecondaryContainer", "secondaryContainer", TEXT, "par de rol"),
            Requirement("onTertiary", "tertiary", TEXT, "par de rol"),
            Requirement("onTertiaryContainer", "tertiaryContainer", TEXT, "par de rol"),
            Requirement("onError", "error", TEXT, "par de rol"),
            Requirement("onErrorContainer", "errorContainer", TEXT, "par de rol"),
            Requirement("error", "errorContainer", TEXT, "par de rol"),
            Requirement("onBackground", "background", TEXT, "par de rol"),
            Requirement("onSurfaceVariant", "surfaceVariant", TEXT, "par de rol"),
            Requirement("onSurface", "surfaceVariant", TEXT, "par de rol"),
            Requirement("inverseOnSurface", "inverseSurface", TEXT, "par de rol"),
            Requirement("inversePrimary", "inverseSurface", TEXT, "par de rol"),
            Requirement("onPrimaryFixed", "primaryFixed", TEXT, "par de rol"),
            Requirement("onPrimaryFixedVariant", "primaryFixed", TEXT, "par de rol"),
            Requirement("onPrimaryFixed", "primaryFixedDim", TEXT, "par de rol"),
            Requirement("onPrimaryFixedVariant", "primaryFixedDim", TEXT, "par de rol"),
            Requirement("onSecondaryFixed", "secondaryFixed", TEXT, "par de rol"),
            Requirement("onSecondaryFixedVariant", "secondaryFixed", TEXT, "par de rol"),
            Requirement("onSecondaryFixed", "secondaryFixedDim", TEXT, "par de rol"),
            Requirement("onSecondaryFixedVariant", "secondaryFixedDim", TEXT, "par de rol"),
            Requirement("onTertiaryFixed", "tertiaryFixed", TEXT, "par de rol"),
            Requirement("onTertiaryFixedVariant", "tertiaryFixed", TEXT, "par de rol"),
            Requirement("onTertiaryFixed", "tertiaryFixedDim", TEXT, "par de rol"),
            Requirement("onTertiaryFixedVariant", "tertiaryFixedDim", TEXT, "par de rol"),
            Requirement("onBrandFill", "brandFill", TEXT, "par de rol"),
            Requirement("onHero", "heroContainer", TEXT, "par de rol"),
            Requirement("onHeroVariant", "heroContainer", TEXT, "par de rol"),
            Requirement("heroAccent", "heroContainer", TEXT, "par de rol"),
            Requirement("onProfitContainer", "profitContainer", TEXT, "par de rol"),
            Requirement("profit", "profitContainer", TEXT, "par de rol"),
            Requirement("onLossContainer", "lossContainer", TEXT, "par de rol"),
            Requirement("loss", "lossContainer", TEXT, "par de rol"),
            Requirement("onWarningContainer", "warningContainer", TEXT, "par de rol"),
            Requirement("warning", "warningContainer", TEXT, "par de rol"),
            Requirement("onBackupOkContainer", "backupOkContainer", TEXT, "par de rol"),
            Requirement("backupOk", "backupOkContainer", TEXT, "par de rol"),
            Requirement("onBackupPendingContainer", "backupPendingContainer", TEXT, "par de rol"),
            Requirement("backupPending", "backupPendingContainer", TEXT, "par de rol"),
            Requirement("onBackupErrorContainer", "backupErrorContainer", TEXT, "par de rol"),
            Requirement("backupError", "backupErrorContainer", TEXT, "par de rol"),
        )

    // Componentes con significado, mínimo 3:1, con su uso.
    private val componentPairs =
        listOf(
            Requirement(
                "outline",
                "surface",
                COMPONENT,
                "Borde de campo y de chip sin seleccionar",
            ),
            Requirement("outline", "cardSurface", COMPONENT, "Borde de campo dentro de tarjeta"),
            Requirement(
                "outline",
                "surfaceContainerLow",
                COMPONENT,
                "Borde de campo dentro de hoja inferior",
            ),
            Requirement(
                "outline",
                "surfaceContainerHigh",
                COMPONENT,
                "Borde de campo dentro de diálogo",
            ),
            Requirement(
                "outline",
                "surfaceContainerHighest",
                COMPONENT,
                "Pulgar del interruptor apagado sobre su pista",
            ),
            Requirement(
                "primary",
                "surface",
                COMPONENT,
                "Foco, interruptor encendido, icono activo",
            ),
            Requirement("primary", "cardSurface", COMPONENT, "Foco e icono activo en tarjeta"),
            Requirement(
                "primary",
                "surfaceContainerLow",
                COMPONENT,
                "Pestaña activa en barra inferior",
            ),
            Requirement(
                "brandFill",
                "surface",
                COMPONENT,
                "Botón principal y Vender sobre el fondo",
            ),
            Requirement("brandFill", "cardSurface", COMPONENT, "Botón principal sobre tarjeta"),
            Requirement(
                "brandFill",
                "surfaceContainerLow",
                COMPONENT,
                "Botón Vender sobre la barra inferior",
            ),
            Requirement("chartBar", "cardSurface", COMPONENT, "Serie 1 de gráfica"),
            Requirement(
                "chartBarMuted",
                "cardSurface",
                COMPONENT,
                "Barra no seleccionada de gráfica",
            ),
            Requirement(
                "chartBarSelected",
                "cardSurface",
                COMPONENT,
                "Barra seleccionada de gráfica",
            ),
            Requirement("chartSeries2", "cardSurface", COMPONENT, "Serie 2 de gráfica"),
            Requirement("chartSeries3", "cardSurface", COMPONENT, "Serie 3 de gráfica"),
            Requirement("chartProfit", "cardSurface", COMPONENT, "Barra de ganancia"),
            Requirement("chartLoss", "cardSurface", COMPONENT, "Barra de pérdida"),
            Requirement("profit", "surface", COMPONENT, "Icono de estado positivo"),
            Requirement("loss", "surface", COMPONENT, "Icono de estado negativo"),
            Requirement("warning", "surface", COMPONENT, "Icono de poco stock"),
            Requirement(
                "backupOk",
                "surfaceContainerLow",
                COMPONENT,
                "Punto de copia al día en barra superior",
            ),
            Requirement(
                "backupPending",
                "surfaceContainerLow",
                COMPONENT,
                "Punto de copia pendiente en barra superior",
            ),
            Requirement(
                "backupError",
                "surfaceContainerLow",
                COMPONENT,
                "Punto de copia con error en barra superior",
            ),
        )

    // Pares del alcance ampliado, cada uno con su mínimo y su uso.
    private val extendedPairs =
        listOf(
            Requirement(
                "warning",
                "cardSurface",
                TEXT,
                "Dato con atención en `ToolRow` y `LotRow` en tarjeta",
            ),
            Requirement("loss", "cardSurface", TEXT, "`LotRow` vencido en tarjeta"),
            Requirement("profit", "cardSurface", TEXT, "Sin diferencia en `CashSessionCard`"),
            Requirement(
                "warning",
                "surfaceContainerHigh",
                TEXT,
                "`LimitMeter` al 80 %, etiqueta sobre esqueleto",
            ),
            Requirement("backupOk", "surfaceContainerHigh", TEXT, "`HealthRow` en hoja"),
            Requirement(
                "chartBar",
                "surfaceContainerHigh",
                COMPONENT,
                "`GoalProgress` y `BreakEvenBar`, barra sobre su pista",
            ),
            Requirement(
                "warning",
                "surfaceContainerHigh",
                COMPONENT,
                "`LimitMeter`, barra sobre su pista",
            ),
            Requirement(
                "loss",
                "surfaceContainerHigh",
                COMPONENT,
                "`LimitMeter` al 95 %, barra sobre su pista",
            ),
            Requirement("onSurface", "surfaceContainerHigh", COMPONENT, "Marca de `BreakEvenBar`"),
            Requirement(
                "onSurfaceVariant",
                "surfaceContainerHigh",
                TEXT,
                "`PhotoThumb` sin foto, icono y etiqueta",
            ),
            Requirement("onSurface", "surfaceContainerHigh", TEXT, "`RecoveryCodeBlock`"),
            Requirement("onTertiaryContainer", "tertiaryContainer", TEXT, "`DemoBanner`"),
            Requirement("tertiary", "tertiaryContainer", COMPONENT, "Icono de `DemoBanner`"),
            Requirement("onWarningContainer", "warningContainer", TEXT, "`BalanceBadge` `Owes`"),
            Requirement("onLossContainer", "lossContainer", TEXT, "`BalanceBadge` `Overdue`"),
            Requirement("onProfitContainer", "profitContainer", TEXT, "`BalanceBadge` `Credit`"),
            Requirement("onHeroVariant", "heroContainer", TEXT, "`DiscreetValue` sobre el héroe"),
            Requirement("onSurfaceVariant", "surface", TEXT, "`DiscreetValue` sobre papel"),
            Requirement("onSurfaceVariant", "cardSurface", TEXT, "`DiscreetValue` en tarjeta"),
            Requirement("inverseOnSurface", "inverseSurface", TEXT, "Etiqueta de `ScannerOverlay`"),
            Requirement("onBrandFill", "brandFill", TEXT, "`Vender` en widget y riel"),
            Requirement(
                "brandFill",
                "surfaceContainerLow",
                COMPONENT,
                "`Vender` sobre el riel y el widget",
            ),
            Requirement(
                "onSurfaceVariant",
                "surfaceContainerLow",
                TEXT,
                "Destino inactivo del riel y texto del widget",
            ),
            Requirement(
                "onSecondaryContainer",
                "secondaryContainer",
                TEXT,
                "Destino activo del riel",
            ),
            Requirement("primary", "surface", COMPONENT, "Paso actual de `OrderStatusTrack`"),
            Requirement("outline", "surface", COMPONENT, "Paso pendiente de `OrderStatusTrack`"),
            Requirement("chartLoss", "cardSurface", COMPONENT, "Saldo negativo en `CashFlowBars`"),
            Requirement("onSurface", "cardSurface", TEXT, "`DenominationCounter`, `LedgerRow`"),
        )

    // Documentos sobre papel blanco: tinta, texto secundario y el filete del encabezado.
    private val paperPairs =
        listOf(
            Requirement("onSurface", "paper", TEXT, "la tinta"),
            Requirement("onSurfaceVariant", "paper", TEXT, "el texto secundario"),
            Requirement("primary", "paper", COMPONENT, "el filete del encabezado"),
        )

    private companion object {
        const val TEXT = 4.5
        const val COMPONENT = 3.0
        const val TOKEN_COUNT = 83
        const val PAIRS_PER_MODE = 191
        val WHITE = Color(0xFFFFFFFF)

        // Las comprobaciones de las series: banda de luminosidad OKLCH por modo, croma mínimo y
        // separación ΔE en OKLab × 100 entre vecinas, con visión normal y con daltonismo.
        val LIGHT_BAND = 0.43..0.77
        val DARK_BAND = 0.48..0.67
        const val MIN_CHROMA = 0.10
        const val MIN_DELTA_NORMAL = 15.0
        const val MIN_DELTA_CVD = 8.0
        val SERIES = listOf("chartBar", "chartSeries2", "chartSeries3")

        val FIXED_ROLES =
            listOf(
                "primaryFixed", "primaryFixedDim", "onPrimaryFixed", "onPrimaryFixedVariant",
                "secondaryFixed", "secondaryFixedDim",
                "onSecondaryFixed", "onSecondaryFixedVariant",
                "tertiaryFixed", "tertiaryFixedDim", "onTertiaryFixed", "onTertiaryFixedVariant",
            )

        // Machado, Oliveira y Fernandes (2009), severidad 1, sobre RGB lineal.
        val PROTAN =
            arrayOf(
                doubleArrayOf(0.152286, 1.052583, -0.204868),
                doubleArrayOf(0.114503, 0.786281, 0.099216),
                doubleArrayOf(-0.003882, -0.048116, 1.051998),
            )
        val DEUTAN =
            arrayOf(
                doubleArrayOf(0.367322, 0.860646, -0.227968),
                doubleArrayOf(0.280085, 0.672501, 0.047413),
                doubleArrayOf(-0.011820, 0.042940, 0.968881),
            )

        fun tokensOf(set: AppPalette, dark: Boolean): Map<String, Color> {
            val scheme = if (dark) set.darkScheme else set.lightScheme
            val colors = if (dark) set.darkColors else set.lightColors
            return schemeTokens(scheme) + appTokens(colors)
        }

        fun schemeTokens(s: ColorScheme): Map<String, Color> = mapOf(
            "primary" to s.primary, "onPrimary" to s.onPrimary,
            "primaryContainer" to s.primaryContainer,
            "onPrimaryContainer" to s.onPrimaryContainer,
            "inversePrimary" to s.inversePrimary, "secondary" to s.secondary,
            "onSecondary" to s.onSecondary,
            "secondaryContainer" to s.secondaryContainer,
            "onSecondaryContainer" to s.onSecondaryContainer,
            "tertiary" to s.tertiary, "onTertiary" to s.onTertiary,
            "tertiaryContainer" to s.tertiaryContainer,
            "onTertiaryContainer" to s.onTertiaryContainer,
            "error" to s.error, "onError" to s.onError,
            "errorContainer" to s.errorContainer, "onErrorContainer" to s.onErrorContainer,
            "background" to s.background, "onBackground" to s.onBackground,
            "surface" to s.surface, "onSurface" to s.onSurface,
            "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
            "surfaceTint" to s.surfaceTint, "inverseSurface" to s.inverseSurface,
            "inverseOnSurface" to s.inverseOnSurface, "outline" to s.outline,
            "outlineVariant" to s.outlineVariant,
            "scrim" to s.scrim, "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim,
            "surfaceContainerLowest" to s.surfaceContainerLowest,
            "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainer" to s.surfaceContainer,
            "surfaceContainerHigh" to s.surfaceContainerHigh,
            "surfaceContainerHighest" to s.surfaceContainerHighest,
            "primaryFixed" to s.primaryFixed, "primaryFixedDim" to s.primaryFixedDim,
            "onPrimaryFixed" to s.onPrimaryFixed,
            "onPrimaryFixedVariant" to s.onPrimaryFixedVariant,
            "secondaryFixed" to s.secondaryFixed, "secondaryFixedDim" to s.secondaryFixedDim,
            "onSecondaryFixed" to s.onSecondaryFixed,
            "onSecondaryFixedVariant" to s.onSecondaryFixedVariant,
            "tertiaryFixed" to s.tertiaryFixed, "tertiaryFixedDim" to s.tertiaryFixedDim,
            "onTertiaryFixed" to s.onTertiaryFixed,
            "onTertiaryFixedVariant" to s.onTertiaryFixedVariant,
        )

        fun appTokens(c: AppColors): Map<String, Color> = mapOf(
            "brandFill" to c.brandFill, "onBrandFill" to c.onBrandFill,
            "heroContainer" to c.heroContainer, "onHero" to c.onHero,
            "onHeroVariant" to c.onHeroVariant,
            "heroAccent" to c.heroAccent, "heroDivider" to c.heroDivider,
            "cardSurface" to c.cardSurface, "cardBorder" to c.cardBorder,
            "profit" to c.profit, "profitContainer" to c.profitContainer,
            "onProfitContainer" to c.onProfitContainer,
            "loss" to c.loss, "lossContainer" to c.lossContainer,
            "onLossContainer" to c.onLossContainer,
            "warning" to c.warning, "warningContainer" to c.warningContainer,
            "onWarningContainer" to c.onWarningContainer,
            "backupOk" to c.backupOk, "backupOkContainer" to c.backupOkContainer,
            "onBackupOkContainer" to c.onBackupOkContainer,
            "backupPending" to c.backupPending,
            "backupPendingContainer" to c.backupPendingContainer,
            "onBackupPendingContainer" to c.onBackupPendingContainer,
            "backupError" to c.backupError, "backupErrorContainer" to c.backupErrorContainer,
            "onBackupErrorContainer" to c.onBackupErrorContainer,
            "chartBar" to c.chartBar, "chartBarMuted" to c.chartBarMuted,
            "chartBarSelected" to c.chartBarSelected,
            "chartSeries2" to c.chartSeries2, "chartSeries3" to c.chartSeries3,
            "chartProfit" to c.chartProfit, "chartLoss" to c.chartLoss,
            "chartGrid" to c.chartGrid,
        )

        fun channels(color: Color): DoubleArray {
            val argb = color.toArgb()
            return doubleArrayOf(
                (argb shr 16 and 0xFF) / 255.0,
                (argb shr 8 and 0xFF) / 255.0,
                (argb and 0xFF) / 255.0,
            )
        }

        // Luminancia relativa de WCAG, con el umbral de 0,03928 de las medidas publicadas.
        fun luminance(color: Color): Double {
            val (r, g, b) = channels(color).map {
                if (it <=
                    0.03928
                ) {
                    it / 12.92
                } else {
                    ((it + 0.055) / 1.055).pow(2.4)
                }
            }
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        fun contrast(a: Color, b: Color): Double {
            val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
            return (high + 0.05) / (low + 0.05)
        }

        fun linear(color: Color): DoubleArray = channels(color).map {
            if (it <=
                0.04045
            ) {
                it / 12.92
            } else {
                ((it + 0.055) / 1.055).pow(2.4)
            }
        }.toDoubleArray()

        fun oklab(rgb: DoubleArray): DoubleArray {
            val (r, g, b) = rgb.toList()
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            return doubleArrayOf(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }

        /** Luminosidad y croma OKLCH. */
        fun oklch(color: Color): Pair<Double, Double> {
            val (l, a, b) = oklab(linear(color)).toList()
            return l to hypot(a, b)
        }

        fun simulate(rgb: DoubleArray, matrix: Array<DoubleArray>?): DoubleArray =
            if (matrix == null) {
                rgb
            } else {
                DoubleArray(3) { i ->
                    (0..2).sumOf { j -> matrix[i][j] * rgb[j] }.coerceIn(0.0, 1.0)
                }
            }

        fun deltaE(a: Color, b: Color, matrix: Array<DoubleArray>?): Double {
            val x = oklab(simulate(linear(a), matrix))
            val y = oklab(simulate(linear(b), matrix))
            return 100 * sqrt((0..2).sumOf { (x[it] - y[it]).pow(2) })
        }
    }
}
