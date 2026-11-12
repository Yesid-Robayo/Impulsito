package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary

/**
 * Antirretroceso y antidegradación de la lista de copias: **solo avisan, nunca rechazan una copia.**
 *
 * - **Faltan copias más recientes:** este teléfono ya vio una generación mayor que la mayor que se
 *   ve ahora. Alguien, o algo, pudo borrar las copias recientes. Se avisa y se puede restaurar igual.
 * - **Sin referencia:** en un teléfono nuevo no se vio ninguna generación antes, así que no hay con
 *   qué comparar, y la pantalla lo dice.
 * - **Copia en claro más nueva que la última cifrada:** con el cifrado activo, una copia sin cifrar
 *   no prueba de dónde salió. Lleva advertencia antes de restaurarla.
 *
 * Las generaciones son las que dicen los nombres o los metadatos de cada copia. En una copia cifrada
 * la generación va además en la cabecera autenticada, que la restauración comprueba.
 */
object RollbackRule {
    /** Si faltan copias más recientes que la mayor generación ya vista en este teléfono. */
    fun missingNewer(visibleMax: Long?, highestSeen: Long?): Boolean {
        val seen = highestSeen ?: return false
        return (visibleMax ?: 0L) < seen
    }

    /**
     * Marca cada copia con lo que la hoja de restaurar debe decir: si este teléfono tiene una
     * referencia, y si es una copia en claro más nueva que la última cifrada con el cifrado activo.
     */
    fun annotate(
        copies: List<BackupCopySummary>,
        encryptionActive: Boolean,
        anchorKnown: Boolean,
    ): List<BackupCopySummary> {
        val newestEncrypted = copies.filter {
            it.encrypted
        }.mapNotNull { it.generation }.maxOrNull()
        return copies.map { copy ->
            copy.copy(
                anchorKnown = anchorKnown,
                plainNewerThanEncrypted =
                    encryptionActive && plainNewer(copy, newestEncrypted),
            )
        }
    }

    private fun plainNewer(copy: BackupCopySummary, newestEncrypted: Long?): Boolean {
        if (copy.encrypted) return false
        val generation = copy.generation ?: return false
        return newestEncrypted != null && generation > newestEncrypted
    }
}
