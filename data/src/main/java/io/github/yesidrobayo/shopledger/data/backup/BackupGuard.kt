package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode

/**
 * Decide, con lo que hay en el destino, si se puede subir una copia nueva y con qué generación.
 * Corre antes de generar nada: si no se puede subir, no se copia ni se comprime la base.
 *
 * Es la defensa contra pisar lo que otro teléfono, o una base vieja de este, dejó en el destino. La
 * generación es un entero que crece y decide sola: nunca se comparan relojes.
 */
internal fun interface CopyGuard {
    fun decide(input: GuardInput): GuardDecision
}

/**
 * Lo que la guarda mira.
 *
 * @property copies las copias del destino cuyos metadatos se pudieron leer y coinciden con su
 *   nombre y su tamaño. Las demás no cuentan para decidir. Con ranuras, las de `slots.json`.
 * @property deviceId esta instalación.
 * @property headGeneration la generación de la última copia que esta base confirmó, o 0.
 * @property headDeviceId el teléfono de esa copia, o nulo si nunca hubo una.
 * @property pendingGeneration la generación de una subida que quedó sin confirmar, o nula.
 * @property appSchemaVersion la versión del esquema de esta app.
 * @property local lo que tiene la base activa, para no subir una con menos datos que la cabeza.
 * @property shrinkAcceptedGeneration la cabeza que la persona ya aceptó conservar por debajo, al
 *   elegir quedarse con este teléfono, o nula.
 * @property highestOwnGeneration la mayor generación de este teléfono que el destino muestra por
 *   su nombre, cuente o no para decidir: también una copia sin metadatos, una rechazada o un
 *   temporal. La generación nueva queda por encima, para que su nombre nunca choque con uno que
 *   quedó en el destino. 0 si no hay ninguna.
 * @property rejectedGenerations las generaciones propias que fallaron la comprobación y nunca se
 *   confirmaron. No cuentan como cabeza, y la generación nueva nunca repite una de ellas.
 * @property encryption el cifrado de las copias en este teléfono.
 */
internal data class GuardInput(
    val copies: List<KnownCopy>,
    val deviceId: String,
    val headGeneration: Long,
    val headDeviceId: String?,
    val pendingGeneration: Long?,
    val appSchemaVersion: Int,
    val local: LocalCounts,
    val shrinkAcceptedGeneration: Long? = null,
    val highestOwnGeneration: Long = 0,
    val rejectedGenerations: Set<Long> = emptySet(),
    val encryption: LocalEncryption = LocalEncryption.NONE,
)

/**
 * Los conteos neutros de la base activa.
 *
 * @property isEmpty si no tiene nada del negocio: una base así nunca sube.
 * @property records los registros de las tablas que solo crecen.
 * @property catalogCount los productos, proveedores y clientes.
 * @property pendingChanges los cambios sin copia, `change_seq - verified_seq`. Cada producto
 *   eliminado cuenta como uno: así borrar a propósito no parece una pérdida de catálogo.
 */
internal data class LocalCounts(
    val isEmpty: Boolean,
    val records: Long,
    val catalogCount: Long,
    val pendingChanges: Long,
)

/**
 * El cifrado de las copias en este teléfono.
 *
 * @property active si las copias nuevas salen cifradas.
 * @property keyAvailable si la clave sellada se puede abrir.
 * @property hasRecoveryCode si este teléfono tiene un código de recuperación.
 * @property offConfirmed si la persona apagó el cifrado a sabiendas.
 */
internal data class LocalEncryption(
    val active: Boolean,
    val keyAvailable: Boolean,
    val hasRecoveryCode: Boolean,
    val offConfirmed: Boolean,
) {
    companion object {
        /** Sin cifrado, que es lo único que existe mientras no esté el cifrado opcional. */
        val NONE = LocalEncryption(
            active = false,
            keyAvailable = false,
            hasRecoveryCode = false,
            offConfirmed = false,
        )
    }
}

/**
 * Una copia del destino tal como dicen sus metadatos ya validados.
 *
 * @property ref su lugar: el nombre del archivo, o la ranura.
 * @property format `1` en claro, `2` cifrada.
 * @property keyId el `key_id` de una copia cifrada, o nulo en claro.
 */
internal data class KnownCopy(
    val ref: CopyRef,
    val generation: Long,
    val deviceId: String,
    val schemaVersion: Long,
    val format: Int,
    val records: Long,
    val catalogCount: Long,
    val keyId: String? = null,
)

/** Lo que decidió la guarda. */
internal sealed interface GuardDecision {
    /** Se sube una copia con [generation], mayor que todo lo que este teléfono escribió. */
    data class Upload(val generation: Long) : GuardDecision

    /** La base no tiene nada del negocio: nunca tapa una copia con datos. */
    data object Empty : GuardDecision

    /**
     * No se sube por [code], que queda a la vista: la cabeza viene de una app más nueva, o no hay
     * con qué cifrar.
     */
    data class Refused(val code: BackupFailureCode) : GuardDecision

    /**
     * No se sube, y la persona decide: el destino tiene algo que una copia nueva taparía.
     *
     * @property head la copia de la cabeza que el conflicto muestra.
     */
    data class Conflict(val kind: BackupConflictKind, val head: KnownCopy) : GuardDecision
}

