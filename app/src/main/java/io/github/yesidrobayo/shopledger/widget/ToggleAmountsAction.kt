package io.github.yesidrobayo.shopledger.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState

/**
 * El ojo del widget: alterna si la persona quiere ver montos en ese widget y lo redibuja. La
 * elección vive en el estado de Glance de ese widget, fuera de la base, y no viaja en una copia.
 * Mostrar o no lo decide la regla al dibujar: con el teléfono bloqueado o el modo discreto, nunca.
 */
class ToggleAmountsAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        updateAppWidgetState(context, glanceId) { preferences ->
            val current = preferences[QuickSaleWidget.showAmountsKey] ?: false
            preferences[QuickSaleWidget.showAmountsKey] = !current
        }
        QuickSaleWidget().update(context, glanceId)
    }
}
