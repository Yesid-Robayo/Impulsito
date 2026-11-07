package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import java.io.File
import java.io.OutputStream

/**
 * La única frontera entre el motor de respaldo y el lugar donde quedan las copias.
 *
 * El motor nunca sabe si habla con un servicio WebDAV o con los documentos de la nube del teléfono:
 * solo conoce estas operaciones y sus resultados. Lo propio de cada destino, como las peticiones,
 * el XML, los documentos o el archivo de estado de las ranuras, no sale de este paquete. Las pruebas
 * del motor usan un destino falso en memoria, que puede fallar a pedido en cualquier paso.
 *
 * Reglas que cumple toda implementación:
 *
 * - **Nunca deja un nombre final a medias.** Lo que se corta antes de terminar queda con un nombre
 *   temporal, o marcado como a medias, y nunca pasa por una copia.
 * - **Nunca pisa una copia.** Subir no reemplaza ningún archivo con nombre final, y la única forma
 *   de borrar es [delete], que el motor llama solo para podar o para descartar lo que nunca se
 *   confirmó.
 * - **Nunca asume vacío.** Una lista que no se pudo leer entera es un fallo, nunca una carpeta sin
 *   copias.
 * - **Fallos como valores.** Toda operación devuelve [DestinationResult]: ninguna lanza por un
 *   fallo esperado, y ninguna guarda texto del servicio. Solo la cancelación de la corrutina sale
 *   como excepción.
 * - **Sin estado de la base.** No lee ni escribe Room. Lo que el motor recuerda, como la intención
 *   de subida, lo guarda el motor.
 *
 * Una instancia corresponde a un destino ya abierto, con su credencial o sus permisos: si no abre,
 * no hay instancia, y el motor queda con acceso perdido.
 */
internal interface BackupDestination {
    /** Qué destino es. Decide lo que el motor muestra y si hay poda. */
    val kind: BackupDestinationKind

    /**
     * Lo que hay en el destino, para decidir antes de escribir y para la poda.
     *
     * Con WebDAV, la carpeta de copias en su primer nivel, con los metadatos de cada copia. Si la
     * carpeta no existe, la crea y devuelve una lista vacía: así una carpeta borrada se recrea en
     * vez de detener las copias. Con ranuras, las tres ranuras según lo que la app recuerda, con su
     * tamaño actual: un tamaño que no es el esperado es [DestinationFailure.ChangedOutside], sin
     * escribir nada.
     *
     * Nunca escribe una copia ni borra nada.
     */
    suspend fun listCopies(control: TransferControl): DestinationResult<CopyListing>

    /**
     * Decide dónde irá [copy], sin escribir nada todavía. El motor guarda su intención de subida
     * después de esto y antes de [upload].
     *
     * Con WebDAV el lugar es el nombre de la copia. Con ranuras, la ranura que permite la regla de
     * turnos: si ninguna se puede usar sin tapar algo que hay que conservar, el resultado es
     * [DestinationFailure.NoSafeSlot]. Una copia que la app no podría restaurar nunca se escribe en
     * una ranura: el motor no llega a pedirlo.
     *
     * @param headFileRef la cabeza que recuerda el motor, o nulo si no hay. El destino no lee la
     *   base, así que la recibe aquí: con ranuras, la regla de turnos nunca la elige.
     */
    suspend fun planUpload(copy: OutgoingCopy, headFileRef: CopyRef?): DestinationResult<UploadPlan>

    /**
     * Sube el archivo y sus metadatos al lugar de [plan].
     *
     * Con WebDAV: el archivo a un nombre temporal y después a su nombre final sin pisar nada, y
     * luego lo mismo con los metadatos. Con ranuras: primero marca la ranura como a medias, en
     * disco, y después la escribe. Revisa [control] mientras envía. Si se corta, el archivo local
     * sigue intacto para el siguiente intento, y lo que haya quedado en el destino nunca cuenta
     * como copia.
     *
     * Que termine bien significa entregado, no comprobado: falta [verify].
     */
    suspend fun upload(plan: UploadPlan, control: TransferControl): DestinationResult<StoredCopy>

    /**
     * Sube solo los metadatos de una copia que ya está en el destino sin ellos, al recuperar una
     * subida que se cortó entre el archivo y sus metadatos. Solo con WebDAV: las ranuras no tienen
     * metadatos aparte, y responden [DestinationFailure.Unsupported].
     */
    suspend fun uploadMeta(
        plan: UploadPlan,
        control: TransferControl,
    ): DestinationResult<StoredCopy>

