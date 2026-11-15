package io.github.yesidrobayo.shopledger.ui.update

import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp

/**
 * `Actualizaciones y verificar APK`.
 *
 * @property installedLoading mientras se leen la versión y la huella de la app instalada.
 * @property installed la versión y la huella, o nula si el sistema no las dio.
 * @property verify lo que pasa con el archivo elegido.
 */
data class UpdatesUiState(
    val installedLoading: Boolean = true,
    val installed: InstalledApp? = null,
    val verify: VerifyUi = VerifyUi.Idle,
)

/** El estado de `Verificar un APK`. */
sealed interface VerifyUi {
    /** Todavía no se eligió ningún archivo, o se canceló el selector. */
    data object Idle : VerifyUi

    /** Copiando y comparando el archivo: el botón no atiende más toques. */
    data object Checking : VerifyUi

    /**
     * El resultado del último archivo elegido.
     *
     * @property manifestMatch si su SHA-256 coincide con el del aviso de versión leído en esta
     *   visita. Solo informa: la prueba es el certificado.
     */
    data class Done(val result: ApkCheckResult, val manifestMatch: Boolean = false) : VerifyUi
}

/** Lo que la persona hace en la pantalla. */
sealed interface UpdatesAction {
    data object Back : UpdatesAction

    /** Abrir el selector del sistema. Lo resuelve la navegación. */
    data object ChooseFile : UpdatesAction

    /** El selector devolvió [uri], o nulo si se cerró sin elegir. */
    data class FileChosen(val uri: String?) : UpdatesAction
}
