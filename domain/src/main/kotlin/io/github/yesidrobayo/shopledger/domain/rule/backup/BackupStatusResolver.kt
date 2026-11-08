package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupTimings
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Calcula el estado del respaldo que ve la persona. Es una función pura: no lee el disco, el reloj
 * ni la red, sino lo que recibe, así que da lo mismo cada vez que la app se abre, haya corrido o no
 * un trabajo en segundo plano.
 *
 * Gana la primera condición que se cumpla, en el orden de [BackupStatus]. Tres reglas sostienen
 * ese orden:
 *
 * - **Un día sin copia se ve siempre.** La antigüedad se mide desde el cambio más viejo sin copia,
 *   no desde la última copia, y va antes que un fallo: el aviso muestra la última copia y el motivo.
 * - **Un reloj que retrocedió nunca dice que todo está bien.** Si la hora es anterior al cambio más
 *   viejo sin copia, o a la última copia, el estado es [BackupStatus.STALE].
 * - **Entregada no es subida.** Con la nube del teléfono nunca sale [BackupStatus.UP_TO_DATE]: la
 *   app solo sabe que la otra app recibió el archivo.
 *
 * Con cualquier plazo, una hora anterior a la guardada cuenta como plazo vencido: un reloj raro
 * nunca esconde un aviso.
 *
 * Los avisos van aparte del estado ([BackupStatusReport.notices]): se calculan con cualquier estado
 * y ninguno lo cambia.
 */
object BackupStatusResolver {
    /** El estado de [facts] en el instante [now]. */
    fun resolve(facts: BackupStatusFacts, now: Instant): BackupStatusReport {
        val pendingChanges = (facts.changeSeq - facts.verifiedSeq).coerceAtLeast(0)
        val status = statusOf(facts, pendingChanges > 0, now)
        return BackupStatusReport(
            status = status,
            pendingChanges = pendingChanges,
            savingSoon = status == BackupStatus.PENDING && savingSoon(facts, now),
            notices = noticesOf(facts, now),
        )
    }

    /**
     * Los avisos que van junto a cualquier estado, para que ningún fallo los esconda.
     *
     * - Los de espacio miran el teléfono y la base, no el destino: también avisan sin destino
     *   conectado. El de la copia cerca del límite mira aquí solo la base; el tamaño de la cabeza lo
     *   trae la lista del destino, en la pantalla del respaldo.
     * - La revisión pendiente es la misma condición que el estado [BackupStatus.CHECK_DUE], con un
     *   destino conectado: un fallo o un día sin copia le ganan como estado, y el aviso sigue.
     * - La lectura completa vencida mira, con WebDAV conectado, la última lectura entera o, sin
     *   ninguna, la conexión. Sin ninguna de las dos no hay con qué medir.
     */
    private fun noticesOf(facts: BackupStatusFacts, now: Instant): Set<BackupNotice> = buildSet {
        facts.storage?.let { storage ->
            if (StorageNoticeRule.lowDeviceStorage(storage)) add(BackupNotice.LOW_DEVICE_STORAGE)
            if (StorageNoticeRule.nearCopyLimit(headFileBytes = null, storage.databaseBytes)) {
                add(BackupNotice.COPY_NEAR_LIMIT)
            }
        }
        if (facts.linkState == BackupLinkState.NOT_CONNECTED) return@buildSet
        val kind = facts.destinationKind
        if (kind == BackupDestinationKind.DOCUMENT_SLOTS && isCheckDue(facts, now)) {
            add(BackupNotice.HUMAN_CHECK_DUE)
        }
        if (kind == BackupDestinationKind.WEBDAV && fullReadOverdue(facts, now)) {
            add(BackupNotice.FULL_READ_OVERDUE)
        }
    }

    /**
     * Con WebDAV, pasaron [BackupTimings.FULL_READ_NOTICE] desde la última lectura entera o, sin
     * ninguna, desde la conexión. Sin ninguna de las dos no hay con qué medir.
     */
    private fun fullReadOverdue(facts: BackupStatusFacts, now: Instant): Boolean {
        val since = facts.lastFullVerifyAt ?: facts.connectedAt ?: return false
        return expired(since, BackupTimings.FULL_READ_NOTICE, now)
    }

