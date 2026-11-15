package io.github.yesidrobayo.shopledger.ui.onboarding

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.FoundLocalCopy
import io.github.yesidrobayo.shopledger.lock.AuthOutcome
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi

/** Los cuatro pasos del primer uso, en orden. */
enum class OnboardingStepId {
    LANGUAGE,
    REGION,
    WELCOME,
    PROTECTION,
}

/** Lo que se encontró en el servicio recién conectado, en el primer uso. */
sealed interface FoundCopies {
    /** Pidiendo la lista al servicio. */
    data object Searching : FoundCopies

    /** Hay copias, de la más nueva a la más vieja: se ofrece traerlas. */
    data class Found(val copies: List<BackupCopySummary>) : FoundCopies

    /** La lista falló. Nunca se trata como si no hubiera copias. */
    data object Failed : FoundCopies
}

/** `Buscar mi copia en este teléfono`: lo que se encontró en la carpeta elegida. */
sealed interface LocalCopySearch {
    /** Leyendo la carpeta. */
    data object Searching : LocalCopySearch

    /**
     * Las copias de la carpeta [treeUri], de la más reciente a la más vieja. Se propone la de
     * [index]: si una no pasa la validación, la siguiente.
     */
    data class Found(val treeUri: String, val copies: List<FoundLocalCopy>, val index: Int = 0) :
        LocalCopySearch {
        val proposed: FoundLocalCopy? get() = copies.getOrNull(index)
    }

    /** La carpeta no tiene copias de la app. */
    data object NoCopies : LocalCopySearch

    /** La carpeta no es del almacenamiento del teléfono. */
    data object NotLocal : LocalCopySearch

    /** No se pudo leer la carpeta: nunca cuenta como vacía. */
    data object Failed : LocalCopySearch
}

/** `Ver con datos de ejemplo`: cómo va la preparación de la base de ejemplo. */
enum class DemoStartUi {
    /** Nada pedido. */
    IDLE,

    /** Escribiendo el negocio de ejemplo: la acción no se repite. */
    WORKING,

    /** Lista: la navegación reinicia la app, que abre en la demostración. */
    READY,

    /** No se pudo. Los datos del negocio no cambiaron, y se puede intentar otra vez. */
    FAILED,
}

/**
 * El estado del primer uso.
 *
 * @param step el paso visible, o nulo mientras se lee dónde retomar.
 * @param offline sin red comprobada: se puede empezar y elegir dónde guardar después.
 * @param slotsLabel el nombre de la nube del teléfono, para decir qué se puede perder con ella.
 * @param bringSheet si está abierta la hoja `Traer datos de otro teléfono`.
 * @param found lo que se encontró al conectar un servicio, o nulo.
 * @param startOver si se pide confirmar `Empezar de cero`.
 * @param connected si ya hay un destino conectado: el cifrado del paso 4 lo necesita.
 * @param restore la restauración pedida desde aquí.
 * @param zone la zona del negocio, para la fecha de las copias encontradas.
 * @param saveFailed si no se pudo guardar el fin del primer uso.
 * @param done terminó: la navegación lo cierra.
 * @param regionVisit cuántas veces se entró al paso de la región: cada visita empieza de nuevo.
 * @param lockOffered si el teléfono tiene bloqueo de pantalla: solo entonces el paso 4 ofrece el
 *   bloqueo de la app. Sin él, el paso avisa del riesgo y nadie queda fuera.
 * @param lockChosen si `Bloquear con huella o PIN` está marcado. Nace marcado: rechazarlo es apagar
 *   el interruptor.
 * @param lockCheck `Continuar` con el bloqueo marcado pide la verificación del sistema: la navegación
 *   la abre y entrega su resultado.
 * @param localCopy lo que se encontró con `Buscar mi copia en este teléfono`, o nulo.
 * @param demo cómo va `Ver con datos de ejemplo`.
 */
data class OnboardingUiState(
    val step: OnboardingStepId? = null,
    val offline: Boolean = false,
    val slotsLabel: String? = null,
    val bringSheet: Boolean = false,
    val found: FoundCopies? = null,
    val startOver: Boolean = false,
    val connected: Boolean = false,
    val restore: RestoreUi = RestoreUi.Idle,
    val zone: BusinessTimeZone? = null,
    val saveFailed: Boolean = false,
    val done: Boolean = false,
    val regionVisit: Int = 0,
    val lockOffered: Boolean = false,
    val lockChosen: Boolean = true,
    val lockCheck: Boolean = false,
    val localCopy: LocalCopySearch? = null,
    val demo: DemoStartUi = DemoStartUi.IDLE,
)

/** Lo que la persona hace en el primer uso. */
sealed interface OnboardingAction {
    /** `Continuar` en el paso del idioma. */
    data object LanguageDone : OnboardingAction

    /** La región quedó confirmada. */
    data object RegionDone : OnboardingAction

    /** Atrás: al paso anterior, sin perder lo elegido. */
    data object Back : OnboardingAction

    /** `Elegir dónde guardar`: lo resuelve la navegación, con la política antes. */
    data object ChooseDestination : OnboardingAction

    data object OpenBring : OnboardingAction

    data object CloseBring : OnboardingAction

    /** `Servicio con WebDAV` en la hoja: el mismo orden para conectar. */
    data object BringWithWebDav : OnboardingAction

    /** `Buscar un archivo`: lo resuelve la navegación, con el selector. */
    data object FindFile : OnboardingAction

    /** `Ahora no`: sigue sin destino. */
    data object NotNow : OnboardingAction

    data object OpenDataTransparency : OnboardingAction

    /** `Cifrar las copias`, con un destino ya elegido: abre la pantalla del cifrado. */
    data object OpenEncryption : OnboardingAction

    /** Volvió a primer plano, quizá desde conectar un destino. */
    data object Resumed : OnboardingAction

    /** `Reintentar` la lista de copias. */
    data object RetrySearch : OnboardingAction

    /** `Empezar de cero`: pide confirmar. */
    data object AskStartOver : OnboardingAction

    data object ConfirmStartOver : OnboardingAction

    data object DismissStartOver : OnboardingAction

    /**
     * `Continuar` en el paso de la protección: termina el primer uso. Con el bloqueo marcado, antes
     * pide la verificación del sistema.
     */
    data object Finish : OnboardingAction

    /** Si el teléfono tiene bloqueo de pantalla, mirado al abrir el paso y al volver a primer plano. */
    data class DeviceLock(val secure: Boolean) : OnboardingAction

    /** El interruptor `Bloquear con huella o PIN` del paso de la protección. */
    data class LockChoice(val enabled: Boolean) : OnboardingAction

    /** Lo que resultó de la verificación que pidió `Continuar` con el bloqueo marcado. */
    data class LockChecked(val outcome: AuthOutcome) : OnboardingAction

    /** Una acción de la restauración, la misma que en la pantalla del respaldo. */
    data class Restore(val action: BackupAction) : OnboardingAction

    /** `Buscar mi copia en este teléfono`: lo resuelve la navegación, con el selector de carpetas. */
    data object FindLocalCopy : OnboardingAction

    /** El selector devolvió la carpeta [treeUri]. */
    data class LocalFolderPicked(val treeUri: String) : OnboardingAction

    /** `Restaurar` la copia propuesta de la carpeta. */
    data object RestoreLocalCopy : OnboardingAction

    /** `Ver con datos de ejemplo`: prepara la base de ejemplo, aparte de la del negocio. */
    data object SeeSampleData : OnboardingAction
}
