package io.github.yesidrobayo.shopledger.ui.scanner

import android.content.Context
import android.util.Size
import androidx.camera.camera2.Camera2Config
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.InitializationException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.awaitCancellation

/**
 * La configuración de la cámara para todo el proceso: solo la trasera.
 *
 * Sin el límite, la cámara revisa también la frontal al empezar; un equipo que la declara y no la
 * tiene, como algunos emuladores, reintenta su inicio varias veces y la primera apertura tarda
 * segundos. El escáner nunca usa la frontal. La lee la cámara la primera vez que se la pide, desde
 * la aplicación.
 */
fun scannerCameraConfig(): CameraXConfig = CameraXConfig.Builder
    .fromConfig(Camera2Config.defaultConfig())
    .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
    .build()

/** Tamaño pedido para el análisis, del sensor. Si no lo tiene, la cámara elige el más cercano. */
private val ANALYSIS_SIZE = Size(1280, 720)

/**
 * La vista de la cámara trasera y su análisis.
 *
 * Solo hay dos casos de uso, vista previa y análisis, y nunca captura de fotos: ningún cuadro
 * puede terminar en un archivo. La cámara queda atada al ciclo de vida de la pantalla, así que se
 * apaga al pasar a segundo plano, y se suelta al salir del escáner o al cambiar [attempt].
 *
 * @param torchOn si la linterna debe estar encendida.
 */
@Composable
fun ScannerCamera(
    viewModel: ScannerViewModel,
    attempt: Int,
    torchOn: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(lifecycleOwner, attempt) {
        val analyzer = viewModel.openSession()
        val executor = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request -> surfaceRequest = request }
        val analysis =
            ImageAnalysis
                .Builder()
                .setResolutionSelector(
                    ResolutionSelector
                        .Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                ANALYSIS_SIZE,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        ).build(),
                )
                // Si la lectura tarda, se descartan los cuadros viejos: siempre se lee el último.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        analysis.setAnalyzer(executor, analyzer)
        var provider: ProcessCameraProvider? = null
        try {
            val cameraProvider = cameraProviderOrNull(context, viewModel) ?: return@LaunchedEffect
            provider = cameraProvider
            when (val bound = bind(cameraProvider, lifecycleOwner, preview, analysis)) {
                is BindResult.Bound -> {
                    camera = bound.camera
                    viewModel.onCameraBound(bound.camera.cameraInfo.hasFlashUnit())
                    awaitCancellation()
                }

                BindResult.NoCamera -> viewModel.onCameraFailed(null)

                is BindResult.Failed -> viewModel.onCameraFailed(bound.failure)
            }
        } finally {
            camera = null
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            executor.shutdown()
            surfaceRequest = null
            viewModel.closeSession()
        }
    }

    LaunchedEffect(camera, torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    surfaceRequest?.let { request ->
        CameraXViewfinder(surfaceRequest = request, modifier = modifier)
    }
}

/**
 * La cámara del proceso, o `null` si no arrancó: sin cámara utilizable, o con el servicio de
 * cámara del sistema caído. El escáner lo muestra como una cámara que no abrió.
 */
private suspend fun cameraProviderOrNull(
    context: Context,
    viewModel: ScannerViewModel,
): ProcessCameraProvider? = try {
    ProcessCameraProvider.awaitInstance(context)
} catch (e: CancellationException) {
    throw e
} catch (e: InitializationException) {
    viewModel.onCameraFailed(e)
    null
} catch (e: IllegalStateException) {
    viewModel.onCameraFailed(e)
    null
}

private sealed interface BindResult {
    data class Bound(val camera: Camera) : BindResult

    data object NoCamera : BindResult

    data class Failed(val failure: Throwable) : BindResult
}

private fun bind(
    provider: ProcessCameraProvider,
    owner: LifecycleOwner,
    preview: Preview,
    analysis: ImageAnalysis,
): BindResult = try {
    if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
        BindResult.NoCamera
    } else {
        provider.unbindAll()
        BindResult.Bound(
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis),
        )
    }
} catch (e: CameraInfoUnavailableException) {
    BindResult.Failed(e)
} catch (e: IllegalArgumentException) {
    BindResult.Failed(e)
} catch (e: IllegalStateException) {
    BindResult.Failed(e)
}