    private fun statusOf(facts: BackupStatusFacts, pending: Boolean, now: Instant): BackupStatus {
        val kind = facts.destinationKind
        return when {
            facts.restoreInProgress -> BackupStatus.RESTORING

            facts.linkState == BackupLinkState.NOT_CONNECTED || kind == null ->
                BackupStatus.NOT_CONNECTED

            !facts.policyAccepted -> BackupStatus.POLICY_PENDING

            facts.linkState == BackupLinkState.PERMISSION_LOST -> BackupStatus.PERMISSION_LOST

            facts.linkState == BackupLinkState.CONFLICT -> BackupStatus.CONFLICT

            facts.databaseEmpty -> BackupStatus.NOTHING_TO_BACK_UP

            facts.uploadWork == BackupUploadWork.RUNNING -> BackupStatus.UPLOADING

            pending && isStale(facts, now) -> BackupStatus.STALE

            facts.failureCode != null -> BackupStatus.FAILED

            kind == BackupDestinationKind.DOCUMENT_SLOTS && isCheckDue(facts, now) ->
                BackupStatus.CHECK_DUE

            !pending && kind == BackupDestinationKind.DOCUMENT_SLOTS -> BackupStatus.HANDED_OFF

            !pending -> BackupStatus.UP_TO_DATE

            // Con la nube del teléfono la app no pide red: la decide la otra app.
            kind == BackupDestinationKind.DOCUMENT_SLOTS -> BackupStatus.PENDING

            else -> webDavWait(facts, now)
        }
    }

    /**
     * El cambio más viejo sin copia lleva un día o más, o la hora es anterior a ese cambio o a la
     * última copia. La última copia solo sirve para ver el reloj atrasado: medir desde ella daría el
     * aviso con la primera venta tras un día sin abrir.
     */
    private fun isStale(facts: BackupStatusFacts, now: Instant): Boolean {
        val oldest = facts.firstUnsyncedAt
        val oldestTooOld =
            oldest != null && (now < oldest || now - oldest >= BackupTimings.STALE_NOTICE)
        val lastSuccess = facts.lastSuccessAt
        return oldestTooOld || (lastSuccess != null && now < lastSuccess)
    }

    /**
     * Con la nube del teléfono y una copia ya entregada, la persona no revisó esa nube en el plazo,
     * contado desde su última revisión o, si nunca revisó, desde la primera entrega.
     */
    private fun isCheckDue(facts: BackupStatusFacts, now: Instant): Boolean {
        val firstHandoff = facts.firstHandoffAt ?: return false
        return expired(facts.lastHumanCheckAt ?: firstHandoff, BackupTimings.HUMAN_CHECK, now)
    }

    /** Con WebDAV y cambios sin copia: qué espera la subida, si espera algo. */
    private fun webDavWait(facts: BackupStatusFacts, now: Instant): BackupStatus {
        val network = facts.network
        val interval = BackupTimings.meteredInterval(facts.meteredPolicy)
        // Una red que el sistema no comprobó no lleva la copia a ninguna parte: cuenta como ninguna.
        val meteredOnly = network.validated && network.metered
        return when {
            interval != null && meteredOnly && network.dataSaverRestricted ->
                BackupStatus.BLOCKED_BY_DATA_SAVER

            interval == null && (!network.validated || network.metered) ->
                BackupStatus.WAITING_FOR_WIFI

            interval != null && meteredOnly && intervalRunning(facts, interval, now) ->
                BackupStatus.WAITING_FOR_WIFI

            facts.uploadWork == BackupUploadWork.ENQUEUED && !network.validated ->
                BackupStatus.WAITING_FOR_NETWORK

            else -> BackupStatus.PENDING
        }
    }

    /** El intervalo con datos móviles sigue corriendo desde la última subida. */
    private fun intervalRunning(
        facts: BackupStatusFacts,
        interval: Duration,
        now: Instant,
    ): Boolean {
        val last = facts.lastUploadFinishedAt ?: return false
        return !expired(last, interval, now)
    }

    /**
     * La espera es todavía la corta: menos de [BackupTimings.SAVING_SOON] desde el cambio más viejo
     * sin copia. Con la nube del teléfono y su intervalo corriendo, esperar es lo normal, y el
     * margen cuenta desde que el intervalo vence.
     *
     * Solo se pide con [BackupStatus.PENDING]: con la hora anterior al cambio más viejo ya ganó
     * [BackupStatus.STALE]. Sin la hora de ese cambio no hay con qué medir, y el texto no promete
     * que se guarda en un momento.
     */
    private fun savingSoon(facts: BackupStatusFacts, now: Instant): Boolean {
        val oldest = facts.firstUnsyncedAt ?: return false
        var start = oldest
        val lastUpload = facts.lastUploadFinishedAt
        if (
            facts.destinationKind == BackupDestinationKind.DOCUMENT_SLOTS &&
            lastUpload != null &&
            now >= lastUpload
        ) {
            start = maxOf(start, lastUpload + BackupTimings.SLOT_MIN_INTERVAL)
        }
        return now - start < BackupTimings.SAVING_SOON
    }

    /** Pasó [period] desde [since], o la hora es anterior a [since]: un reloj raro vence el plazo. */
    private fun expired(since: Instant, period: Duration, now: Instant): Boolean =
        now < since || now - since >= period
}
