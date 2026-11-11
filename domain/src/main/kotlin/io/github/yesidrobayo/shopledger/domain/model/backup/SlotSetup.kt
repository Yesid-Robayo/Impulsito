package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * El nombre que el selector del sistema propone al crear el archivo de esta ranura:
 * `shopledger-backup-a.slcopy`, `-b` o `-c`. No se traduce: es un nombre de archivo, el mismo en
 * cualquier idioma, y la persona puede cambiarlo en el selector.
 */
val BackupSlot.newFileName: String get() = "shopledger-backup-${name.lowercase()}.slcopy"

/** En qué está cada archivo mientras se preparan las ranuras. */
enum class SlotFileStatus {
    /** Falta crearlo. */
    MISSING,

    /** Se creó en esta preparación: existe, está vacío y la app conserva su permiso. */
    CREATED,

    /** Es de las ranuras que ya había, y sigue abriendo: no hay que crearlo de nuevo. */
    READY,
}

/**
 * Un archivo de la preparación.
 *
 * @property fileName el nombre que dio la nube, texto no confiable que la persona puede cambiar, o
 *   nulo si todavía no hay archivo o la nube no lo dijo: entonces se muestra el nombre propuesto.
 */
data class SlotSetupFile(val slot: BackupSlot, val status: SlotFileStatus, val fileName: String?) {
    // El nombre lo pudo escribir la persona: no va a un registro.
    override fun toString(): String = "SlotSetupFile($slot, $status)"
}

/**
 * Lo que muestra la pantalla de preparar los archivos.
 *
 * @property providerLabel el nombre de la nube, de la lista fija de proveedores aprobados: nunca lo
 *   que diga el proveedor.
 * @property files los tres archivos, en el orden de las ranuras.
 * @property otherDestination si hay otro destino guardado, conectado o no. Los archivos se pueden
 *   crear, pero el cambio de destino no se confirma aquí.
 * @property done si las ranuras ya están conectadas y las tres abren: no queda nada que preparar.
 */
data class SlotSetupLook(
    val providerLabel: String,
    val files: List<SlotSetupFile>,
    val otherDestination: Boolean,
    val done: Boolean,
) {
    init {
        require(files.map { it.slot } == BackupSlot.entries) { "Un archivo por ranura, en orden" }
    }

    /** El primer archivo que falta, o nulo si ya están los tres. */
    val next: BackupSlot? get() = files.firstOrNull { it.status == SlotFileStatus.MISSING }?.slot
}

/** Por qué no sirve el archivo que se acaba de elegir. */
enum class SlotFileRejection {
    /** Está en otra nube o en una carpeta del teléfono: solo sirven los proveedores aprobados. */
    OUTSIDE_PROVIDERS,

    /** La nube no dejó conservar el permiso, o no respondió al preguntarle por el archivo. */
    NO_ACCESS,

    /** La nube no deja escribir en ese archivo. */
    NOT_WRITABLE,

    /** Ya es el archivo de otra ranura: escribir en una destruiría la otra. */
    SAME_FILE,

    /** La nube no dice cuánto mide: la app no podría notar si otro lo cambió. */
    SIZE_UNKNOWN,

    /** Ya tiene datos, quizá una copia de otro teléfono: la app no escribe encima. */
    NOT_EMPTY,
}

/** Lo que pasó con el archivo que se acaba de elegir. */
sealed interface SlotFileOutcome {
    /** Sirve, y queda guardado como creado. */
    data class Accepted(val look: SlotSetupLook) : SlotFileOutcome

    /**
     * No sirve, y la app no lo conserva ni lo toca.
     *
     * @property sameAs con [SlotFileRejection.SAME_FILE], la ranura que ya usa ese archivo.
     */
    data class Rejected(val reason: SlotFileRejection, val sameAs: BackupSlot? = null) :
        SlotFileOutcome

    /** La base no abre, o el teléfono no pudo guardar el avance. Nada cambió. */
    data object Unavailable : SlotFileOutcome
}

/** Lo que pasó al confirmar las ranuras. */
sealed interface SlotSetupConfirm {
    /**
     * Las ranuras quedaron conectadas.
     *
     * @property hasData si la base tiene datos: entonces ya se pidió la primera copia.
     */
    data class Done(val hasData: Boolean) : SlotSetupConfirm

    /** Falta crear algún archivo. */
    data object Incomplete : SlotSetupConfirm

    /** Hay otro destino guardado: el cambio de destino no se hace aquí. Nada cambió. */
    data object OtherDestination : SlotSetupConfirm

    /** La base no abre, o el teléfono no pudo guardar. Se puede repetir sin riesgo. */
    data object Failed : SlotSetupConfirm
}
