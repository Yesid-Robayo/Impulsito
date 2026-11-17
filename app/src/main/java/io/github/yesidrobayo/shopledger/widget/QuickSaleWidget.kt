package io.github.yesidrobayo.shopledger.widget

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceComposable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.yesidrobayo.shopledger.MainActivity
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.domain.model.surface.WidgetContent
import io.github.yesidrobayo.shopledger.domain.rule.surface.QuickSaleWidgetRules
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest
import io.github.yesidrobayo.shopledger.surface.LockStateReader
import io.github.yesidrobayo.shopledger.ui.format.businessFormatsOf

/** Solo las acciones: el tamaño 4 x 1. */
private val ActionsOnlySize = DpSize(180.dp, 48.dp)

/** El día y las acciones: el tamaño 4 x 2 de un lanzador de celdas bajas. */
private val CompactSize = DpSize(180.dp, 110.dp)

/** El día, una línea más y las acciones: el tamaño 4 x 2 de la mayoría de los lanzadores. */
private val SummarySize = DpSize(180.dp, 130.dp)

/**
 * El widget de venta rápida, con Glance: vender con un toque y ver el día.
 *
 * - **Sin montos por defecto.** Muestra conteos: ventas de hoy y lo que se está acabando. El monto
 *   aparece solo si la persona lo pide con el ojo de ese widget, y nunca con la pantalla apagada,
 *   el teléfono bloqueado ni el modo discreto. La elección vive en el estado de Glance del widget,
 *   fuera de la base.
 * - **Lee la base solo por su compuerta**, sin migrarla, y nunca la de la demostración. Si no está
 *   lista, queda neutro: solo `Vender`, que abre la app.
 * - **Abre la app con un pedido de la lista cerrada,** con un `Intent` explícito a la actividad
 *   principal: sin esquemas ni enlaces. Con el bloqueo de la app, el destino espera al desbloqueo.
 * - **Toma la paleta elegida** al leer sus datos, y la predeterminada si la base no abre. Sigue el
 *   modo claro u oscuro del sistema y usa la letra del tema del sistema: Glance no carga la de la
 *   app.
 * - **Sus textos salen del idioma de la interfaz,** no del teléfono.
 */
class QuickSaleWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode =
        SizeMode.Responsive(setOf(ActionsOnlySize, CompactSize, SummarySize))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as ShopLedgerApp
        val container = app.container
        val updater = container.quickSaleWidget
        val loadedAt = updater.refreshCount.value
        val first = loadQuickSaleWidgetData(app)
        provideContent {
            val refresh by updater.refreshCount.collectAsState()
            // Con la sesión abierta, un pedido de redibujar vuelve a leer la base.
            val data by produceState(first, refresh) {
                if (refresh != loadedAt) value = loadQuickSaleWidgetData(app)
            }
            val signaled by container.lockWatcher.state.collectAsState()
            // Cada cambio de bloqueo recompone. La lectura directa cubre una señal que no llegó.
            val lock = signaled.strictest(LockStateReader.read(context))
            val chosen = currentState(showAmountsKey) ?: false
            val content =
                QuickSaleWidgetRules.contentOf(data.facts, chosen, lock, data.discreet)
            val texts = container.localizedContext.context()
            WidgetBody(
                content = content,
                amountText = amountTextOf(content, data, texts),
                colors = WidgetColors(data.palette),
                texts = texts,
            )
        }
    }

    companion object {
        /** La elección de mostrar montos, propia de cada widget, en su estado de Glance. */
        val showAmountsKey: Preferences.Key<Boolean> = booleanPreferencesKey("show_amounts")
    }
}

/** `Ventas de hoy: {amount}` con el formato del negocio, solo si la regla dejó ver el monto. */
private fun amountTextOf(
    content: WidgetContent,
    data: QuickSaleWidgetData,
    texts: Context,
): String? {
    val amount = (content as? WidgetContent.Summary)?.amount ?: return null
    val regional = data.regional ?: return null
    val uiLocale = texts.resources.configuration.locales[0]
    val money = businessFormatsOf(regional, uiLocale).money.format(amount)
    return texts.getString(R.string.widget_sales_amount, money)
}

@GlanceComposable
@Composable
private fun WidgetBody(
    content: WidgetContent,
    amountText: String?,
    colors: WidgetColors,
    texts: Context,
) {
    val size = LocalSize.current
    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(colors.border)
                .cornerRadius(20.dp)
                .padding(1.dp),
    ) {
        Column(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .background(colors.surface)
                    .cornerRadius(19.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (size.height >= CompactSize.height && content != WidgetContent.Neutral) {
                // La línea del monto o del respaldo, y el ojo que la pide, solo entran si caben
                // sin tapar las acciones.
                val roomy = size.height >= SummarySize.height
                SummaryHeader(content = content, withEye = roomy, colors = colors, texts = texts)
                if (roomy) {
                    SecondLine(content, amountText, colors, texts)
                }
                Spacer(modifier = GlanceModifier.defaultWeight())
            }
            ActionsRow(
                onlySell = content == WidgetContent.Neutral,
                colors = colors,
                texts = texts,
            )
        }
    }
}