    /**
     * Comprueba que [copy] está en el destino tal como se subió.
     *
     * Con WebDAV mide siempre el tamaño y los metadatos, y lee el contenido según [mode]. Con
     * ranuras relee el documento entero por la otra app, hasta un byte de más, y compara la suma:
     * [mode] no cambia nada. Si algo no coincide, el resultado es
     * [DestinationFailure.VerifyMismatch], y esa copia nunca se confirma.
     *
     * @param localFile el archivo que se subió, para la lectura por muestra. Puede ser nulo si el
     *   motor llega aquí al recuperar una subida y el archivo ya no está: entonces [mode] no puede
     *   ser [VerifyMode.SAMPLE].
     */
    suspend fun verify(
        copy: StoredCopy,
        mode: VerifyMode,
        localFile: File?,
        control: TransferControl,
    ): DestinationResult<VerifiedCopy>

    /**
     * Deja constancia en el destino de que [copy] quedó comprobada, antes de que el motor la
     * confirme en la base. Con ranuras, escribe el estado de las ranuras con esa copia en su lugar.
     * Con WebDAV no hay nada que escribir: la copia ya está con su nombre final.
     */
    suspend fun confirm(copy: StoredCopy): DestinationResult<Unit>

    /**
     * Dónde quedó la subida que el motor tenía en curso, al empezar una ejecución nueva tras un
     * corte. El motor decide qué hacer con la respuesta, según la tabla de recuperación.
     */
    suspend fun locatePending(
        pending: PendingCopy,
        control: TransferControl,
    ): DestinationResult<PendingLocation>

    /**
     * Baja [ref] a [sink], sin pasar de [maxBytes]. Para restaurar y para la comprobación completa
     * de la cabeza. Un archivo más grande se rechaza sin leerlo entero.
     */
    suspend fun download(
        ref: CopyRef,
        sink: OutputStream,
        maxBytes: Long,
        control: TransferControl,
    ): DestinationResult<DownloadedCopy>

    /**
     * Relee los metadatos de la copia [ref], justo antes de podarla: la persona pudo fijarla
     * después de la lista. Si no se pueden releer, el motor no la borra en esta ejecución. Con
     * ranuras, los que la app guardó para esa ranura.
     */
    suspend fun readMeta(ref: CopyRef, control: TransferControl): DestinationResult<ListedMeta>

    /**
     * Reemplaza los metadatos de la copia [ref] por [metaBytes], para fijarla o liberarla: es el
     * único reemplazo que hace la app. Con WebDAV sube los nuevos a un nombre temporal y los mueve
     * encima de los anteriores. Un corte puede dejar la copia sin metadatos, que entonces no cuenta
     * ni se poda: quien llama relee para saber si quedaron. Las ranuras no se fijan: responden
     * [DestinationFailure.Unsupported].
     */
    suspend fun replaceMeta(
        ref: CopyRef,
        metaBytes: ByteArray,
        control: TransferControl,
    ): DestinationResult<Unit>

    /**
     * Borra [ref] y, si es una copia, sus metadatos. Si ya no estaba, también es éxito. Solo para
     * podar con WebDAV y para descartar una copia que nunca se confirmó o un temporal de este
     * teléfono: nunca una carpeta, y nunca algo que la app no escribió. Las ranuras no se borran:
     * responden [DestinationFailure.Unsupported].
     */
    suspend fun delete(ref: CopyRef, control: TransferControl): DestinationResult<Unit>
}

/**
 * Cómo identifica el destino un archivo o una ranura. Es lo que el motor guarda como referencia de
 * la cabeza: con WebDAV el nombre del archivo, y con ranuras `A`, `B` o `C`. Es un dato, nunca una
 * ruta ni una dirección.
 */
@JvmInline
internal value class CopyRef(val value: String) {
    init {
        require(value.isNotEmpty() && value.length <= MAX_LENGTH) { "Referencia vacía o muy larga" }
    }

    override fun toString(): String = value

    companion object {
        /** El largo que admite la columna donde el motor la guarda. */
        const val MAX_LENGTH = 1024
    }
}

/**
 * Lo que el motor da al destino para que decida cuánto puede seguir una transferencia. Se revisa
 * entre escrituras y entre peticiones, nunca a mitad de un byte.
 */
internal interface TransferControl {
    /** Si el sistema pidió detener el trabajo. */
    fun isStopped(): Boolean

