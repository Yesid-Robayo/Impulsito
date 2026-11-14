package io.github.yesidrobayo.shopledger.ui.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptKind
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptLine
import io.github.yesidrobayo.shopledger.ui.document.ReceiptAssets
import io.github.yesidrobayo.shopledger.ui.document.ReceiptRenderer
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * [document] dibujado como lo dibuja el PDF, a un punto por dp, fuera del hilo principal, o `null`
 * mientras se dibuja. Con cada cambio espera un momento antes de dibujar: al escribir, solo se
 * dibuja lo último. [logo] es la imagen del logo, que se lee de su archivo.
 */
@Composable
fun rememberReceiptImage(
    document: ReceiptDocument,
    logo: StoredImage?,
    assets: ReceiptAssets,
): ImageBitmap? {
    val context = LocalContext.current
    val formats = LocalAppFormats.current
    val palette = AppTheme.palette
    val scale = LocalDensity.current.density
    val renderer = remember(context, formats, palette) {
        ReceiptRenderer(context, formats, palette)
    }
    val image by produceState<ImageBitmap?>(null, renderer, document, logo, assets, scale) {
        delay(RENDER_DEBOUNCE_MILLIS)
        value = withContext(Dispatchers.Default) {
            val withLogo = assets.copy(logo = logo?.let { decodeStored(context, it) })
            renderer.renderImage(document, withLogo, scale).asImageBitmap()
        }
    }
    return image
}

/**
 * La imagen guardada [image] como `Bitmap`, para dibujarla en un documento, o `null` si su archivo
 * falta o no se lee. Solo lee archivos que la app escribió, ya recodificados y con tope de tamaño.
 */
internal fun decodeStored(context: Context, image: StoredImage): Bitmap? =
    BitmapFactory.decodeFile(File(File(context.filesDir, StoredImage.FOLDER), image.fileName).path)

/**
 * Un comprobante de muestra con la plantilla que se está escribiendo: dos productos inventados,
 * pagados en efectivo, con los textos del idioma activo. Las cifras se ajustan a los decimales
 * de la moneda, para que se lean como precios: `8.400` sin decimales y `8,40` con dos.
 */
@Composable
fun rememberSampleReceipt(
    businessName: String?,
    logoMediaId: Long?,
    details: String,
    footer: String,
): ReceiptDocument {
    val first = stringResource(R.string.receipt_sample_product_first)
    val second = stringResource(R.string.receipt_sample_product_second)
    val cash = stringResource(R.string.seed_payment_methods_cash)
    val decimals = LocalAppFormats.current.money.currencyDecimals
    return remember(first, second, cash, decimals, businessName, logoMediaId, details, footer) {
        val firstAmount = sampleAmount(FIRST_AMOUNT, decimals)
        val secondAmount = sampleAmount(SECOND_AMOUNT, decimals)
        val total = firstAmount + secondAmount
        ReceiptDocument(
            kind = ReceiptKind.SALE,
            number = SAMPLE_NUMBER,
            voided = false,
            header = ReceiptHeader(
                logoMediaId = logoMediaId,
                businessName = businessName,
                businessDetails = details.trim().takeIf { it.isNotEmpty() },
            ),
            businessDate = SAMPLE_DATE,
            occurredAt = SAMPLE_AT,
            customerName = null,
            lines = listOf(
                ReceiptLine(first, QuantityMilli.ofUnits(2L), SAMPLE_UNIT, firstAmount, false),
                ReceiptLine(second, QuantityMilli.ofUnits(1L), SAMPLE_UNIT, secondAmount, false),
            ),
            amounts = listOf(
                ReceiptAmount.Subtotal(total),
                ReceiptAmount.Total(total),
                ReceiptAmount.Payment(cash, total),
            ),
            footerText = footer.trim().takeIf { it.isNotEmpty() },
        )
    }
}

/**
 * [whole] en la unidad menor de una moneda con [decimals] decimales: entero sin decimales, y con
 * decimales, la centésima parte, como `8,40`.
 */
private fun sampleAmount(whole: Long, decimals: Int): Money {
    if (decimals == 0) return Money(whole)
    var minor = whole
    repeat(decimals) { minor *= DECIMAL_BASE }
    return Money(minor / HUNDRED)
}

/** Lo que tarda en dibujarse de nuevo tras un cambio: menos de lo que se nota al escribir. */
private const val RENDER_DEBOUNCE_MILLIS = 150L
private const val DECIMAL_BASE = 10L
private const val HUNDRED = 100L
private const val FIRST_AMOUNT = 8_400L
private const val SECOND_AMOUNT = 12_500L
private const val SAMPLE_NUMBER = 1_204L

/** Una unidad que no existe: el comprobante usa la genérica. */
private const val SAMPLE_UNIT = 0L
private val SAMPLE_DATE = BusinessDate(LocalDate(2026, 10, 28))
private val SAMPLE_AT = Instant.fromEpochMilliseconds(1_793_198_520_000L)
