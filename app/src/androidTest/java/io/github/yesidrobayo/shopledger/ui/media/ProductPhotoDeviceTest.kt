package io.github.yesidrobayo.shopledger.ui.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.ui.components.PhotoState
import io.github.yesidrobayo.shopledger.ui.product.ProductListAction
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
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las fotos de producto en el teléfono: la caché decodifica una miniatura del tamaño en que se
 * dibuja y una foto que falta nunca es un error, y la lista de 5000 productos, todos con foto, llega
 * hasta el último y responde a sus toques aunque muchas fotos estén pendientes o no disponibles.
 *
 * Las fotos son inventadas, dibujadas por la prueba. Solo se registran conteos y duraciones.
 */
@RunWith(AndroidJUnit4::class)
class ProductPhotoDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var media: File

    @Before
    fun createFolder() {
        media = File(context.cacheDir, "photo-test-" + Random.nextLong().toULong()).apply {
            mkdirs()
        }
    }

    @After
    fun deleteFolder() {
        media.deleteRecursively()
    }

    @Test
    fun theCacheDecodesASmallThumbnailAndAMissingFileIsNeverAnError() = runBlocking {
        val image = writePhoto(1)
        val cache = PhotoCache({ media }, Dispatchers.IO)

        val ready = cache.load(image.sha256, 120)
        val bitmap = (ready as PhotoState.Ready).bitmap
        // 640 x 480 muestreada a la cuarta parte: el lado menor queda justo en los 120 pedidos.
        assertEquals(160, bitmap.width)
        assertEquals(120, bitmap.height)
        assertNotNull(cache.peek(image.sha256, 120))
        assertNull(cache.peek(image.sha256, 48))

        val missing = "f".repeat(64)
        assertEquals(PhotoState.Unavailable, cache.load(missing, 120))
        val waiting = PhotoCache({ media }, Dispatchers.IO) { PhotoState.Pending }
        assertEquals(PhotoState.Pending, waiting.load(missing, 120))
        // Un archivo dañado tampoco es un error.
        File(media, StoredImage.fileNameOf(missing)).writeBytes(ByteArray(64))
        assertEquals(PhotoState.Unavailable, cache.load(missing, 120))
        cache.forget(image.sha256)
        assertNull(cache.peek(image.sha256, 120))
    }

    @Test
    fun fiveThousandProductsWithPhotosReachTheLastOneAndOpenIt() {
        // 300 fotos con su archivo, 300 sin archivo y el resto sin fila: todas las situaciones.
        val stored = (1..PHOTOS).associate { it.toLong() to writePhoto(it) }
        val missing = (PHOTOS + 1..PHOTOS * 2).associate { it.toLong() to fakeImage(it) }
        val index = stored + missing
        val units = mapOf(SAMPLE_UNIT.id to SAMPLE_UNIT)
        val catalog =
            (1..PRODUCTS)
                .map { sampleProduct(it).withPhoto(it.toLong()) }
                .sortedBy { it.details.normalizedName }
        val content = ProductListItems.build(catalog, units, "") as ProductListContent.Products
        val rows = content.sections.sumOf { it.items.size + if (it.initial == null) 0 else 1 }
        val pending = missing.values.mapTo(HashSet()) { it.sha256 }
        val cache =
            PhotoCache({ media }, Dispatchers.IO) { sha ->
                if (sha in pending) PhotoState.Pending else PhotoState.Unavailable
            }
        var opened: Long? = null
        compose.setContent {
            PreviewTheme {
                CompositionLocalProvider(LocalPhotoSource provides PhotoSource(index, cache)) {
                    ProductListScreen(
                        uiState = ProductListUiState(content = content),
                        onAction = {
                            if (it is ProductListAction.OpenProduct) opened = it.productId
                        },
                    )
                }
            }
        }

        val mark = TimeSource.Monotonic.markNow()
        val list = compose.onNode(hasScrollAction())
        for (step in 0 until rows step SCROLL_STEP) {
            list.performScrollToIndex(step)
            compose.waitForIdle()
        }
        list.performScrollToIndex(rows - 1)
        compose.waitForIdle()
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        Log.i(TAG, "recorrer $PRODUCTS productos con foto: $elapsed ms")

        val last = content.sections.last().items.last()
        compose.onNodeWithText(last.name).performClick()
        compose.runOnIdle { assertEquals(last.id, opened) }
        // Una foto que falta se dice, sin bloquear la fila.
        val unavailable = context.getString(R.string.media_unavailable)
        assertTrue(
            compose.onAllNodesWithContentDescription(unavailable, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun Product.withPhoto(id: Long) = copy(details = details.copy(photoMediaId = id))

    /** Escribe una foto inventada de 640 x 480, como la dejaría el importador. */
    private fun writePhoto(seed: Int): StoredImage {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val random = Random(seed)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
        }
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

    /** Una foto anotada en la base cuyo archivo no está en el teléfono. */
    private fun fakeImage(seed: Int): StoredImage =
        StoredImage("%064x".format(seed), "image/webp", 640, 480, 20_000L)

    private companion object {
        const val TAG = "ProductPhotoDeviceTest"
        const val PRODUCTS = 5_000
        const val PHOTOS = 300
        const val SCROLL_STEP = 40
    }
}
