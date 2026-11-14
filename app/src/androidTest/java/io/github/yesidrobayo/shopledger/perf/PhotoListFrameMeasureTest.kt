package io.github.yesidrobayo.shopledger.perf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.ui.components.PhotoState
import io.github.yesidrobayo.shopledger.ui.components.PhotoThumbSize
import io.github.yesidrobayo.shopledger.ui.media.LocalPhotoSource
import io.github.yesidrobayo.shopledger.ui.media.PhotoCache
import io.github.yesidrobayo.shopledger.ui.media.PhotoSource
import io.github.yesidrobayo.shopledger.ui.product.ProductListContent
import io.github.yesidrobayo.shopledger.ui.product.ProductListItems
import io.github.yesidrobayo.shopledger.ui.product.ProductListScreen
import io.github.yesidrobayo.shopledger.ui.product.ProductListUiState
import io.github.yesidrobayo.shopledger.ui.product.SAMPLE_UNIT
import io.github.yesidrobayo.shopledger.ui.product.sampleProduct
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La lista de productos con mil fotos de verdad, todas en el teléfono, desplazada con el dedo
 * durante unos segundos: cuenta los fotogramas y los lentos, de más de 16,7 ms, con las métricas
 * de la ventana. La misma lista sin fotos se mide igual, para comparar en el mismo equipo. En un
 * emulador solo orienta; el valor que manda es el del teléfono de referencia.
 *
 * Las fotos son inventadas, dibujadas por la prueba. Solo se registran conteos y duraciones.
 */
@RunWith(AndroidJUnit4::class)
class PhotoListFrameMeasureTest {
    // Con la regla de siempre, las corrutinas de la composición corren sin despachar: cada foto que
    // vuelve de leerse en disco seguía en ese hilo y recomponía desde ahí, a la vez que el hilo
    // principal aplicaba cambios, y Compose lo rechaza. Con esta regla, la foto vuelve a la cola de
    // la prueba y se aplica en el hilo principal, como en la app.
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var media: File
    private val frames = AtomicInteger()
    private val slow = AtomicInteger()
    private val thread = HandlerThread("metricas-de-fotogramas")

    @Before
    fun createFolder() {
        media = File(compose.activity.cacheDir, "photo-list-frames").apply { mkdirs() }
        thread.start()
    }

    @After
    fun deleteFolder() {
        thread.quitSafely()
        media.deleteRecursively()
    }

    @Test
    fun aThousandPhotosScrollWithFewSlowFrames() = measure(withPhotos = true)

    @Test
    fun theSameListWithoutPhotosForComparison() = measure(withPhotos = false)

    private fun measure(withPhotos: Boolean) {
        val ids = if (withPhotos) (1..PRODUCTS) else IntRange.EMPTY
        val index = ids.associate { it.toLong() to writePhoto(it) }
        val catalog =
            (1..PRODUCTS)
                .map { sampleProduct(it).withPhoto(if (withPhotos) it.toLong() else null) }
                .sortedBy { it.details.normalizedName }
        val units = mapOf(SAMPLE_UNIT.id to SAMPLE_UNIT)
        val content = ProductListItems.build(catalog, units, "") as ProductListContent.Products
        val cache = PhotoCache({ media }, Dispatchers.IO) { PhotoState.Unavailable }
        compose.setContent {
            PreviewTheme {
                CompositionLocalProvider(LocalPhotoSource provides PhotoSource(index, cache)) {
                    ProductListScreen(
                        uiState = ProductListUiState(content = content),
                        onAction = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        val listener =
            Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frames.incrementAndGet()
                if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > FRAME_BUDGET_NANOS) {
                    slow.incrementAndGet()
                }
            }
        val window = compose.activity.window
        compose.runOnUiThread {
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }
        val list = compose.onNode(hasScrollAction())
        repeat(SWIPES) { step ->
            // Dos tercios hacia abajo de la lista, y el resto de vuelta, en cada vuelta.
            val forward = step % DIRECTION_CHANGE < DOWN_SWIPES
            list.performTouchInput { if (forward) swipeUp() else swipeDown() }
            compose.waitForIdle()
        }
        compose.runOnUiThread { window.removeOnFrameMetricsAvailableListener(listener) }

        // Las fotos que llegaron a la caché: sin ninguna, la medida con fotos no midió fotos.
        val side = with(compose.density) { PhotoThumbSize.ROW.side.roundToPx() }
        val read = index.values.count { cache.peek(it.sha256, side) != null }
        val total = frames.get()
        val late = slow.get()
        val percent = if (total == 0) 0.0 else late * 100.0 / total
        val shown = "%.1f".format(percent)
        val label = if (withPhotos) "con foto" else "sin foto"
        Log.i(TAG, "$PRODUCTS productos $label: $total fotogramas, $late lentos ($shown %)")
        Log.i(TAG, "$read fotos leídas")
        assertTrue("Sin fotogramas medidos", total > 0)
        assertTrue("Ninguna foto llegó a la lista", !withPhotos || read > 0)
    }

    private fun Product.withPhoto(id: Long?) = copy(details = details.copy(photoMediaId = id))

    /** Una foto inventada de 640 x 480, como la dejaría el importador. */
    private fun writePhoto(seed: Int): StoredImage {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val random = Random(seed)
        Canvas(bitmap).drawColor(
            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)),
        )
        val bytes = ByteArrayOutputStream().also {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, 80, it)
        }.toByteArray()
        bitmap.recycle()
        val sha =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        File(media, StoredImage.fileNameOf(sha)).writeBytes(bytes)
        return StoredImage(sha, "image/webp", 640, 480, bytes.size.toLong())
    }

    private companion object {
        const val TAG = "PhotoListFrameMeasure"
        const val PRODUCTS = 1_000
        const val SWIPES = 60
        const val DIRECTION_CHANGE = 30
        const val DOWN_SWIPES = 20
        const val FRAME_BUDGET_NANOS = 16_666_667L
    }
}
