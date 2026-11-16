package io.github.yesidrobayo.shopledger.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import java.io.IOException

/**
 * Un componente de otras personas que va dentro del APK, con su licencia y el archivo de `assets/`
 * que tiene su texto completo. El nombre del componente y el de la licencia son nombres propios:
 * no se traducen, salvo el dominio público, que no es un nombre.
 */
enum class LicensedComponent(
    @param:StringRes val component: Int,
    @param:StringRes val license: Int,
    val textAsset: String,
) {
    ANDROIDX(R.string.license_component_androidx, R.string.license_apache, APACHE),
    KOTLIN(R.string.license_component_kotlin, R.string.license_apache, APACHE),
    OKHTTP(R.string.license_component_okhttp, R.string.license_apache, APACHE),
    VICO(R.string.license_component_vico, R.string.license_apache, APACHE),
    SQLITE(R.string.license_component_sqlite, R.string.licenses_public_domain, SQLITE_TEXT),
    MATERIAL_SYMBOLS(R.string.license_component_material_symbols, R.string.license_apache, APACHE),
    FIGTREE(R.string.license_component_figtree, R.string.license_ofl, "licenses/figtree-ofl.txt"),
    NOTO(R.string.license_component_noto, R.string.license_ofl, "licenses/noto-ofl.txt"),
    TINK(R.string.license_component_tink, R.string.license_apache, APACHE),

    // Tink lleva Protocol Buffers dentro de su propio paquete: su licencia pide reproducir el aviso.
    PROTOBUF(
        R.string.license_component_protobuf,
        R.string.license_bsd_3,
        "licenses/protobuf-bsd-3-clause.txt",
    ),
    ZXING_CPP(R.string.license_component_zxing_cpp, R.string.license_apache, APACHE),
    OKIO(R.string.license_component_okio, R.string.license_apache, APACHE),
    GSON(R.string.license_component_gson, R.string.license_apache, APACHE),
    GOOGLE_JAVA(R.string.license_component_google_java, R.string.license_apache, APACHE),
    JETBRAINS(R.string.license_component_jetbrains, R.string.license_apache, APACHE),
}

// El texto de Apache 2.0 es el mismo para todos los componentes con esa licencia.
private const val APACHE = "licenses/material-symbols-apache-2.0.txt"
private const val SQLITE_TEXT = "licenses/sqlite-blessing.txt"

/**
 * El texto completo de una licencia, desde los recursos del APK: se lee sin internet. Lee el disco,
 * así que va fuera del hilo principal. `null` si no se pudo leer.
 */
fun readLicenseText(context: Context, asset: String): String? = try {
    context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
} catch (_: IOException) {
    null
}

/**
 * `Licencias`: cada componente incluido en el APK con su licencia. Tocar uno abre su texto completo.
 */
@Composable
fun LicensesSheet(onOpen: (LicensedComponent) -> Unit, onDismiss: () -> Unit) {
    val app = stringResource(R.string.app_name)
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_licenses),
    ) {
        Text(
            stringResource(R.string.licenses_help, app),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Las filas llevan su propio margen: salen hasta el borde de la hoja para alinearse con el título.
        val bleed = AppTheme.spacing.s16
        Column(
            Modifier.layout { measurable, constraints ->
                val extra = bleed.roundToPx() * 2
                val placeable =
                    measurable.measure(
                        constraints.copy(
                            minWidth = constraints.minWidth + extra,
                            maxWidth = constraints.maxWidth + extra,
                        ),
                    )
                layout(placeable.width - extra, placeable.height) {
                    placeable.place(-extra / 2, 0)
                }
            },
        ) {
            for (entry in LicensedComponent.entries) {
                SettingsRow(
                    title =
                        stringResource(
                            R.string.licenses_row,
                            stringResource(entry.component),
                            stringResource(entry.license),
                        ),
                    action = SettingsRowAction.Navigation { onOpen(entry) },
                )
            }
        }
    }
}

/**
 * El texto completo de la licencia de [component]. Mientras se lee, un bloque de espera; si no se
 * pudo leer, lo dice sin cerrar la hoja.
 *
 * @param read lee el texto del archivo de `assets/`, fuera del hilo principal.
 */
@Composable
fun LicenseTextSheet(
    component: LicensedComponent,
    read: suspend (String) -> String?,
    onDismiss: () -> Unit,
) {
    val text by produceState<LicenseText>(LicenseText.Loading, component) {
        value = read(component.textAsset)?.let { LicenseText.Ready(it) } ?: LicenseText.Missing
    }
    AppBottomSheet(onDismissRequest = onDismiss, title = stringResource(component.component)) {
        when (val shown = text) {
            LicenseText.Loading -> SkeletonBlock()

            LicenseText.Missing ->
                Text(
                    stringResource(R.string.state_error_read),
                    style = MaterialTheme.typography.bodyMedium,
                )

            is LicenseText.Ready ->
                Text(shown.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** El texto de una licencia mientras se lee. */
private sealed interface LicenseText {
    data object Loading : LicenseText

    data object Missing : LicenseText

    data class Ready(val text: String) : LicenseText
}

/**
 * `Cómo borrar los datos`: los pasos a mano, para quien prefiera hacerlo por su cuenta, también lo
 * que la app no alcanza. Primero, que conviene exportar lo que se quiera conservar.
 *
 * Mientras no exista `Borrar mis datos`, la frase que la recomienda no se muestra: nombraría una
 * acción que la persona no encuentra.
 */
@Composable
fun DeleteGuideSheet(onDismiss: () -> Unit) {
    val app = stringResource(R.string.app_name)
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_delete_data),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            Text(
                stringResource(R.string.delete_guide_before),
                style = MaterialTheme.typography.bodyLarge,
            )
            for (step in listOf(
                stringResource(R.string.delete_guide_step_1, app),
                stringResource(R.string.delete_guide_step_2, app),
                stringResource(R.string.delete_guide_step_3),
                stringResource(R.string.delete_guide_step_4, app),
            )) {
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@ThemePreviews
@Composable
private fun AboutSheetsPreview() {
    PreviewTheme {
        Column {
            LicensesSheet(onOpen = {}, onDismiss = {})
            DeleteGuideSheet(onDismiss = {})
        }
    }
}