    /** Lo que queda de tiempo en esta ejecución, en milisegundos, o nulo si no hay tope. */
    fun remainingMillis(): Long?

    /**
     * Si una subida debe cortarse porque la red pasó a medida. Solo es verdadero cuando la
     * ejecución empezó en red no medida y debía terminar en ella.
     */
    fun meteredStop(): Boolean

    /**
     * Si la ejecución corre en red medida. Ahí una subida que ya envió el archivo entero nunca se
     * repite dentro de la ejecución: gastaría datos otra vez.
     */
    fun isMetered(): Boolean

    companion object {
        /** Sin tope ni detención: para lo que la persona pidió y espera en pantalla. */
        val Unbounded: TransferControl =
            object : TransferControl {
                override fun isStopped(): Boolean = false

                override fun remainingMillis(): Long? = null

                override fun meteredStop(): Boolean = false

                override fun isMetered(): Boolean = false
            }
    }
}

/**
 * Una copia lista para subir: el archivo final, comprimido o cifrado, con su suma ya comprobada, y
 * sus metadatos ya escritos.
 *
 * @property file el archivo local. Nunca se mueve ni se borra desde el destino.
 * @property name el nombre de la copia en el destino.
 * @property sizeBytes el tamaño de [file].
 * @property sha256 la suma SHA-256 de [file], en 64 caracteres hexadecimales en minúscula.
 * @property metaBytes los metadatos de la copia, ya codificados. Con WebDAV van en un archivo al
 *   lado de la copia. Con ranuras quedan en el estado de las ranuras, porque un documento de la
 *   nube del teléfono no tiene archivo al lado.
 * @property candidate lo que dijo el validador de la copia y sus conteos neutros, ya tipados: la
 *   regla de turnos de las ranuras decide con ellos sin decodificar [metaBytes]. WebDAV no los usa:
 *   van en los metadatos.
 */
internal class OutgoingCopy(
    val file: File,
    val name: BackupFileName,
    val sizeBytes: Long,
    val sha256: String,
    val metaBytes: ByteArray,
    val candidate: SlotCandidate,
) {
    init {
        require(sizeBytes >= 0) { "El tamaño no puede ser negativo" }
        require(SHA256_HEX.matches(sha256)) { "La suma debe ser SHA-256 en hexadecimal" }
    }

    override fun toString(): String = "OutgoingCopy($name, $sizeBytes bytes)"
}

/**
 * Dónde irá una copia, ya decidido y todavía sin escribir.
 *
 * @property ref el lugar: el nombre de la copia con WebDAV, o la ranura.
 */
internal class UploadPlan(val copy: OutgoingCopy, val ref: CopyRef) {
    override fun toString(): String = "UploadPlan($ref)"
}

/**
 * Una copia que está en el destino con lo que se espera de ella. Sale de [BackupDestination.upload],
 * o de [BackupDestination.locatePending] al recuperar.
 *
 * @property ref el lugar de la copia.
 * @property sizeBytes el tamaño que debe tener.
 * @property sha256 la suma que debe tener, en hexadecimal en minúscula.
 * @property metaBytes los metadatos que deben acompañarla.
 */
internal class StoredCopy(
    val ref: CopyRef,
    val sizeBytes: Long,
    val sha256: String,
    val metaBytes: ByteArray,
) {
    init {
        require(sizeBytes >= 0) { "El tamaño no puede ser negativo" }
        require(SHA256_HEX.matches(sha256)) { "La suma debe ser SHA-256 en hexadecimal" }
    }

    override fun toString(): String = "StoredCopy($ref, $sizeBytes bytes)"
}

/** Cuánto lee [BackupDestination.verify] del contenido. Con ranuras, siempre lo lee entero. */
internal enum class VerifyMode {
    /** Baja el archivo entero y compara la suma. */
    FULL,

    /**
     * Compara el comienzo y el final con el archivo local. Si el servicio no sabe leer por partes,
     * solo el comienzo: el final queda para la próxima lectura completa.
     */
    SAMPLE,

    /** Solo tamaño y metadatos, sin leer el contenido: en red medida y sin archivo local. */
    SIZE_AND_META,
}

/**
 * Una copia comprobada.
 *
 * @property fullyRead verdadero si se leyó entera y su suma coincidió. Solo entonces el motor anota
 *   una verificación completa.
 */
internal data class VerifiedCopy(val fullyRead: Boolean)

