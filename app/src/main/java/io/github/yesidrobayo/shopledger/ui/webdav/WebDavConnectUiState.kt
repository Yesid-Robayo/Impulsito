package io.github.yesidrobayo.shopledger.ui.webdav

import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavAddressProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavSuggestion
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestFailure
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestStep

/** En qué va un paso de la prueba de conexión. */
enum class StepStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
}

/** Un paso de la prueba, con su estado. */
data class StepProgress(val step: WebDavTestStep, val status: StepStatus)

/** En qué va la pantalla. */
enum class WebDavPhase {
    /** Escribiendo, o de vuelta tras un fallo, con lo escrito intacto. */
    EDITING,

    /** `¿Es este el servicio?`, antes de la primera petición a una dirección escrita a mano. */
    CONFIRM_HOST,

    /** La prueba corre. Se puede cancelar. */
    TESTING,

    /** La prueba pasó y hay otro destino conectado: `¿Cambiar dónde guardar?`. */
    CONFIRM_CHANGE,

    /**
     * Lo probado espera la verificación del sistema antes de guardarse. Con el bloqueo de la app
     * apagado pasa sola; cancelada, nada se guarda y lo escrito se conserva.
     */
    VERIFYING,

    /** Guardando lo probado. No se cancela: dura un instante y no deja nada a medias. */
    SAVING,
}

/** Un aviso de la pantalla, en una `NoticeCard`. */
sealed interface WebDavNotice {
    /** La prueba falló en [step] por [failure]. Nada se guardó. */
    data class Failed(val step: WebDavTestStep, val failure: WebDavTestFailure) : WebDavNotice

    /** Sin internet: conectar lo requiere, y lo escrito se conserva. */
    data object NoInternet : WebDavNotice

    /** La prueba pasó pero no se pudo guardar. Nada quedó guardado. */
    data object SaveFailed : WebDavNotice
}

/**
 * El estado de `Conectar el servicio`.
 *
 * La contraseña vive aquí solo en memoria: este estado nunca se guarda, y la dirección y el usuario
 * son lo único que sobrevive a que Android cierre el proceso.
 *
 * @param suggestion la sugerencia cuya dirección está escrita tal cual, o nula si se escribió a mano.
 * @param host el servidor, en ASCII, que se pide confirmar.
 * @param changeFrom el nombre del destino actual, para `¿Cambiar dónde guardar?`.
 * @param reconnect si se abrió para reconectar el servicio ya conectado.
 * @param focusUser cambia cada vez que una sugerencia llena la dirección, para llevar el foco a
 *   `Usuario`.
 * @param connected si quedó conectado, y si la carpeta ya tenía copias, hasta que la navegación lo
 *   tome.
 */
data class WebDavConnectUiState(
    val suggestions: List<WebDavSuggestion> = emptyList(),
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val suggestion: WebDavSuggestion? = null,
    val phase: WebDavPhase = WebDavPhase.EDITING,
    val host: String? = null,
    val steps: List<StepProgress> = emptyList(),
    val urlError: WebDavAddressProblem? = null,
    val passwordRejected: Boolean = false,
    val notice: WebDavNotice? = null,
    val changeFrom: String? = null,
    val reconnect: Boolean = false,
    val focusUser: Int = 0,
    val connected: Connected? = null,
) {
    /** `Probar y conectar` se activa con los tres campos llenos y nada corriendo. */
    val canConnect: Boolean
        get() = phase == WebDavPhase.EDITING &&
            url.isNotBlank() &&
            username.isNotBlank() &&
            password.isNotEmpty()

    /** Si la prueba o el guardado están corriendo, y el botón está en `Trabajando`. */
    val working: Boolean
        get() =
            phase == WebDavPhase.TESTING ||
                phase == WebDavPhase.VERIFYING ||
                phase == WebDavPhase.SAVING

    /**
     * Sin la dirección, el usuario ni la contraseña: un registro o un error que imprima el estado
     * nunca los lleva.
     */
    override fun toString(): String = "WebDavConnectUiState(phase=$phase)"

    /** Quedó conectado; [hasCopies] dice si la carpeta ya tenía copias de esta app. */
    data class Connected(val hasCopies: Boolean)
}

/** Lo que la persona hace en la pantalla. */
sealed interface WebDavConnectAction {
    /** `Cifrar las copias`: lo resuelve la navegación, sin perder lo escrito. */
    data object OpenEncryption : WebDavConnectAction

    data class UrlChange(val value: String) : WebDavConnectAction

    data class UserChange(val value: String) : WebDavConnectAction

    data class PasswordChange(val value: String) : WebDavConnectAction {
        /** La contraseña escrita nunca sale en un texto. */
        override fun toString(): String = "PasswordChange"
    }

    data object TogglePassword : WebDavConnectAction

    /** Toca una sugerencia: llena la dirección y lleva el foco a `Usuario`. */
    data class PickSuggestion(val key: String) : WebDavConnectAction

    /** `Probar y conectar`, o `Reintentar` tras un fallo de red. */
    data object Connect : WebDavConnectAction

    data object ConfirmHost : WebDavConnectAction

    data object DismissHost : WebDavConnectAction

    /** Cancela la prueba en curso. Nada queda guardado. */
    data object Cancel : WebDavConnectAction

    data object ConfirmChange : WebDavConnectAction

    data object DismissChange : WebDavConnectAction

    /** La verificación del sistema terminó: [allowed] dice si se guarda lo probado. */
    data class Verified(val allowed: Boolean) : WebDavConnectAction

    data object Back : WebDavConnectAction

    /** La navegación ya tomó el resultado. */
    data object ConnectedHandled : WebDavConnectAction
}
