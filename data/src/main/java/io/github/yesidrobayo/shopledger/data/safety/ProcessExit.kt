package io.github.yesidrobayo.shopledger.data.safety

/** Qué clase de muerte tuvo un proceso anterior de la app, según el sistema. */
internal enum class ExitKind {
    JAVA_CRASH,
    NATIVE_CRASH,
    ANR,
    INITIALIZATION_FAILURE,
    OTHER,
}

/**
 * Traduce el código de motivo de `ApplicationExitInfo` a [ExitKind]. Es una función pura, sin
 * Android, para poder probarla en el PC. Los valores son los de la referencia pública de
 * `ApplicationExitInfo` y no cambian entre versiones.
 *
 * Cualquier otro código, incluido el motivo desconocido (0), es [ExitKind.OTHER]: contarlo
 * abriría el modo seguro por salidas que no son un fallo.
 */
internal fun exitKindOf(reason: Int): ExitKind = when (reason) {
    REASON_CRASH -> ExitKind.JAVA_CRASH
    REASON_CRASH_NATIVE -> ExitKind.NATIVE_CRASH
    REASON_ANR -> ExitKind.ANR
    REASON_INITIALIZATION_FAILURE -> ExitKind.INITIALIZATION_FAILURE
    else -> ExitKind.OTHER
}

// Códigos de motivo de ApplicationExitInfo que cuentan como fallo al abrir.
private const val REASON_CRASH = 4
private const val REASON_CRASH_NATIVE = 5
private const val REASON_ANR = 6
private const val REASON_INITIALIZATION_FAILURE = 7

/**
 * Una salida de un proceso anterior, reducida a números. No guarda la descripción que da el
 * sistema: es texto libre, sin formato estable, y nada garantiza que no arrastre datos.
 *
 * @property reason código crudo del sistema, para el registro de fallos.
 * @property status código de salida, o el número de la señal si el proceso murió por una.
 * @property importance importancia que tenía el proceso antes de morir.
 */
internal data class ProcessExit(
    val pid: Int,
    val timestampMillis: Long,
    val kind: ExitKind,
    val reason: Int,
    val status: Int,
    val importance: Int,
) {
    /**
     * Una caída de Java o nativa, un ANR o un fallo de inicialización cuentan como fallo al abrir.
     * Un cierre forzado, uno por memoria o un motivo desconocido, no.
     */
    val isFailure: Boolean
        get() = kind != ExitKind.OTHER
}

/**
 * Salidas recientes de la app, de la más reciente a la más antigua. Vacía si el sistema no las
 * ofrece.
 */
internal fun interface ExitReasonSource {
    fun recentExits(): List<ProcessExit>
}