/**
 * Lo que hay en el destino, en un momento dado. Su orden nunca decide nada.
 *
 * @property copies las copias reconocidas por su nombre, o las ranuras con una copia.
 * @property orphanMetas metadatos sin su copia: restos de una poda cortada, que se borran.
 * @property temporaries archivos con nombre temporal, que nunca son copias. Los de este teléfono se
 *   borran al empezar; los de otro, nunca.
 * @property quotaAvailableBytes el espacio libre que informa el servicio, o nulo si no hay tope
 *   conocido: no lo informa, lo informa negativo o no se entiende. Nunca decide qué se poda.
 */
internal data class CopyListing(
    val copies: List<ListedCopy>,
    val orphanMetas: List<CopyRef>,
    val temporaries: List<TemporaryFile>,
    val quotaAvailableBytes: Long?,
)

/**
 * Una copia que el destino tiene.
 *
 * @property ref su lugar.
 * @property name el nombre, si el destino nombra sus copias. Las ranuras no: lo que dice cada una
 *   sale de sus metadatos.
 * @property sizeBytes el tamaño que informa el destino.
 * @property meta sus metadatos, todavía sin leer: los decodifica y valida el motor, porque son
 *   entrada no confiable aunque los haya escrito la app.
 */
internal data class ListedCopy(
    val ref: CopyRef,
    val name: BackupFileName?,
    val sizeBytes: Long,
    val meta: ListedMeta,
)

/** Los metadatos de una copia, tal como llegaron. */
internal sealed interface ListedMeta {
    /** Los bytes de los metadatos, dentro del tope de tamaño. */
    class Present(val bytes: ByteArray) : ListedMeta {
        override fun toString(): String = "Present(${bytes.size} bytes)"
    }

    /** La copia no tiene metadatos. Se ofrece para restaurar, sin suma esperada. */
    data object Missing : ListedMeta

    /** Pasan del tope, o no se pudieron leer como un archivo aceptable. Igual que [Missing]. */
    data object Invalid : ListedMeta
}

/**
 * Un archivo con nombre temporal.
 *
 * @property devicePrefix los primeros 8 caracteres del teléfono que lo subió, si su nombre lo dice.
 *   Si no, nulo: no es de este teléfono y no se toca.
 */
internal data class TemporaryFile(val ref: CopyRef, val devicePrefix: String?)

/**
 * La subida que el motor tenía en curso, tal como la recuerda.
 *
 * @property name el nombre de la copia, con su generación y su teléfono.
 * @property sizeBytes el tamaño que debía tener.
 * @property sha256 la suma que debía tener.
 */
internal data class PendingCopy(val name: BackupFileName, val sizeBytes: Long, val sha256: String)

/** Dónde quedó una subida interrumpida. */
internal sealed interface PendingLocation {
    /**
     * Nada en el destino corresponde a esa subida. Con WebDAV el motor la repite desde el comienzo
     * si conserva el archivo. Con ranuras la escritura no empezó: el motor empieza de nuevo.
     */
    data object Absent : PendingLocation

    /**
     * Está, con el tamaño esperado. Con WebDAV, [meta] dice si tiene sus metadatos: si faltan, el
     * motor los sube con [BackupDestination.uploadMeta]. Con ranuras, la ranura estaba marcada con
     * los metadatos de este archivo. En los dos casos falta comprobarla.
     */
    data class Uploaded(val copy: StoredCopy, val meta: ListedMeta) : PendingLocation

    /** Solo con ranuras: ya estaba comprobada y anotada. Falta solo confirmarla en la base. */
    data class Confirmed(val copy: StoredCopy) : PendingLocation

    /**
     * Solo con WebDAV: está con otro tamaño. Nunca se confirmó, así que el motor la descarta y la
     * borra.
     */
    data class WrongSize(val ref: CopyRef) : PendingLocation

    /**
     * Solo con ranuras: la marca de la ranura no describe este archivo, porque el proceso murió
     * entre la intención y la marca. El motor empieza de nuevo.
     */
    data object Stale : PendingLocation
}

/**
 * Lo que bajó [BackupDestination.download].
 *
 * @property sizeBytes los bytes escritos en el destino de la descarga.
 * @property sha256 su suma, en hexadecimal en minúscula, calculada mientras bajaba.
 */
internal data class DownloadedCopy(val sizeBytes: Long, val sha256: String)

/** SHA-256 en hexadecimal en minúscula. */
internal val SHA256_HEX = Regex("[0-9a-f]{64}")
