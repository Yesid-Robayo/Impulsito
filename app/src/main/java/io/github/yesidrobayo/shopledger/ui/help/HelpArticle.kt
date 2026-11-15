package io.github.yesidrobayo.shopledger.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Un artículo de la ayuda dibujado con los estilos del tema, sin `WebView`: el título en
 * `headlineMedium`, el cuerpo en `bodyLarge`, encabezados, listas, negrita y enlaces a otros
 * artículos. Las tablas no existen aquí: los artículos las escriben como listas, para que con
 * letra grande nada se corte.
 *
 * @param onOpenArticle abre el artículo de un enlace.
 */
@Composable
fun HelpArticle(
    title: String,
    blocks: List<HelpBlock>,
    onOpenArticle: (HelpArticleId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val announced = stringResource(R.string.a11y_help_article, title)
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics {
                heading()
                contentDescription = announced
            },
        )
        blocks.forEach { block -> Block(block, onOpenArticle) }
    }
}

@Composable
private fun Block(block: HelpBlock, onOpenArticle: (HelpArticleId) -> Unit) {
    val typography = MaterialTheme.typography
    when (block) {
        is HelpBlock.Heading ->
            Text(
                annotated(block.spans, onOpenArticle),
                style = if (block.level <= 2) typography.titleLarge else typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = AppTheme.spacing.s8).semantics { heading() },
            )

        is HelpBlock.Paragraph -> BodyText(annotated(block.spans, onOpenArticle))

        is HelpBlock.ListBlock ->
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                block.items.forEachIndexed { index, item ->
                    ListItem(
                        marker = if (block.numbered) numberMarker(index) else BULLET,
                        text = annotated(item, onOpenArticle),
                    )
                }
            }
    }
}

/**
 * Un elemento de lista: la viñeta o el número, y el texto con sangría propia, que no se mete
 * debajo del número al pasar a otra línea. TalkBack lee solo el texto de una viñeta, y el número
 * con él.
 */
@Composable
private fun ListItem(marker: String, text: AnnotatedString) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        Text(
            marker,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (marker == BULLET) Modifier.clearAndSetSemantics {} else Modifier,
        )
        BodyText(text, Modifier.weight(1f))
    }
}

@Composable
private fun BodyText(text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** Los tramos con su estilo: la negrita en seminegrita, y los enlaces subrayados y tocables. */
@Composable
private fun annotated(
    spans: List<HelpSpan>,
    onOpenArticle: (HelpArticleId) -> Unit,
): AnnotatedString {
    val link = MaterialTheme.colorScheme.primary
    return remember(spans, link, onOpenArticle) { build(spans, link, onOpenArticle) }
}

private fun build(
    spans: List<HelpSpan>,
    link: Color,
    onOpenArticle: (HelpArticleId) -> Unit,
): AnnotatedString = buildAnnotatedString {
    val linkStyle =
        TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    for (span in spans) {
        when (span) {
            is HelpSpan.Plain -> append(span.text)

            is HelpSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(span.text)
            }

            is HelpSpan.Link -> {
                val target = span.article
                val annotation =
                    LinkAnnotation.Clickable(target.key, linkStyle) { onOpenArticle(target) }
                withLink(annotation) { append(span.text) }
            }
        }
    }
}

/** El número de un elemento, como `1.`: solo cifras y punto, igual en los tres idiomas. */
private fun numberMarker(index: Int): String = (index + 1).toString() + NUMBER_SUFFIX

private const val BULLET = "•"
private const val NUMBER_SUFFIX = "."

@ThemePreviews
@Composable
private fun HelpArticlePreview() {
    PreviewTheme {
        HelpArticle(
            title = helpTitle(HelpArticleId.BACKUP_LIMITS),
            blocks = HelpMarkdown.parse(PREVIEW_ARTICLE, stringResource(R.string.app_name)),
            onOpenArticle = {},
            modifier = Modifier.padding(AppTheme.spacing.s16),
        )
    }
}

/** Un artículo de muestra, con cada bloque y un párrafo largo para la letra al 200 %. */
internal const val PREVIEW_ARTICLE = """
Los datos viven en el teléfono, y {app} guarda copias en la nube que se elija.

## Cada día

1. **Registrar cada venta** en el momento.
2. Mirar en Inicio el estado de la copia: esa línea es la que manda, y con letra grande pasa a
   varias líneas sin cortarse.

- Con **Más segura**, la app sube y comprueba.
- Más en [Dónde guardar las copias](help_destinations).
"""
