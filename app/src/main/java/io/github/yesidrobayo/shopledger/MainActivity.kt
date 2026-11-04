package io.github.yesidrobayo.shopledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.ui.theme.ShopLedgerTheme
import kotlinx.coroutines.launch

/**
 * La única actividad de la app. Por ahora muestra una pantalla provisional con el nombre del
 * negocio, que lee de los ajustes por el contenedor, o el de la app mientras no hay uno.
 *
 * La base se abre fuera del hilo principal: la pantalla aparece enseguida y el nombre llega cuando
 * la base está lista. Si la base no abre, la pantalla sigue con el nombre de la app; la pantalla
 * de rescate llega con la compuerta del arranque.
 */
class MainActivity : ComponentActivity() {
    private val shownName = mutableStateOf<String?>(null)

    /** El nombre del negocio que leyó de los ajustes, o `null` si todavía no hay uno. */
    internal val businessName: State<String?> get() = shownName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ShopLedgerApp).container
        lifecycleScope.launch {
            when (val access = container.data.open()) {
                is DataAccess.Ready ->
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        access.ports.settings.observe().collect { settings ->
                            shownName.value = settings.profile.businessName
                        }
                    }

                // La causa ya quedó registrada por su clase.
                DataAccess.Unavailable -> Unit
            }
        }
        setContent {
            ShopLedgerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppName(
                        businessName = shownName.value,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

/**
 * Pantalla provisional del esqueleto: el nombre del negocio si la persona ya lo escribió, o el
 * nombre visible de la app, que sale de los recursos.
 */
@Composable
fun AppName(businessName: String?, modifier: Modifier = Modifier) {
    Text(
        text = businessName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.app_name),
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
fun AppNamePreview() {
    ShopLedgerTheme {
        AppName(businessName = null)
    }
}
