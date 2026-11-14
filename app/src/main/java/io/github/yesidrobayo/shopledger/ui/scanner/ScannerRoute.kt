package io.github.yesidrobayo.shopledger.ui.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.di.ScannerTelemetryLog
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScanDelivery
import io.github.yesidrobayo.shopledger.navigation.ScanPurpose
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel

/**
 * El escáner en la navegación. Cada código tomado va a la pantalla que lo abrió, por la entrega de
 * la navegación, y al terminar vuelve a ella.
 *
 * `CAMERA` se pide solo aquí, tras la hoja que explica para qué, y nunca al abrir la app. Al
 * volver de los ajustes del sistema se mira otra vez, porque la persona pudo activarlo.
 */
@Composable
fun ScannerRoute(key: AppNavKey.Scanner, navigator: AppNavigator) {
    val context = LocalContext.current
    val model =
        rememberAppViewModel { dependencies ->
            ScannerViewModel(
                continuous = key.purpose.isContinuous,
                deliver = { code -> navigator.scans.deliver(ScanDelivery(key.purpose, code)) },
                telemetry = ScannerTelemetryLog(dependencies.container.log),
            )
        }
    when (model) {
        ScreenModel.Opening ->
            ScannerScreen(ScannerUiState(key.purpose.isContinuous), {}, cameraContent = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val permission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    viewModel.onPermissionResult(it)
                }
            LifecycleResumeEffect(viewModel) {
                viewModel.onPermissionChecked(context.hasCamera())
                onPauseOrDispose {}
            }
            LaunchedEffect(uiState.closed) {
                if (uiState.closed) navigator.back()
            }
            ScannerScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        ScannerAction.AllowCamera -> permission.launch(Manifest.permission.CAMERA)
                        ScannerAction.OpenSettings -> context.openAppSettings()
                        else -> viewModel.onAction(action)
                    }
                },
                cameraContent = { modifier ->
                    ScannerCamera(viewModel, uiState.cameraAttempt, uiState.torchOn, modifier)
                },
            )
        }
    }
}

/** Si el escáner sigue leyendo después del primer código. */
internal val ScanPurpose.isContinuous: Boolean
    get() = when (this) {
        ScanPurpose.SALE, ScanPurpose.PURCHASE, ScanPurpose.STOCK_COUNT -> true
        ScanPurpose.PRODUCT_SEARCH, ScanPurpose.PRODUCT_BARCODE -> false
    }

private fun Context.hasCamera(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** Los ajustes del sistema de esta app, donde la persona activa la cámara. */
private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}
