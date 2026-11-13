package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateFinding
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import java.io.File

/**
 * Revisa la instantánea sellada de una copia nueva con el mismo validador que decide si una copia
 * se puede restaurar. Así una copia que la app no podría restaurar se nota el día que se crea, y no
 * el día que hace falta.
 *
 * Revisa exactamente lo que se va a subir, no la base activa, y sin los puntos que exigen los
 * metadatos del destino: una copia nueva todavía no los tiene. Solo lee.
 *
 * @param validator el validador de las restauraciones.
 */
internal class SnapshotValidation(private val validator: CandidateValidator) {
    /** Lo que dice el validador de [snapshot], la instantánea ya sellada. */
    suspend fun check(snapshot: File): SnapshotVerdict =
        when (val validation = validator.validate(snapshot, CandidateContext.NEW_COPY)) {
            is CandidateValidation.Checked ->
                SnapshotVerdict.Checked(
                    marks = validation.verdict.newCopyMarks,
                    repairCases = repairCasesOf(validation.verdict),
                )

            is CandidateValidation.Refused ->
                when (validation.reason) {
                    // El archivo está dañado: una base dañada no sube, ni para un rescate.
                    CandidateRejection.INTEGRITY_FAILED,
                    CandidateRejection.NOT_A_DATABASE,
                    CandidateRejection.DAMAGED_ARCHIVE,
                    -> SnapshotVerdict.Corrupt

                    // Cualquier otro rechazo dice que la app no la restauraría: sube solo para un
                    // rescate manual, igual que un hallazgo fatal en sus datos.
                    CandidateRejection.CHECKSUM_MISMATCH,
                    CandidateRejection.UNKNOWN_FORMAT,
                    CandidateRejection.FORMAT_TOO_NEW,
                    CandidateRejection.DECOMPRESSED_TOO_LARGE,
                    CandidateRejection.WAL_DATABASE,
                    CandidateRejection.UNKNOWN_SCHEMA_VERSION,
                    CandidateRejection.SCHEMA_TOO_NEW,
                    CandidateRejection.ISOLATION_FAILED,
                    CandidateRejection.SCHEMA_MISMATCH,
                    CandidateRejection.DATA_FATAL,
                    CandidateRejection.REPAIR_INCOMPLETE,
                    -> SnapshotVerdict.Checked(UNRESTORABLE, repairCases = 0)
                }

            is CandidateValidation.Failed -> SnapshotVerdict.Failed(validation.cause)
        }

    private companion object {
        val UNRESTORABLE = NewCopyMarks(valid = false, fatal = true)
    }
}

/** Lo que concluyó la revisión de una instantánea. */
internal sealed interface SnapshotVerdict {
    /**
     * La instantánea se revisó entera.
     *
     * @property marks si la copia cuenta como válida y si es fatal, para sus metadatos.
     * @property repairCases cuántos elementos necesitan recalcular sus saldos, o 0. Es lo que se
     *   guarda en la base activa para el aviso, sin decir cuáles.
     */
    data class Checked(val marks: NewCopyMarks, val repairCases: Long) : SnapshotVerdict

    /** La instantánea está dañada. No se sube. */
    data object Corrupt : SnapshotVerdict

    /**
     * No se pudo revisar, por una falla que no dice nada de la instantánea. Se reintenta. Solo la
     * clase de [cause] puede ir al registro.
     */
    class Failed(val cause: Exception) : SnapshotVerdict
}

/**
 * Cuántos elementos tienen un saldo que recalcular. Un mismo producto descuadrado falla a la vez en
 * su stock y en el saldo de su cabecera, así que sumar los hallazgos lo contaría dos veces: se toma
 * el hallazgo reparable con más casos, que es el mínimo seguro de elementos afectados.
 */
internal fun repairCasesOf(verdict: CandidateVerdict): Long {
    val repairs: List<CandidateFinding> = when (verdict) {
        is CandidateVerdict.Restorable -> emptyList()
        is CandidateVerdict.NeedsRepair -> verdict.repairs
        is CandidateVerdict.Rejected -> verdict.repairs
    }
    return repairs.maxOfOrNull(CandidateFinding::cases) ?: 0
}
