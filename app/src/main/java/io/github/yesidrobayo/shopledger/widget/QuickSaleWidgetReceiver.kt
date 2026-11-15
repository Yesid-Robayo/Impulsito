package io.github.yesidrobayo.shopledger.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * El receptor del widget. Va sin exportar: el sistema le entrega igual sus difusiones, y ninguna
 * otra app le puede enviar nada. Solo atiende las del widget y redibuja.
 */
class QuickSaleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickSaleWidget()
}
