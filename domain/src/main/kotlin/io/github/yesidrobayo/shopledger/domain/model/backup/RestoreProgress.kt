package io.github.yesidrobayo.shopledger.domain.model.backup

import kotlin.time.Instant

/**
 * En qué quedó una restauración que pidió la persona. Salvo [ReadyToRestart] y las preguntas, los
 * datos del teléfono siguen como estaban.
 */
sealed interface RestoreProgress {
    /** Ya hay otra restauración en curso. */
    data object Busy : RestoreProgress

    /** No cabe. [missingBytes] es lo que hay que liberar. */
    data class NotEnoughSpace(val missingBytes: Long) : RestoreProgress

    /** La copia no se usa, por [problem]. */
    data class Refused(val problem: RestoreProblem) : RestoreProgress

    /** Una falla del teléfono o de la red, que no dice nada de la copia: se puede reintentar. */
    data object Failed : RestoreProgress

    /** Trae [rows] saldos que no salen de su historial: sigue si se recalculan. */
    data class AskRepair(val rows: Long) : RestoreProgress

    /** La copia no tiene datos del negocio y este teléfono sí: sigue solo con una decisión expresa. */
    data object AskEmptyCopy : RestoreProgress

    /** Los datos actuales están dañados y no se pudieron copiar antes de reemplazarlos. */
    data object AskWithoutSafetyCopy : RestoreProgress

    /**
     * Las dos copias de seguridad de este teléfono guardan cambios que no llegaron a la nube: la
     * persona elige cuál descartar, por su posición en [copies].
     */
    data class AskDiscardSafetyCopy(val copies: List<SafetyCopyInfo>) : RestoreProgress

    /** Todo listo: falta reiniciar la app, y el arranque reemplaza los datos. */
    data class ReadyToRestart(val warnings: Int) : RestoreProgress

    /**
     * La copia está cifrada y este teléfono no tiene su código: se pide escribirlo, por [request].
     * No espera una respuesta: la restauración se detuvo sin descargar ni tocar nada, y empieza de
     * nuevo con el código escrito. Un código errado nunca borra ni bloquea nada.
     * [replacesCurrent] dice si, al terminar, ese código reemplazaría al guardado en este teléfono:
     * las copias con el código actual lo siguen necesitando, y la persona lo sabe antes de seguir.
     */
    data class AskRecoveryCode(
        val request: RecoveryCodeRequest,
        val replacesCurrent: Boolean = false,
    ) : RestoreProgress
}

/** Por qué la restauración pide el código de recuperación. */
enum class RecoveryCodeRequest {
    /** Este teléfono no tiene el código de esta copia. */
    NEEDED,

    /** Lo escrito no es un código: sus caracteres de control no cuadran o le falta algo. */
    TYPO,

    /** Es un código válido, pero de otras copias. No se descargó ni se borró nada. */
    OTHER_CODE,
}

/** Una copia de seguridad de este teléfono, para elegir cuál descartar. */
data class SafetyCopyInfo(val createdAt: Instant?)

/** Por qué no se usa una copia. Cada uno tiene su texto; ninguno cambia los datos actuales. */
enum class RestoreProblem {
    /** El archivo no es una copia de la app. */
    NOT_A_BACKUP,

    /** Es una copia, pero está dañada o a medias. Con ranuras, conviene probar otra. */
    DAMAGED,

    /** Viene de una versión más nueva de la app: hay que actualizar. */
    NEWER_VERSION,

    /** Supera el tamaño que la app puede abrir. */
    TOO_LARGE,

    /** La copia se marcó como imposible de restaurar al crearla. */
    MARKED_FATAL,

    /** No hay un destino conectado del que traerla. */
    NOT_CONNECTED,

    /** El destino ya no la tiene, o no dejó leerla. */
    NOT_AVAILABLE,

    /** Otra causa, como una migración que falló: los datos actuales no cambiaron. */
    OTHER,

    /**
     * La copia previa a la actualización no abre con esta versión de la app, que todavía no trae la
     * migración corregida: hay que instalar la versión más reciente.
     */
    NEEDS_UPDATE,
}

/**
 * La copia que la app guardó en este teléfono justo antes de su última actualización de los datos.
 *
 * @property createdAt cuándo se guardó, según el reloj del teléfono.
 */
data class BeforeUpdateCopy(val createdAt: Instant)

/**
 * La copia local de seguridad que se ofrece para volver a como estaba antes de restaurar: la
 * que la app guardó justo antes de reemplazar los datos, mientras tenga menos de 30 días. Nunca
 * subió.
 *
 * @property createdAt cuándo se guardó, según el reloj del teléfono. También la identifica:
 *   al confirmar se vuelve a esa misma copia, nunca a otra que la haya reemplazado entretanto.
 */
data class UndoRestoreCopy(val createdAt: Instant)

/** Lo que la persona contesta cuando la restauración pregunta. */
sealed interface RestoreAnswer {
    /** Recalcular los saldos desde el historial. */
    data object Repair : RestoreAnswer

    /** Reemplazar los datos con una copia vacía. */
    data object ReplaceWithEmptyCopy : RestoreAnswer

    /** Seguir sin copia de seguridad. */
    data object ContinueWithoutSafetyCopy : RestoreAnswer

    /** Descartar la copia de seguridad en la posición [index] de la pregunta. */
    data class DiscardSafetyCopy(val index: Int) : RestoreAnswer
}
