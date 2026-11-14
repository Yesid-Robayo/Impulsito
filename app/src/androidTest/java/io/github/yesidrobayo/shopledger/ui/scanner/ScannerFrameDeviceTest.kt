package io.github.yesidrobayo.shopledger.ui.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.di.ScannerTelemetryLog
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El escáner de verdad, sin la cámara: cuadros inventados pasan por el mismo analizador y el mismo
 * estado que usa la cámara, y salen como códigos entregados.
 *
 * Cada cuadro imita lo que entrega la cámara de un teléfono en vertical: un plano de luminancia de
 * 1280 × 720 con relleno al final de cada fila, girado 90 grados y con algo de ruido. Los códigos
 * son inventados. Mide cuánto tarda la lectura de cada cuadro, que es la parte del tiempo del
 * escáner que depende de la app; el resto es lo que tarda la cámara en abrir.
 */
@RunWith(AndroidJUnit4::class)
class ScannerFrameDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext

    @Test
    fun theAnalyzerReadsEachFormatAndKeepsNoFile() {
        val before = appFiles()
        val outcomes = mutableListOf<FrameOutcome>()
        val analyzer = BarcodeFrameAnalyzer(onFrame = { outcomes += it })

        for (sample in SAMPLES) {
            val frame = frameWith(sample)
            repeat(ITERATIONS) {
                outcomes.clear()
                val image = FakeImage(frame)
                analyzer.analyze(image.proxy)
                assertTrue("el analizador cierra el cuadro", image.closed)
                assertEquals(sample.code.format.name, sample.code, outcomes.single().code)
                val decode = outcomes.single().decodeNanos
                times.getOrPut(sample.code.format.name) { mutableListOf() } += decode
            }
        }
        val empty = frameWith(null)
        repeat(ITERATIONS) {
            outcomes.clear()
            analyzer.analyze(FakeImage(empty).proxy)
            assertEquals("ningún código en un cuadro sin código", null, outcomes.single().code)
        }

        for ((format, nanos) in times) Log.i(TAG, "lectura por cuadro: $format ${summary(nanos)}")
        assertEquals("el análisis no deja archivos", before, appFiles())
    }

    @Test
    fun aHeldCodeIsDeliveredOnceAndAnotherRightAfter() {
        val delivered = mutableListOf<String>()
        var now = 0L
        val model =
            ScannerViewModel(
                continuous = true,
                deliver = { delivered += it.code },
                telemetry = ScannerTelemetryLog(NoLog),
                nanoTime = { now },
            )
        val analyzer = model.openSession()
        model.onCameraBound(hasTorch = false)
        val ean = frameWith(SAMPLES[0])
        val own = frameWith(SAMPLES[1])

        // Treinta cuadros por segundo: el mismo producto un segundo delante, y luego otro.
        repeat(FRAMES_PER_SECOND) {
            analyzer.analyze(FakeImage(ean).proxy)
            now += FRAME_NANOS
        }
        analyzer.analyze(FakeImage(own).proxy)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {}

        assertEquals(listOf(SAMPLES[0].code.text, SAMPLES[1].code.text), delivered)
        model.closeSession()
    }

    private val times = mutableMapOf<String, MutableList<Long>>()

    private fun summary(nanos: List<Long>): String {
        val sorted = nanos.sorted()
        fun millis(index: Int) = "%.2f".format(Locale.ROOT, sorted[index] / NANOS_PER_MILLI)
        val p95 = (sorted.size * 95 + 99) / 100 - 1
        return "n=${sorted.size} mediana=${millis(sorted.size / 2)} ms " +
            "p95=${millis(p95)} ms máx=${millis(sorted.lastIndex)} ms"
    }

    private fun frameWith(sample: Sample?): LumaFrame {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(138, 120, 96))
        if (sample != null) {
            val code = loadAsset(sample.asset)
            val scale = WIDTH * sample.widthFraction / code.width
            // El cuadro llega de lado: un código derecho en la pantalla llega girado 90 grados.
            val matrix =
                Matrix().apply {
                    postTranslate(-code.width / 2f, -code.height / 2f)
                    postScale(scale, scale)
                    postRotate(90f)
                    postTranslate(WIDTH / 2f, HEIGHT / 2f)
                }
            canvas.drawBitmap(code, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return LumaFrame.fromBitmap(bitmap, seed = WIDTH + HEIGHT)
    }

    private fun loadAsset(name: String): Bitmap =
        instrumentation.context.assets.open("scanner/$name").use { stream ->
            requireNotNull(BitmapFactory.decodeStream(stream)) { "No se leyó $name" }
        }

    /** Los archivos de la app, salvo la marca que escribe el instalador de perfiles al abrirla. */
    private fun appFiles(): Set<String> = listOfNotNull(
        targetContext.filesDir,
        targetContext.cacheDir,
        targetContext.noBackupFilesDir,
        targetContext.getExternalFilesDir(null),
        targetContext.externalCacheDir,
    ).flatMap { root -> root.walkTopDown().filter(File::isFile).toList() }
        .filterNot { it.name == PROFILE_INSTALLED_MARKER }
        .map { it.absolutePath }
        .toSet()

    private data class Sample(val code: ScannedCode, val asset: String, val widthFraction: Float)

    /** El plano de luminancia, con el relleno de fila que suele traer el búfer de una cámara. */
    private class LumaFrame(
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val bytes: ByteArray,
    ) {
        companion object {
            fun fromBitmap(bitmap: Bitmap, seed: Int): LumaFrame {
                val width = bitmap.width
                val height = bitmap.height
                val rowStride = (width + 63) / 64 * 64 + 64
                val bytes = ByteArray(rowStride * height)
                val pixels = IntArray(width)
                val random = Random(seed)
                for (y in 0 until height) {
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                    for (x in 0 until width) {
                        val p = pixels[x]
                        // Luminancia de rango limitado, como la entrega la cámara, con ruido.
                        val weighted =
                            66 * Color.red(p) + 129 * Color.green(p) + 25 * Color.blue(p)
                        val luma = ((weighted + 128) shr 8) + 16 + random.nextInt(-10, 11)
                        bytes[y * rowStride + x] = luma.coerceIn(0, 255).toByte()
                    }
                }
                return LumaFrame(width, height, rowStride, bytes)
            }
        }
    }

    /**
     * Un cuadro de prueba con un solo plano útil, el de luminancia, que es el único que lee el
     * lector. Se arma con un proxy dinámico para no depender de las clases internas de la cámara.
     */
    private class FakeImage(frame: LumaFrame) {
        var closed = false
            private set

        private val buffer: ByteBuffer =
            ByteBuffer.allocateDirect(frame.bytes.size).put(frame.bytes).apply { rewind() }
        private val plane =
            proxy<ImageProxy.PlaneProxy> { name ->
                when (name) {
                    "getBuffer" -> buffer
                    "getRowStride" -> frame.rowStride
                    "getPixelStride" -> 1
                    else -> null
                }
            }
        private val info =
            proxy<ImageInfo> { name ->
                when (name) {
                    "getRotationDegrees" -> 90
                    "getTimestamp" -> 0L
                    "getSensorToBufferTransformMatrix" -> Matrix()
                    "getFlashState" -> 0
                    else -> null
                }
            }
        val proxy =
            proxy<ImageProxy> { name ->
                when (name) {
                    "getWidth" -> frame.width

                    "getHeight" -> frame.height

                    "getFormat" -> ImageFormat.YUV_420_888

                    "getCropRect" -> Rect(0, 0, frame.width, frame.height)

                    "getPlanes" -> arrayOf(plane, plane, plane)

                    "getImageInfo" -> info

                    "close" -> {
                        closed = true
                        null
                    }

                    else -> null
                }
            }

        private inline fun <reified T> proxy(crossinline answer: (String) -> Any?): T {
            val handler =
                InvocationHandler { self, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(self)
                        "equals" -> self === args?.firstOrNull()
                        "toString" -> "FakeImage"
                        else -> answer(method.name)
                    }
                }
            val loader = T::class.java.classLoader
            return Proxy.newProxyInstance(loader, arrayOf(T::class.java), handler) as T
        }
    }

    private object NoLog : EventLog {
        override fun write(level: LogLevel, line: LogLine) = Unit
    }

    private companion object {
        const val TAG = "ScannerFrameDeviceTest"
        const val PROFILE_INSTALLED_MARKER = "profileInstalled"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val ITERATIONS = 30
        const val FRAMES_PER_SECOND = 30
        const val FRAME_NANOS = 1_000_000_000L / FRAMES_PER_SECOND
        const val NANOS_PER_MILLI = 1_000_000.0
        const val QR_TEXT = "demo:item=0042;lot=7;qty=1"
        val SAMPLES =
            listOf(
                Sample(ScannedCode(ScanFormat.EAN_13, "2900000123458"), "ean13.png", 0.5f),
                Sample(ScannedCode(ScanFormat.CODE_128, "P00000042"), "code128.png", 0.5f),
                Sample(ScannedCode(ScanFormat.QR_CODE, QR_TEXT), "qr.png", 0.3f),
            )
    }
}
