package io.github.yesidrobayo.shopledger.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertTrue

/**
 * Las comprobaciones de accesibilidad que se pueden medir sin dependencias nuevas, sobre lo que dibuja
 * una pantalla en el árbol de semántica que lee TalkBack:
 *
 * - todo lo tocable mide al menos 48 × 48 dp, contando el área que agrega
 *   `minimumInteractiveComponentSize`;
 * - todo lo tocable tiene un nombre: un texto, una descripción o el texto de su campo;
 * - ningún texto se sale de su caja, como puede pasar con la letra al 200 %.
 *
 * Cada falla dice qué nodo es, para que el informe la ubique en la pantalla.
 */
class A11yAudit(private val compose: ComposeContentTestRule) {
    private val clickable = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
    private val laidOutText = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)

    /** Las fallas encontradas en lo que se ve ahora, en todas las ventanas: hojas y diálogos incluidos. */
    fun problems(): List<String> {
        compose.waitForIdle()
        val density = compose.density.density
        val found = mutableListOf<String>()
        for (node in compose.onAllNodes(clickable).fetchSemanticsNodes()) {
            if (!node.isShown()) continue
            val touch = node.touchBoundsInRoot
            val widthDp = maxOf(node.size.width.toFloat(), touch.width) / density
            val heightDp = maxOf(node.size.height.toFloat(), touch.height) / density
            if (widthDp < MIN_TARGET_DP - TOLERANCE_DP || heightDp < MIN_TARGET_DP - TOLERANCE_DP) {
                found +=
                    "objetivo de ${widthDp.toInt()} × ${heightDp.toInt()} dp: ${node.describe()}"
            }
            if (node.name().isBlank()) found += "tocable sin nombre: ${node.describe()}"
        }
        for (node in compose.onAllNodes(
            laidOutText,
            useUnmergedTree = true,
        ).fetchSemanticsNodes()) {
            if (!node.isShown()) continue
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.firstOrNull { it.isCut() }?.let { cut ->
                found += "texto recortado (${cut.why()}): ${node.describe()}"
            }
        }
        return found
    }

    /** Falla con la lista entera, para ver todas las fallas de una pantalla en una sola corrida. */
    fun assertClean(scene: String) {
        val found = problems()
        assertTrue("$scene:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /**
     * Si el texto se sale de su caja: su ancho natural, el de la línea más larga sin partir, pasa del
     * ancho de la caja, y aun así quedó en una sola línea. La elipsis no cuenta: un nombre de 80
     * caracteres se corta con elipsis a propósito, y los montos nunca la usan. No se usan
     * `didOverflowWidth`, el alto del párrafo ni el ancho de cada línea: el párrafo se arma con el ancho
     * que se ofrece, y un texto centrado o con la altura de línea recortada del tema los mide de más
     * sin perder nada, como se vio en las capturas.
     */
    private fun TextLayoutResult.isCut(): Boolean =
        lineCount == 1 && multiParagraph.intrinsics.maxIntrinsicWidth > size.width + 1f

    private fun TextLayoutResult.why(): String =
        "caja ${size.width}, ancho natural ${multiParagraph.intrinsics.maxIntrinsicWidth}"

    private fun SemanticsNode.isShown(): Boolean = size.width > 0 && size.height > 0 &&
        config.getOrNull(SemanticsProperties.HideFromAccessibility) == null

    private fun SemanticsNode.name(): String = buildString {
        config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { append(it) }
        config.getOrNull(SemanticsProperties.Text)?.forEach { append(it.text) }
        config.getOrNull(SemanticsProperties.EditableText)?.let { append(it.text) }
        config.getOrNull(SemanticsProperties.StateDescription)?.let { append(it) }
    }

    private fun SemanticsNode.describe(): String {
        val label = name().ifBlank {
            config.getOrNull(SemanticsProperties.TestTag) ?: "(sin texto)"
        }
        return "\"${label.take(DESCRIBE_CHARS)}\" en $boundsInRoot"
    }

    private companion object {
        const val MIN_TARGET_DP = 48f

        /** Medio dp de redondeo al pasar de píxeles a dp. */
        const val TOLERANCE_DP = 0.5f
        const val DESCRIBE_CHARS = 40
    }
}