@GlanceComposable
@Composable
private fun SummaryHeader(
    content: WidgetContent,
    withEye: Boolean,
    colors: WidgetColors,
    texts: Context,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = texts.getString(R.string.widget_today),
                style = textStyle(colors.text, fontSize = 16, weight = FontWeight.Medium),
                maxLines = 1,
            )
            when (content) {
                is WidgetContent.Summary ->
                    Text(
                        text = countsLine(content, texts),
                        style = textStyle(colors.secondaryText, fontSize = 14),
                        maxLines = 1,
                    )

                // Sin productos, la línea del día invita a crear el primero.
                WidgetContent.NoProducts ->
                    Text(
                        text = texts.getString(R.string.empty_home_action),
                        style = textStyle(colors.text, fontSize = 14, weight = FontWeight.Medium),
                        maxLines = 1,
                        modifier =
                            GlanceModifier.clickable(open(texts, AppOpenRequest.NewProduct)),
                    )

                WidgetContent.Neutral -> Unit
            }
        }
        if (withEye && content is WidgetContent.Summary) {
            val chosen = content.amountsChosen
            val icon = if (chosen) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            Image(
                provider = ImageProvider(icon),
                contentDescription =
                    texts.getString(
                        if (chosen) R.string.widget_hide_amounts else R.string.widget_show_amounts,
                    ),
                modifier =
                    GlanceModifier
                        .size(48.dp)
                        .padding(12.dp)
                        .clickable(actionRunCallback<ToggleAmountsAction>()),
                colorFilter = ColorFilter.tint(colors.secondaryText),
            )
        }
    }
}

/**
 * Una sola línea bajo el día, para que el tamaño 4 x 2 no se desborde: un respaldo fallido gana
 * sobre el monto, porque es lo que la persona tiene que atender.
 */
@GlanceComposable
@Composable
private fun SecondLine(
    content: WidgetContent,
    amountText: String?,
    colors: WidgetColors,
    texts: Context,
) {
    val summary = content as? WidgetContent.Summary ?: return
    when {
        summary.backupFailed ->
            Text(
                text = texts.getString(R.string.widget_backup_failed),
                style = textStyle(colors.text, fontSize = 13, weight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.clickable(open(texts, AppOpenRequest.Backup)),
            )

        amountText != null ->
            Text(
                text = amountText,
                style = textStyle(colors.text, fontSize = 14),
                maxLines = 1,
            )
    }
}

@GlanceComposable
@Composable
private fun ActionsRow(onlySell: Boolean, colors: WidgetColors, texts: Context) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .background(colors.brand)
                    .cornerRadius(12.dp)
                    .clickable(open(texts, AppOpenRequest.NewSale)),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(R.drawable.ic_sell),
                    contentDescription = null,
                    modifier = GlanceModifier.size(20.dp),
                    colorFilter = ColorFilter.tint(colors.onBrand),
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Text(
                    text = texts.getString(R.string.nav_sell),
                    style = textStyle(colors.onBrand, fontSize = 14, weight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
        if (!onlySell) {
            Spacer(modifier = GlanceModifier.width(8.dp))
            SayAction(colors = colors, texts = texts)
            Spacer(modifier = GlanceModifier.width(8.dp))
            SecondaryAction(
                label = R.string.home_shortcut_expense,
                request = AppOpenRequest.NewExpense,
                colors = colors,
                texts = texts,
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            SecondaryAction(
                label = R.string.home_shortcut_purchase,
                request = AppOpenRequest.NewPurchase,
                colors = colors,
                texts = texts,
            )
        }
    }
}

@GlanceComposable
@Composable
private fun SecondaryAction(
    @StringRes label: Int,
    request: AppOpenRequest,
    colors: WidgetColors,
    texts: Context,
) {
    Box(
        modifier =
            GlanceModifier
                .fillMaxHeight()
                .background(colors.border)
                .cornerRadius(12.dp)
                .padding(1.dp)
                .clickable(open(texts, request)),
    ) {
        Box(
            modifier =
                GlanceModifier
                    .fillMaxHeight()
                    .background(colors.surface)
                    .cornerRadius(11.dp)
                    .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = texts.getString(label),
                style = textStyle(colors.text, fontSize = 14, weight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

/**
 * `Decir`: un micrófono de 48 dp que abre `Decir o escribir`. Va solo con icono para que quepa
 * junto a los demás en el tamaño 4 x 1; TalkBack lo nombra. No muestra nada del negocio, y con el
 * bloqueo de la app la hoja espera al desbloqueo, como los demás destinos.
 */
@GlanceComposable
@Composable
private fun SayAction(colors: WidgetColors, texts: Context) {
    Box(
        modifier =
            GlanceModifier
                .size(48.dp)
                .background(colors.border)
                .cornerRadius(12.dp)
                .padding(1.dp)
                .clickable(open(texts, AppOpenRequest.Phrase)),
    ) {
        Box(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .background(colors.surface)
                    .cornerRadius(11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_mic),
                contentDescription = texts.getString(R.string.widget_phrase),
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(colors.text),
            )
        }
    }
}

/** `12 ventas · 2 por agotarse`, o solo las ventas si nada se está acabando. Nunca un monto. */
private fun countsLine(summary: WidgetContent.Summary, texts: Context): String {
    val resources = texts.resources
    val salesCount = summary.salesCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val sales = resources.getQuantityString(R.plurals.stats_sales_count, salesCount, salesCount)
    if (summary.lowStockCount == 0) return sales
    val low =
        resources.getQuantityString(
            R.plurals.widget_low_stock_count,
            summary.lowStockCount,
            summary.lowStockCount,
        )
    return texts.getString(R.string.widget_counts, sales, low)
}

private fun textStyle(
    color: ColorProvider,
    fontSize: Int,
    weight: FontWeight = FontWeight.Normal,
): TextStyle = TextStyle(color = color, fontSize = fontSize.sp, fontWeight = weight)

/**
 * Abre la actividad principal con [request], un destino de la lista cerrada: la actividad lo lee
 * con `AppOpenRequest.readFrom` y descarta cualquier otro.
 */
private fun open(context: Context, request: AppOpenRequest): Action {
    val intent =
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    request.writeTo(intent)
    return actionStartActivity(intent)
}
