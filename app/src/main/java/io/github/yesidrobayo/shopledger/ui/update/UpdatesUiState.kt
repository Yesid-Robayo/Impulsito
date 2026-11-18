package io.github.yesidrobayo.shopledger.ui.update

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.ui.help.HelpBlock
import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp
import kotlin.time.Instant

/**
 * `Actualizaciones y verificar APK`.
 *
 * @property installedLoading mientras se leen la versión y la huella de la app instalada.
 * @property installed la versión y la huella, o nula si el sistema no las dio.
 * @property searchEnabled si `Buscar actualización` está permitido, o nulo mientras se lee o si
 *   los ajustes no se pudieron leer: entonces no se ofrece nada de la búsqueda.
 * @property automatic si la búsqueda automática, una vez al día, está encendida, o nulo mientras se
 *   lee.
 * @property lastSearchAt la última búsqueda en este teléfono, o nula si nunca se buscó.
 * @property search lo que pasa con la búsqueda de esta visita.
 * @property zone la zona con que se leen las fechas.
 * @property now el instante de la última lectura, para decir una fecha de este año sin el año.
 * @property verify lo que pasa con el archivo elegido.
 * @property saveFailed si el interruptor no se pudo guardar: la pantalla lo dice una vez.
 * @property install lo que pasa con `Actualizar`, que solo se ofrece tras una búsqueda que
 *   encontró una versión nueva.
 * @property whatsNew la hoja de `Novedades`, si está abierta.
 */
data class UpdatesUiState(
    val installedLoading: Boolean = true,
    val installed: InstalledApp? = null,
    val searchEnabled: Boolean? = null,
    val automatic: Boolean? = null,
    val lastSearchAt: Instant? = null,
    val search: SearchUi = SearchUi.Idle,
    val zone: BusinessTimeZone = UTC,
    val now: Instant? = null,
    val verify: VerifyUi = VerifyUi.Idle,
    val saveFailed: Boolean = false,
    val install: InstallUi = InstallUi.Idle,
    val whatsNew: WhatsNewUi = WhatsNewUi.Closed,
) {
    companion object {
        /** La zona mientras no se leyó la del negocio. */
        val UTC: BusinessTimeZone = checkNotNull(BusinessTimeZone.of("UTC"))
    }
}

/** El estado de `Buscar actualización`. */
sealed interface SearchUi {
    /** No se buscó en esta visita. */
    data object Idle : SearchUi

    /** Leyendo el aviso de versión: el botón no atiende más toques. */
    data object Searching : SearchUi

    /** El resultado de la última búsqueda de esta visita. */
    data class Done(val outcome: UpdateCheckOutcome) : SearchUi
}

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

/**
 * El estado de `Actualizar`: bajar el APK de la versión anunciada, compararlo y entregarlo al
 * instalador del sistema. Nada de esto toca los datos.
 */
sealed interface InstallUi {
    /** Todavía no se tocó `Actualizar` en esta búsqueda. */
    data object Idle : InstallUi

    /**
     * Bajando el archivo, con `Cancelar`.
     *
     * @property downloadedBytes lo bajado hasta ahora.
     * @property totalBytes el tamaño declarado, o nulo si el servidor no lo dijo.
     */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : InstallUi

    /** Comparando el archivo con el aviso firmado y con la firma de la app. */
    data object Verifying : InstallUi

    /**
     * El archivo es bueno, pero Android todavía no permite que la app instale actualizaciones: se
     * explica y se abre el ajuste. Al volver con el permiso, sigue sola.
     */
    data object NeedsPermission : InstallUi

    /**
     * Listo para el instalador: la navegación lo abre y avisa con [UpdatesAction.InstallerOpened].
     */
    data object Opening : InstallUi

    /** El instalador de Android tiene el archivo y pide confirmar. Los datos se quedan. */
    data object HandedToInstaller : InstallUi

    /** Algo falló: el archivo ya se borró, nada se instaló y se puede reintentar. */
    data class Failed(val reason: InstallFailure) : InstallUi
}

/** Por qué `Actualizar` no llegó al instalador. */
enum class InstallFailure {
    /** Sin red. */
    NO_CONNECTION,

    /** La descarga no terminó, o lo que llegó no era el archivo. */
    DOWNLOAD_FAILED,

    /** El archivo pasa el tope de tamaño. */
    TOO_LARGE,

    /** Su SHA-256 no es el del aviso de versión firmado. */
    HASH_MISMATCH,

    /** Su firma no es la de la app instalada, u otra app: no se instala ni se desinstala nada. */
    WRONG_SIGNATURE,

    /** No es más nuevo que la versión instalada. */
    NOT_NEWER,

    /** No hay un instalador que lo abra. */
    NO_INSTALLER,
}

/** La hoja de `Novedades`: lo que trajo cada versión, sin red. */
sealed interface WhatsNewUi {
    data object Closed : WhatsNewUi

    data object Loading : WhatsNewUi

    /** El texto, ya en bloques, en el idioma de la interfaz. */
    data class Ready(val blocks: List<HelpBlock>) : WhatsNewUi

    /** No se pudo leer. */
    data object Failed : WhatsNewUi
}

/** Lo que la persona hace en la pantalla. */
sealed interface UpdatesAction {
    data object Back : UpdatesAction

    /** `Buscar actualización`, o `Reintentar` tras una falla. */
    data object Search : UpdatesAction

    /** El interruptor `Permitir buscar actualizaciones`. */
    data class SetSearchEnabled(val enabled: Boolean) : UpdatesAction

    /** El interruptor `Buscar actualizaciones automáticamente`. */
    data class SetAutomatic(val enabled: Boolean) : UpdatesAction

    /** La pantalla ya dijo que el interruptor no se guardó. */
    data object MessageShown : UpdatesAction

    /** Abrir el selector del sistema. Lo resuelve la navegación. */
    data object ChooseFile : UpdatesAction

    /** El selector devolvió [uri], o nulo si se cerró sin elegir. */
    data class FileChosen(val uri: String?) : UpdatesAction

    /** `Actualizar`, o `Reintentar` tras una falla. */
    data object StartUpdate : UpdatesAction

    /** `Cancelar` mientras se baja el archivo. */
    data object CancelUpdate : UpdatesAction

    /** `Permitir`: abre el ajuste de Android. Lo resuelve la navegación. */
    data object AllowInstalls : UpdatesAction

    /** La pantalla volvió a primer plano, quizá desde ese ajuste. */
    data object Resumed : UpdatesAction

    /** La navegación intentó abrir el instalador: [opened] si lo logró. */
    data class InstallerOpened(val opened: Boolean) : UpdatesAction

    /** Abrir el instalador otra vez, con el mismo archivo ya comprobado. */
    data object OpenInstallerAgain : UpdatesAction

    /** `Novedades`. */
    data object OpenWhatsNew : UpdatesAction

    /** Se cerró la hoja de `Novedades`. */
    data object CloseWhatsNew : UpdatesAction
}
