package io.github.yesidrobayo.shopledger.shortcut

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.yesidrobayo.shopledger.MainActivity
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.warn
import io.github.yesidrobayo.shopledger.localization.LocalizedSurface
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest
import io.github.yesidrobayo.shopledger.surface.SurfaceEvent

/**
 * Los atajos de la app al mantener pulsado su icono: `Vender`, `Gasto` y `Compra`, en ese orden.
 * Son tres, y queda libre uno de los cuatro que se ven.
 *
 * - **Dinámicos, no estáticos.** Android resuelve los de un archivo del manifiesto con el idioma
 *   del teléfono; estos se publican con los textos del idioma de la interfaz. Por eso se vuelven a
 *   publicar con cada cambio de idioma o de versión, como las demás superficies, y aparecen desde
 *   la primera vez que se abre la app, no recién instalada.
 * - **Abren la actividad principal con un pedido de su lista cerrada,** con un `Intent` explícito:
 *   sin esquemas ni enlaces. El bloqueo de la app sigue valiendo, y el destino espera a que la
 *   persona desbloquee.
 * - **Sus iconos no cambian con la paleta:** blanco sobre el índigo del ícono de la app.
 */
class AppShortcuts(private val app: Context, private val log: EventLog) : LocalizedSurface {
    override suspend fun refresh(texts: Context) {
        val shortcuts = SHORTCUTS.mapIndexed { rank, spec -> spec.build(texts, rank) }
        // Android puede limitar cuántas veces por día se publican: entonces devuelve falso y los
        // atajos anteriores siguen. Se vuelve a intentar con el siguiente cambio.
        if (!ShortcutManagerCompat.setDynamicShortcuts(app, shortcuts)) {
            log.warn(SurfaceEvent.SHORTCUTS_FAILED)
        }
    }

    private fun Shortcut.build(texts: Context, rank: Int): ShortcutInfoCompat {
        val intent =
            Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        request.writeTo(intent)
        return ShortcutInfoCompat
            .Builder(app, id)
            .setShortLabel(texts.getString(shortLabel))
            .setLongLabel(texts.getString(longLabel))
            .setIcon(IconCompat.createWithResource(app, icon))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    /** Un atajo: su id, en inglés y para siempre, sus textos, su icono y lo que abre. */
    private class Shortcut(
        val id: String,
        @param:StringRes val shortLabel: Int,
        @param:StringRes val longLabel: Int,
        @param:DrawableRes val icon: Int,
        val request: AppOpenRequest,
    )

    companion object {
        /** El id del atajo `Vender`. */
        const val ID_SALE = "new_sale"

        /** El id del atajo `Gasto`. */
        const val ID_EXPENSE = "new_expense"

        /** El id del atajo `Compra`. */
        const val ID_PURCHASE = "new_purchase"

        private val SHORTCUTS =
            listOf(
                Shortcut(
                    id = ID_SALE,
                    shortLabel = R.string.shortcut_sale_short,
                    longLabel = R.string.shortcut_sale_long,
                    icon = R.drawable.ic_shortcut_sale,
                    request = AppOpenRequest.NewSale,
                ),
                Shortcut(
                    id = ID_EXPENSE,
                    shortLabel = R.string.shortcut_expense_short,
                    longLabel = R.string.shortcut_expense_long,
                    icon = R.drawable.ic_shortcut_expense,
                    request = AppOpenRequest.NewExpense,
                ),
                Shortcut(
                    id = ID_PURCHASE,
                    shortLabel = R.string.shortcut_purchase_short,
                    longLabel = R.string.shortcut_purchase_long,
                    icon = R.drawable.ic_shortcut_purchase,
                    request = AppOpenRequest.NewPurchase,
                ),
            )
    }
}
