package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import java.io.InputStream

/** De dónde sale la base que reemplaza a la activa. */
internal sealed interface RestoreSource {
    /**
     * Una copia del destino conectado, tal como se listó. Sus metadatos son entrada no confiable:
     * se validan antes de usar la suma, el tamaño o la identidad que dicen.
     */
    class Listed(val copy: ListedCopy) : RestoreSource {
        override fun toString(): String = "Listed(${copy.ref})"
    }

    /**
     * Un archivo que eligió la persona con el selector del sistema, sin metadatos ni suma
     * esperada. Su nombre nunca se usa: el formato sale de sus primeros bytes.
     *
     * @property declaredSizeBytes el tamaño que dice el proveedor, si lo dice. Solo estima el
     *   espacio: el límite real lo ponen la copia y la descompresión.
     * @property open abre el archivo para leerlo una vez, de principio a fin.
     */
    class PickedFile(val declaredSizeBytes: Long?, val open: suspend () -> InputStream) :
        RestoreSource {
        override fun toString(): String = "PickedFile"
    }

    /** Una copia local de seguridad, para deshacer una restauración anterior. */
    class Undo(val copy: SafetyCopy) : RestoreSource {
        override fun toString(): String = "Undo"
    }
}

/**
 * En qué quedó la preparación de una restauración. Salvo [ReadyToRestart] y las que piden una
 * decisión, la base activa sigue como estaba, el cerrojo se retiró y no quedan temporales.
 */
internal sealed interface RestoreStage {
    /** Ya hay una restauración en curso, o un reemplazo a medias que termina el arranque. */
    data object Busy : RestoreStage

    /** No cabe. [missingBytes] es cuánto hay que liberar. */
    data class NotEnoughSpace(val missingBytes: Long) : RestoreStage

    /** La copia no se restaura, por [reason]. */
    data class Refused(val reason: RestoreRefusal) : RestoreStage

    /**
     * Una falla del teléfono o de la red que no dice nada de la copia: se puede reintentar. Solo la
     * clase de [cause] puede ir al registro.
     */
    class Failed(val cause: Exception?) : RestoreStage

    /**
     * La copia trae saldos de cabecera que no salen de su libro: se restaura si la persona confirma
     * recalcularlos. [rows] es cuántas filas se recalculan.
     */
    data class ConfirmRepair(val rows: Long) : RestoreStage

    /** La copia no tiene datos del negocio y la base activa sí: solo sigue con una decisión expresa. */
    data object ConfirmEmptyCopy : RestoreStage

    /**
     * La base activa está dañada y no se pudo copiar. Si la persona confirma, sigue sin copia de
     * seguridad, y la base original se aparta 30 días en vez de borrarse.
     */
    data object ConfirmWithoutSafetyCopy : RestoreStage

    /**
     * Las dos copias de seguridad guardan cambios que no llegaron al destino. La persona elige cuál
     * descartar: la app nunca descarta una sola.
     */
    data class ChooseSafetyCopyToDiscard(val copies: List<SafetyCopy>) : RestoreStage

    /**
     * El diario quedó escrito y la base cerrada: falta reiniciar el proceso, y el arranque hace el
     * reemplazo. [warnings] es cuántos avisos tuvo la copia, que se cuentan y se conservan.
     */
    data class ReadyToRestart(val warnings: Int) : RestoreStage
}

/** Lo que la persona decide cuando la preparación lo pide. */
internal sealed interface RestoreDecision {
    /** Recalcular los saldos de cabecera desde el libro. */
    data object Repair : RestoreDecision

    /** Reemplazar los datos con una copia vacía. */
    data object ReplaceWithEmptyCopy : RestoreDecision

    /** Seguir sin copia de seguridad, porque la base activa está dañada. */
    data object ContinueWithoutSafetyCopy : RestoreDecision

    /** Descartar [copy], una de las dos copias de seguridad. */
    data class DiscardSafetyCopy(val copy: SafetyCopy) : RestoreDecision
}

/** Por qué no se restaura una copia. Es un código para la pantalla y el registro, sin datos. */
internal sealed interface RestoreRefusal {
    /** El validador o la preparación la rechazaron, con su código. */
    data class Candidate(val reason: CandidateRejection) : RestoreRefusal

    /** Sus metadatos dicen que la app no la puede restaurar. */
    data object MarkedFatal : RestoreRefusal

    /** Pasa del tamaño que se acepta bajar o leer. */
    data object TooLarge : RestoreRefusal

    /** El destino no la entregó, por [failure]. */
    data class Download(val failure: DestinationFailure) : RestoreRefusal

    /** No hay destino conectado del que bajarla. */
    data object NoDestination : RestoreRefusal

    /** La migración sobre la candidata falló. La base activa no se tocó. */
    data object MigrationFailed : RestoreRefusal

    /** No cupo la copia de seguridad de la base activa. */
    data object SafetyCopyNoSpace : RestoreRefusal

    /**
     * El cerrojo venció o dejó de ser el de esta preparación: pudo entrar una venta después de la
     * copia de seguridad, y reemplazar la base la perdería.
     */
    data object LockLost : RestoreRefusal
}