/**
 * La guarda del respaldo. Evalúa en este orden, y gana la primera regla:
 *
 * 1. Una lista incompleta nunca llega aquí: quien lista la reintenta, y nunca la toma por vacía.
 * 2. **Base vacía:** no sube.
 * 3. **Esquema:** si alguna copia de la cabeza viene de un esquema más nuevo que esta app, no sube.
 *    Hay que actualizar la app.
 * 4. **Cifrado:** con el cifrado activo y sin su clave, o con una cabeza cifrada y este teléfono
 *    sin código y sin haber apagado el cifrado a sabiendas, no sube: nunca sale en claro lo que
 *    debía ir cifrado.
 * 5. **Sin cabeza** en el destino: sube.
 * 6. **Cabeza más nueva** que la que esta base conoce: si toda es de este teléfono, es una base
 *    vieja que reapareció; si no, de otro teléfono. Conflicto en los dos casos.
 * 7. **Cabeza igual** a la conocida, con alguna copia de otro teléfono que el de la cabeza: un
 *    empate, que siempre es conflicto de otro teléfono, en cualquier orden de la lista.
 * 8. **Cabeza más vieja:** la persona borró copias, y no hay nada que pisar. Sube.
 * 9. **Cabeza igual:** si la base tiene menos registros, o menos catálogo contando los cambios
 *    pendientes, la base perdió datos. Conflicto, salvo que la persona ya haya aceptado esa
 *    cabeza. Si no, sube la siguiente.
 *
 * La cabeza es el conjunto de copias con la mayor generación. No cuentan la subida propia que
 * quedó sin confirmar, que es de esta misma base, ni las generaciones propias rechazadas. Cuando
 * la persona eligió quedarse con este teléfono por encima de una cabeza, esa cabeza ya no da
 * conflicto por empate: si no, un empate no tendría salida.
 *
 * La generación nueva queda por encima de la cabeza, de lo que esta base confirmó, de la subida
 * sin confirmar y de toda generación propia que el destino muestre, y nunca repite una rechazada.
 */
internal object BackupGuard : CopyGuard {
    override fun decide(input: GuardInput): GuardDecision {
        if (input.local.isEmpty) return GuardDecision.Empty
        val head = headOf(input)
        if (head.any { it.schemaVersion > input.appSchemaVersion }) {
            return GuardDecision.Refused(BackupFailureCode.CLOUD_SCHEMA_NEWER)
        }
        if (lacksKey(input, head)) {
            return GuardDecision.Refused(BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE)
        }
        if (head.isEmpty()) return upload(input, input.headGeneration)
        val top = head.first().generation
        val accepted = input.shrinkAcceptedGeneration == top
        return when {
            top > input.headGeneration ->
                if (head.all { it.deviceId == input.deviceId }) {
                    GuardDecision.Conflict(BackupConflictKind.CLOUD_AHEAD, head.first())
                } else {
                    GuardDecision.Conflict(
                        BackupConflictKind.OTHER_DEVICE,
                        head.first { it.deviceId != input.deviceId },
                    )
                }

            top == input.headGeneration && !accepted &&
                head.any { it.deviceId != input.headDeviceId } ->
                GuardDecision.Conflict(
                    BackupConflictKind.OTHER_DEVICE,
                    head.first { it.deviceId != input.headDeviceId },
                )

            top < input.headGeneration -> upload(input, input.headGeneration)

            shrunk(input.local, head) && !accepted ->
                GuardDecision.Conflict(
                    BackupConflictKind.LOCAL_SHRUNK,
                    head.maxWith(compareBy(KnownCopy::records, KnownCopy::catalogCount)),
                )

            else -> upload(input, top)
        }
    }

    /**
     * La cabeza del destino, en un orden que no depende del de la lista: el mismo destino da
     * siempre la misma copia para mostrar.
     */
    fun headOf(input: GuardInput): List<KnownCopy> {
        val relevant =
            input.copies.filterNot {
                it.deviceId == input.deviceId &&
                    (
                        it.generation == input.pendingGeneration ||
                            it.generation in input.rejectedGenerations
                        )
            }
        val top = relevant.maxOfOrNull(KnownCopy::generation) ?: return emptyList()
        return relevant.filter { it.generation == top }.sortedBy { it.ref.value }
    }

    private fun lacksKey(input: GuardInput, head: List<KnownCopy>): Boolean {
        val encryption = input.encryption
        if (encryption.active && !encryption.keyAvailable) return true
        return head.any { it.format == ENCRYPTED_FORMAT } &&
            !encryption.hasRecoveryCode &&
            !encryption.offConfirmed
    }

    /** Si la base tiene menos que la cabeza, en registros o en catálogo. */
    private fun shrunk(local: LocalCounts, head: List<KnownCopy>): Boolean {
        val records = head.maxOf(KnownCopy::records)
        val catalog = head.maxOf(KnownCopy::catalogCount)
        // El catálogo puede bajar a propósito: cada producto eliminado es un cambio pendiente.
        return local.records < records ||
            saturatedSum(local.catalogCount, local.pendingChanges) < catalog
    }

    private fun upload(input: GuardInput, above: Long): GuardDecision {
        var next =
            maxOf(
                above,
                input.headGeneration,
                input.pendingGeneration ?: 0L,
                input.highestOwnGeneration,
            ) +
                1
        while (next in input.rejectedGenerations) next++
        return GuardDecision.Upload(next)
    }

    private fun saturatedSum(a: Long, b: Long): Long =
        if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private const val ENCRYPTED_FORMAT = 2
}
