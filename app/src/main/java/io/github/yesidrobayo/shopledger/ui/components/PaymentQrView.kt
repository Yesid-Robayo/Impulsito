package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La imagen de cobro a pantalla completa, para enseñarla a quien paga en el mismo teléfono: sin
 * barras, sobre el papel, con lo que se paga, la imagen a lo ancho y las instrucciones.
 *
 * - La imagen va sobre un margen claro de 16 dp, para que un lector la reconozca también en tema
 *   oscuro.
 * - Mantiene la pantalla encendida mientras se ve, y no cambia el brillo.
 * - Nunca muestra comisión, neto ni costo: la está mirando quien paga.
 * - La app no lee ningún pago: la pantalla pide revisar que llegó antes de registrar.
 *
 * @param image la imagen, o `null` si el método solo tiene instrucciones o no se pudo leer.
 * @param imageDescription lo que TalkBack dice de la imagen.
 */
@Composable
fun PaymentQrView(
    amount: Money?,
    image: ImageBitmap?,
    imageDescription: String,
    instructions: String?,
    onRegister: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    registering: Boolean = false,
) {
    val spacing = AppTheme.spacing
    Surface(
        modifier.fillMaxSize().keepScreenOn(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.s16, vertical = spacing.s24),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.s16),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.checkout_qr_total),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyText(amount, MoneySize.LARGE)
                }
                if (image != null) {
                    Box(
                        Modifier
                            .widthIn(max = MAX_IMAGE_WIDTH)
                            .fillMaxWidth()
                            .background(AppTheme.colors.onHero, MaterialTheme.shapes.medium)
                            .padding(spacing.s16),
                    ) {
                        Image(
                            bitmap = image,
                            contentDescription = imageDescription,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                instructions?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    stringResource(R.string.checkout_qr_check),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(
                Modifier.padding(spacing.s16),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.sale_register),
                    onClick = onRegister,
                    variant = ActionVariant.PRIMARY,
                    state = if (registering) ActionState.WORKING else ActionState.IDLE,
                )
                PrimaryActionButton(
                    text = stringResource(R.string.action_back),
                    onClick = onBack,
                    variant = ActionVariant.TONAL,
                )
            }
        }
    }
}

/** En una tableta, la imagen se centra a este ancho: más grande no se lee mejor. */
private val MAX_IMAGE_WIDTH = 480.dp

@ThemePreviews
@Composable
private fun PaymentQrViewPreview() {
    PreviewTheme {
        PaymentQrView(
            amount = Money(1_234_567_890L),
            image = null,
            imageDescription = PreviewSamples.METHOD_TRANSFER,
            instructions = PreviewSamples.LONG_NAME,
            onRegister = {},
            onBack = {},
        )
    }
}
