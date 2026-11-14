package io.github.yesidrobayo.shopledger.ui.scanner

/** El permiso de cámara tal como lo ve el escáner. */
enum class CameraPermission {
    /** Todavía no se miró: la pantalla no muestra nada sobre el fondo. */
    UNKNOWN,

    /** Sin conceder ni pedir en esta visita: la hoja explica para qué sirve antes de pedirlo. */
    NOT_REQUESTED,

    GRANTED,

    /** La persona lo negó: se puede activar en los ajustes del sistema, o escribir el código. */
    DENIED,
}

/** En qué va la cámara. Solo corre con el escáner a la vista. */
enum class CameraPhase {
    /** Se pidió la cámara y todavía no entrega cuadros. */
    OPENING,

    /** Entrega cuadros y el lector los mira. */
    RUNNING,

    /** No abrió: está ocupada, no existe o falló. Escribir el código sigue funcionando. */
    FAILED,
}

/**
 * Lo que muestra el escáner.
 *
 * @property continuous si sigue leyendo después de un código, como en la venta, o se cierra con el
 *   primero, como al llenar el código de un producto.
 * @property cameraAttempt sube con cada `Reintentar`, para volver a pedir la cámara.
 * @property hasTorch si la cámara trae linterna: sin ella no hay botón.
 * @property slowHint si lleva un rato sin leer nada: entonces sugiere acercar el código o encender
 *   la linterna, sin hablar de error.
 * @property readCount cuántos códigos se tomaron en esta visita.
 * @property lastRead el último código tomado, para la etiqueta de la ventana.
 * @property readPulse sube con cada código tomado: la ventana se cierra un poco y el teléfono
 *   vibra.
 * @property typingCode si está abierta la hoja para escribir el código a mano.
 * @property closed si el escáner terminó y la navegación debe volver.
 */
data class ScannerUiState(
    val continuous: Boolean,
    val permission: CameraPermission = CameraPermission.UNKNOWN,
    val camera: CameraPhase = CameraPhase.OPENING,
    val cameraAttempt: Int = 0,
    val hasTorch: Boolean = false,
    val torchOn: Boolean = false,
    val slowHint: Boolean = false,
    val readCount: Int = 0,
    val lastRead: String? = null,
    val readPulse: Int = 0,
    val typingCode: Boolean = false,
    val closed: Boolean = false,
)

/** Lo que la persona hace en el escáner. */
sealed interface ScannerAction {
    /** Cerrar, con la X o con atrás. En lectura continua es lo mismo que `Terminar`. */
    data object Close : ScannerAction

    data object AllowCamera : ScannerAction

    data object OpenSettings : ScannerAction

    data object Retry : ScannerAction

    data object ToggleTorch : ScannerAction

    data object TypeCode : ScannerAction

    /** El código escrito a mano, o `null` si la hoja se cerró sin usarlo. */
    data class CodeTyped(val code: String?) : ScannerAction
}
