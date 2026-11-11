package io.github.yesidrobayo.shopledger.ui.destination

/** Qué destino está conectado, leído del teléfono y sin red. */
enum class ConnectedDestination {
    /** Todavía no se leyó. Casi nunca se ve: la lectura es local. */
    UNKNOWN,

    /** Ninguno, o no se pudo leer: se ofrece `Ahora no`. */
    NONE,

    /** Un servicio WebDAV. */
    WEBDAV,

    /** Los tres archivos en la nube del teléfono. */
    DOCUMENT_SLOTS,
}

/**
 * Cómo llegan las copias a la nube del teléfono. La prueba en un teléfono real con esa nube decide
 * si la entrega sale sola o si cada copia se entrega con la app abierta, y con eso cambia el texto de
 * la opción. Hasta que esa prueba corra, la app entrega sola.
 */
enum class SlotsHandoff {
    /** La app entrega cada copia sola, también en segundo plano. */
    AUTOMATIC,

    /** Cada copia se entrega a mano, con la app abierta. */
    MANUAL,
}

/** A dónde lleva una elección de la pantalla. Lo resuelve la navegación. */
enum class DestinationChoiceTarget {
    /** Vuelve a quien abrió la pantalla, sin cambiar nada. */
    BACK,

    /** `Ahora no`: sigue sin destino. En el primer uso, al paso siguiente. */
    NOT_NOW,

    /** La opción ya conectada: lleva a la pantalla del respaldo. */
    BACKUP,

    /** Conectar un servicio WebDAV. */
    WEBDAV_CONNECT,

    /** Preparar los tres archivos en la nube del teléfono. */
    DOCUMENT_SLOTS,

    /** Qué datos hay y adónde van. */
    DATA_TRANSPARENCY,
}

/**
 * El estado de `Dónde guardar las copias`. El contenido sale de los recursos: lo único que se lee es
 * qué destino está conectado.
 *
 * @param slotsWarning si está abierta la hoja `Antes de elegir`.
 * @param firstRun si la pantalla es un paso del primer uso, que muestra en qué paso va.
 * @param target a dónde ir, hasta que la navegación lo tome.
 */
data class DestinationChoiceUiState(
    val connected: ConnectedDestination = ConnectedDestination.UNKNOWN,
    val slotsHandoff: SlotsHandoff = SlotsHandoff.AUTOMATIC,
    val slotsWarning: Boolean = false,
    val firstRun: Boolean = false,
    val target: DestinationChoiceTarget? = null,
) {
    /** `Ahora no` solo aparece sin un destino conectado: con uno, no hay nada que posponer. */
    val showsNotNow: Boolean get() = connected == ConnectedDestination.NONE
}

/** Lo que la persona hace en la pantalla. */
sealed interface DestinationChoiceAction {
    data object Back : DestinationChoiceAction

    /** Toca `Drive del teléfono`. */
    data object ChooseSlots : DestinationChoiceAction

    /** `Entendido` en la hoja `Antes de elegir`. */
    data object ConfirmSlots : DestinationChoiceAction

    /** Cierra la hoja `Antes de elegir` sin elegir. */
    data object DismissSlotsWarning : DestinationChoiceAction

    /** Toca `Servicio con WebDAV`. */
    data object ChooseWebDav : DestinationChoiceAction

    data object NotNow : DestinationChoiceAction

    data object OpenDataTransparency : DestinationChoiceAction

    /** La navegación ya tomó el destino. */
    data object TargetHandled : DestinationChoiceAction
}
