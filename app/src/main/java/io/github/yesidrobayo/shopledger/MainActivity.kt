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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.ui.theme.ShopLedgerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShopLedgerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppName(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/** Pantalla provisional del esqueleto: muestra el nombre visible, que sale de los recursos. */
@Composable
fun AppName(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.app_name),
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
fun AppNamePreview() {
    ShopLedgerTheme {
        AppName()
    }
}
