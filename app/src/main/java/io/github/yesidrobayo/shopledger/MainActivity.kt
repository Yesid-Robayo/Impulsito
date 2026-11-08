package io.github.yesidrobayo.shopledger

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.navigation.AppNavHost
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
 * colores. Mientras la compuerta del arranque copia y migra, también se ve solo ese papel. Si la
 * compuerta manda al rescate, la navegación sigue con la paleta predeterminada y el tema del
 * teléfono, hasta que exista la pantalla de rescate.
 *
 * Es una [AppCompatActivity] porque en Android 8 a 12 el idioma elegido dentro de la app solo se
 * aplica a ese tipo de actividad. Sigue siendo una `FragmentActivity`, así que el desbloqueo con
 * huella o rostro también funciona aquí.
 */
class MainActivity : AppCompatActivity() {
    private val shownAppearance = mutableStateOf<Appearance?>(null)
    private val dataUnavailable = mutableStateOf(false)

    /** La paleta y el modo que leyó de los ajustes, o `null` mientras la base no responde. */
    internal val appearance: State<Appearance?> get() = shownAppearance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Solo en debug: un extra de adb abre el arnés del respaldo encima. En release no hace nada.
        if (savedInstanceState == null) DebugEntry.openIfRequested(this, intent)
        val container = (application as ShopLedgerApp).container
        lifecycleScope.launch {
            when (val access = container.data.open()) {
                is DataAccess.Ready ->
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        access.ports.settings
                            .observe()
                            .map {
                                Appearance(it.preferences.colorPalette, it.preferences.themeMode)
                            }
                            .distinctUntilChanged()
                            .collect { shownAppearance.value = it }
                    }

                // La causa ya quedó registrada. La pantalla de rescate todavía no existe: hasta
                // entonces sigue la navegación, que no lee la base.
                is DataAccess.Rescue -> dataUnavailable.value = true
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
                if (appearance == null && !dataUnavailable.value) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                } else {
                    AppNavHost()
                }
            }
        }
    }
}
