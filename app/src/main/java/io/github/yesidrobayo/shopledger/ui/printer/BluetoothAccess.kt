package io.github.yesidrobayo.shopledger.ui.printer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Si se pueden usar las impresoras emparejadas: `BLUETOOTH_CONNECT` desde Android 12 y `BLUETOOTH`
 * antes, que se concede al instalar y no muestra diálogo.
 */
internal fun Context.canUsePairedPrinters(): Boolean {
    val permission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Cuando [asking] pasa a verdadero, pide `BLUETOOTH_CONNECT` en contexto, solo desde Android 12 y
 * solo si falta, y avisa con [onResult]. Antes de Android 12, o ya concedido, avisa sin diálogo.
 */
@Composable
internal fun AskBluetoothPermission(asking: Boolean, onResult: (Boolean) -> Unit) {
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    LaunchedEffect(asking) {
        if (!asking) return@LaunchedEffect
        val granted = context.canUsePairedPrinters()
        if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onResult(granted)
        } else {
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}

/** Abre los ajustes de Bluetooth del teléfono, para emparejar una impresora o encenderlo. */
internal fun Context.openBluetoothSettings() {
    try {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        // Un teléfono sin esa pantalla: la persona la busca en sus ajustes.
    }
}

/** Los ajustes del sistema de esta app, donde la persona concede el permiso negado. */
internal fun Context.openPrinterAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}
