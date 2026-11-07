package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.RetainedCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RetentionInput
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.rule.backup.RetentionPolicy

/**
 * Lo que la poda sabe de esta instalación, fuera de la base y del destino.
 *
 * @property rejectedGenerations las generaciones propias que nunca se confirmaron: no cuentan.
 * @property damagedGenerations las copias confirmadas que una lectura completa no pudo comprobar.
 * @property fullVerifyGeneration la última copia leída completa, o nula.
 * @property currentKeyId la clave vigente, o nula sin cifrado.
 */
internal data class RetentionMarks(
    val rejectedGenerations: Set<Long> = emptySet(),
    val damagedGenerations: Set<Long> = emptySet(),
    val fullVerifyGeneration: Long? = null,
    val currentKeyId: String? = null,
) {
    companion object {
        val NONE = RetentionMarks()
    }
}

/**
 * La poda de WebDAV, el paso 13 del respaldo: con la copia nueva ya confirmada, lista otra vez, le
 * pide a [RetentionPolicy] qué se poda y lo borra, de la más vieja a la más nueva.
 *
 * Antes de cada borrado relee los metadatos de esa copia: si ya dicen que está fijada, o no se
 * pueden releer, no se borra en esta ejecución, porque la persona pudo fijarla después de la lista.
 * Borra como mucho [maxDeletes] por ejecución, y lo que falte queda para la siguiente.
 *
 * No poda nada si la copia nueva no aparece en la lista, que entonces está incompleta, quizá en
 * caché; si aparece con otro tamaño, y entonces fuerza una subida; ni si hay una copia de otro
 * teléfono con una generación igual o mayor. Con ranuras no hay poda.
 *
 * @param marks lo que la poda sabe de la instalación, pedido al correr.
 * @param forceUpload fuerza una subida: la copia confirmada ya no es la que se subió.
 * @param forgetDamaged olvida las generaciones dañadas que ya no están en el destino: su marca
 *   sale cuando la copia ya no está.
 */
internal class RetentionPruner(
    private val deviceId: suspend () -> String,
    private val log: EventLog,
    private val marks: suspend () -> RetentionMarks = { RetentionMarks.NONE },
    private val forceUpload: suspend () -> Unit = {},
    private val forgetDamaged: suspend (Set<Long>) -> Unit = {},
    private val maxDeletes: Int = MAX_PRUNE_PER_RUN,
) : CopyPruner {
    override suspend fun prune(
        destination: BackupDestination,
        confirmed: ConfirmedCopy,
        control: TransferControl,
    ): DestinationResult<PruneReport> {
        if (destination.kind != BackupDestinationKind.WEBDAV) {
            return DestinationResult.Success(PruneReport(PruneResult.NOT_APPLICABLE, 0))
        }
        val listing =
            when (val listed = destination.listCopies(control)) {
                is DestinationResult.Success -> listed.value
                is DestinationResult.Failure -> return listed
            }
        val device = deviceId()
        val known = marks()
        val counted = counted(listing, device, known)
        val listed = listing.copies.mapNotNull { it.name?.generation }.toSet()
        val gone = known.damagedGenerations - listed
        if (gone.isNotEmpty()) forgetDamaged(gone)
        val newCopy = listing.copies.firstOrNull { it.ref == confirmed.ref }
        if (newCopy == null || counted.none { it.key == confirmed.ref.value }) {
            if (newCopy != null && sizeMismatch(newCopy)) forceUpload()
            return report(PruneResult.INCOMPLETE_LISTING, 0)
        }
        if (counted.any { it.deviceId != device && it.generation >= confirmed.generation }) {
            return report(PruneResult.FOREIGN_AHEAD, 0)
        }
        val plan =
            RetentionPolicy.retain(
                RetentionInput(
                    copies = counted,
                    deviceId = device,
                    currentKeyId = known.currentKeyId,
                    fullVerifyGeneration = known.fullVerifyGeneration,
                    damagedGenerations = known.damagedGenerations,
                ),
            )
        if (plan.blockedByRecords) return report(PruneResult.BLOCKED_BY_RECORDS, 0)
        var deleted = 0
        for (copy in plan.prune) {
            if (deleted >= maxDeletes || control.isStopped()) break
            val ref = CopyRef(copy.key)
            if (!stillPrunable(destination, ref, copy, control)) continue
            when (val removed = destination.delete(ref, control)) {
                is DestinationResult.Success -> deleted++
                is DestinationResult.Failure -> return removed
            }
        }
        // Unos metadatos sin copia son restos de una poda cortada entre los dos borrados.
        for (orphan in listing.orphanMetas) {
            if (deleted >= maxDeletes || control.isStopped()) break
            when (val removed = destination.delete(orphan, control)) {
                is DestinationResult.Success -> deleted++
                is DestinationResult.Failure -> return removed
            }
        }
        return report(PruneResult.PRUNED, deleted)
    }

    /** Las copias que cuentan para podar: metadatos válidos de su nombre, con el tamaño que dicen. */
    private fun counted(
        listing: CopyListing,
        device: String,
        known: RetentionMarks,
    ): List<RetainedCopy> = listing.copies.mapNotNull { copy ->
        val name = copy.name ?: return@mapNotNull null
        val meta = metaOf(copy.meta, name) ?: return@mapNotNull null
        if (meta.fileSize != copy.sizeBytes) return@mapNotNull null
        if (meta.deviceId == device && meta.generation in known.rejectedGenerations) {
            return@mapNotNull null
        }
        RetainedCopy(
            key = copy.ref.value,
            generation = meta.generation,
            deviceId = meta.deviceId,
            createdAt = name.createdAt,
            valid = meta.valid,
            fatal = meta.fatal,
            pinned = meta.pinned,
            keyId = meta.keyId,
            records = meta.records,
        )
    }

    private fun sizeMismatch(copy: ListedCopy): Boolean {
        val name = copy.name ?: return false
        val meta = metaOf(copy.meta, name) ?: return false
        return meta.fileSize != copy.sizeBytes
    }

    private fun metaOf(listed: ListedMeta, name: BackupFileName): SlMeta? {
        val bytes = (listed as? ListedMeta.Present)?.bytes ?: return null
        return (SlMetaCodec.decode(bytes, name.identity()) as? CopyMetadata.Valid)?.meta
    }

    /** Relee los metadatos justo antes de borrar: una copia fijada después de listar se queda. */
    private suspend fun stillPrunable(
        destination: BackupDestination,
        ref: CopyRef,
        copy: RetainedCopy,
        control: TransferControl,
    ): Boolean {
        val reread =
            when (val read = destination.readMeta(ref, control)) {
                is DestinationResult.Success -> read.value
                is DestinationResult.Failure -> return false
            }
        val name =
            BackupFileName.parse(ref.value)
                ?: return false
        val meta = metaOf(reread, name) ?: return false
        return !meta.pinned && meta.generation == copy.generation
    }

    private fun report(result: PruneResult, deleted: Int): DestinationResult<PruneReport> {
        log.info(
            BackupEvent.PRUNE_DONE,
            LogField.state("resultado", result),
            LogField.count("borradas", deleted.toLong()),
        )
        return DestinationResult.Success(PruneReport(result, deleted))
    }

    companion object {
        /** Los borrados de una ejecución, como mucho: limita las llamadas. */
        const val MAX_PRUNE_PER_RUN = 20
    }
}
