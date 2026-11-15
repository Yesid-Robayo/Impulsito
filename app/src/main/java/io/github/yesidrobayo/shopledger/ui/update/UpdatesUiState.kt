package io.github.yesidrobayo.shopledger.ui.update

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
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
 * @property lastSearchAt la última búsqueda en este teléfono, o nula si nunca se buscó.
 * @property search lo que pasa con la búsqueda de esta visita.
 * @property zone la zona con que se leen las fechas.
 * @property now el instante de la última lectura, para decir una fecha de este año sin el año.
 * @property verify lo que pasa con el archivo elegido.
 * @property saveFailed si el interruptor no se pudo guardar: la pantalla lo dice una vez.
 */
data class UpdatesUiState(
    val installedLoading: Boolean = true,
    val installed: InstalledApp? = null,
    val searchEnabled: Boolean? = null,
    val lastSearchAt: Instant? = null,
    val search: SearchUi = SearchUi.Idle,
    val zone: BusinessTimeZone = UTC,
    val now: Instant? = null,
    val verify: VerifyUi = VerifyUi.Idle,
    val saveFailed: Boolean = false,
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

/** Lo que la persona hace en la pantalla. */
sealed interface UpdatesAction {
    data object Back : UpdatesAction

    /** `Buscar actualización`, o `Reintentar` tras una falla. */
    data object Search : UpdatesAction

    /** El interruptor `Permitir buscar actualizaciones`. */
    data class SetSearchEnabled(val enabled: Boolean) : UpdatesAction

    /** La pantalla ya dijo que el interruptor no se guardó. */
    data object MessageShown : UpdatesAction

    /** Abrir el selector del sistema. Lo resuelve la navegación. */
    data object ChooseFile : UpdatesAction

    /** El selector devolvió [uri], o nulo si se cerró sin elegir. */
    data class FileChosen(val uri: String?) : UpdatesAction
}
