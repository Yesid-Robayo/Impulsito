package io.github.yesidrobayo.shopledger

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.OpeningStep
import io.github.yesidrobayo.shopledger.data.RescueCause
import io.github.yesidrobayo.shopledger.navigation.AppNavHost
import io.github.yesidrobayo.shopledger.navigation.AppOpenRequest
import io.github.yesidrobayo.shopledger.ui.format.BusinessDisplay
import io.github.yesidrobayo.shopledger.ui.format.ProvideBusinessFormats
import io.github.yesidrobayo.shopledger.ui.rescue.OpeningScreen
import io.github.yesidrobayo.shopledger.ui.rescue.OpeningStage
import io.github.yesidrobayo.shopledger.ui.rescue.RescueReason
import io.github.yesidrobayo.shopledger.ui.rescue.RescueRoute
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.Appearance
import io.github.yesidrobayo.shopledger.ui.theme.DefaultColorPalette
import io.github.yesidrobayo.shopledger.ui.theme.isDark
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * La única actividad de la app: aplica la paleta y el modo que eligió la persona, y muestra la
 * navegación.
 *
 * La paleta y el modo viven en la base, que se abre fuera del hilo principal. Hasta leerlos se ve
 * solo el papel de la paleta predeterminada, sin contenido: así no aparece un destello de otros
 * colores. Mientras la compuerta del arranque copia y migra, o termina una restauración, se dice
 * sobre ese papel, sin cifras. Si la compuerta manda al rescate, la pantalla de rescate reemplaza a
 * toda la navegación, con la paleta predeterminada y el tema del teléfono: los guardados viven en
 * la base, que no se lee.
 *
 * Con la paleta llegan el formato regional, la moneda y el modo discreto: toda pantalla con cifras
 * los recibe de aquí, y cambian a la vez cuando la persona los cambia.
 *
 * Es una [AppCompatActivity] porque en Android 8 a 12 el idioma elegido dentro de la app solo se
 * aplica a ese tipo de actividad. Sigue siendo una `FragmentActivity`, así que el desbloqueo con
 * huella o rostro también funciona aquí.
 */
class MainActivity : AppCompatActivity() {
    private val shownAppearance = mutableStateOf<Appearance?>(null)
    private val shownDisplay = mutableStateOf<BusinessDisplay?>(null)
    private val rescueReason = mutableStateOf<RescueReason?>(null)
    private val openingStage = mutableStateOf(OpeningStage.OPENING)
    private val onboardingPending = mutableStateOf(false)
    private val openRequest = mutableStateOf<AppOpenRequest?>(null)

    /** La paleta y el modo que leyó de los ajustes, o `null` mientras la base no responde. */
    internal val appearance: State<Appearance?> get() = shownAppearance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Solo en debug: un extra de adb abre el arnés del respaldo encima. En release no hace nada.
        if (savedInstanceState == null) DebugEntry.openIfRequested(this, intent)
        // Un aviso de stock abre su producto. Al recrearse la actividad, el pedido ya se cumplió.
        if (savedInstanceState == null) openRequest.value = AppOpenRequest.readFrom(intent)
        val container = (application as ShopLedgerApp).container
        // Lo que hace la compuerta mientras se espera la base: copiar y migrar, o restaurar.
        lifecycleScope.launch {
            container.data.opening.collect { openingStage.value = it.toStage() }
        }
        lifecycleScope.launch {
            when (val access = container.data.open()) {
                is DataAccess.Ready -> {
                    // Desde aquí se avisa lo que quedó pendiente y cada alerta nueva.
                    container.lowStockAlertDelivery.follow(access.ports)
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        access.ports.settings
                            .observe()
                            .map {
                                Triple(
                                    Appearance(
                                        it.preferences.colorPalette,
                                        it.preferences.themeMode,
                                    ),
                                    BusinessDisplay.of(it),
                                    it.onboardingCompletedAt == null,
                                )
                            }
                            .distinctUntilChanged()
                            .collect { (appearance, display, pending) ->
                                // El primer uso se decide antes de mostrar la navegación.
                                onboardingPending.value = pending
                                shownAppearance.value = appearance
                                shownDisplay.value = display
                            }
                    }
                }

                // La causa ya quedó registrada. El rescate no lee la base ni navega.
                is DataAccess.Rescue -> rescueReason.value = access.cause.toReason()
            }
        }
        setContent {
            val appearance = shownAppearance.value
            val darkTheme = appearance.isDark(systemDark = isSystemInDarkTheme())
            // Los iconos de las barras del sistema siguen al modo de la app, no al del teléfono: con
            // el modo fijado al revés del sistema, se perderían sobre el papel.
            DisposableEffect(darkTheme) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AppTheme(palette = appearance?.palette ?: DefaultColorPalette, darkTheme = darkTheme) {
                val reason = rescueReason.value
                if (reason != null) {
                    RescueRoute(reason)
                } else if (appearance == null) {
                    OpeningScreen(openingStage.value)
                } else {
                    ProvideBusinessFormats(shownDisplay.value) {
                        AppNavHost(
                            onboardingPending = onboardingPending.value,
                            openRequest = openRequest.value,
                            onOpenHandled = { openRequest.value = null },
                        )
                    }
                }
            }
        }
    }

    /** Con la app abierta, tocar un aviso de stock llega aquí. Lo desconocido no cambia nada. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppOpenRequest.readFrom(intent)?.let { openRequest.value = it }
    }
}

/** La causa del rescate en los términos de la pantalla, que no ve la capa de datos. */
private fun RescueCause.toReason(): RescueReason = when (this) {
    RescueCause.DatabaseMissing -> RescueReason.DatabaseMissing
    RescueCause.NewerVersion -> RescueReason.NewerVersion
    RescueCause.OpenFailed -> RescueReason.OpenFailed
    RescueCause.MigrationFailed -> RescueReason.MigrationFailed
    RescueCause.PreMigrationCopyFailed -> RescueReason.PreMigrationCopyFailed
    is RescueCause.NotEnoughSpace -> RescueReason.NotEnoughSpace(missingBytes)
    RescueCause.RestoreJournalUnreadable -> RescueReason.RestoreJournalUnreadable
    RescueCause.RestoreLeftNoDatabase -> RescueReason.RestoreLeftNoDatabase
}

/** Lo que hace la compuerta, en los términos de la pantalla. */
private fun OpeningStep.toStage(): OpeningStage = when (this) {
    OpeningStep.OPENING -> OpeningStage.OPENING
    OpeningStep.MIGRATING -> OpeningStage.MIGRATING
    OpeningStep.RESTORING -> OpeningStage.RESTORING
}
