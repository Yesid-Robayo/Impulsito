package io.github.yesidrobayo.shopledger.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.yesidrobayo.shopledger.MainActivity
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ShopLedgerApp
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.error
import io.github.yesidrobayo.shopledger.domain.port.warn
import io.github.yesidrobayo.shopledger.localization.LocalizedSurface
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest
import io.github.yesidrobayo.shopledger.surface.SurfaceEvent

/**
 * El mosaico `Nueva venta` del panel rápido. Es solo una acción: su estado siempre es inactivo,
 * y nunca muestra datos del negocio, ni montos, ni conteos, ni nombres. Su título es fijo y no
 * tiene subtítulo, porque en Android 8 un texto del mosaico sigue a la vista si la persona
 * bloquea con el panel abierto.
 *
 * Con el teléfono bloqueado, la app solo se abre después del desbloqueo:
 * - **Desde Android 14** se abre directo, con un `PendingIntent`, y el sistema pide el PIN antes
 *   de abrir la actividad. Envolverlo en `unlockAndRun()` falla ahí: el sistema corre la acción
 *   cuando todavía cree que el bloqueo está a la vista, vuelve a pedirlo y la app no se abre.
 * - **Antes de Android 14** lo pide `unlockAndRun()`, y después se abre con el `Intent`.
 *
 * Abre la actividad principal con un pedido de su lista cerrada, así que el bloqueo de la app
 * sigue valiendo: la venta espera a que la persona desbloquee la app. Su etiqueta en el panel
 * sale del idioma de la interfaz; la del editor del panel la resuelve Android con el idioma del
 * teléfono.
 */
class NewSaleTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val texts = (application as ShopLedgerApp).container.localizedContext.context()
        tile.icon = Icon.createWithResource(this, R.drawable.ic_sell)
        tile.state = Tile.STATE_INACTIVE
        tile.label = texts.getString(R.string.tile_new_sale)
        tile.contentDescription = texts.getString(R.string.tile_new_sale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = null
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            openNewSale()
        } else if (isLocked) {
            unlockAndRun { openNewSale() }
        } else {
            openNewSale()
        }
    }

    private fun openNewSale() {
        val intent =
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        AppOpenRequest.NewSale.writeTo(intent)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending =
                    PendingIntent.getActivity(
                        this,
                        REQUEST_NEW_SALE,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                startActivityAndCollapse(pending)
            } else {
                startActivityAndCollapseBeforeApi34(intent)
            }
        } catch (failure: RuntimeException) {
            // Sin abrir no hay nada que deshacer: queda en el registro, solo con su clase.
            val log = (application as ShopLedgerApp).container.log
            log.error(SurfaceEvent.TILE_OPEN_FAILED, failure)
        }
    }

    /** Antes de Android 14 solo existe la variante con `Intent`. Desde Android 14 lanza error. */
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startActivityAndCollapseBeforeApi34(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private companion object {
        const val REQUEST_NEW_SALE = 1
    }
}

/**
 * Pide al panel que vuelva a escribir la etiqueta del mosaico, como al cambiar de idioma. Si el
 * mosaico no está en el panel, Android no lo llama, y no pasa nada más.
 */
class NewSaleTileSurface(private val app: Context, private val log: EventLog) : LocalizedSurface {
    override suspend fun refresh(texts: Context) {
        val tile = ComponentName(app, NewSaleTileService::class.java)
        try {
            TileService.requestListeningState(app, tile)
        } catch (failure: RuntimeException) {
            // Un panel que no lo acepta no impide escribir las demás superficies.
            log.warn(SurfaceEvent.TILE_REFRESH_FAILED, failure)
        }
    }
}
