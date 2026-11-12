package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El antirretroceso solo avisa: faltan copias si ya se vio una generación mayor que la mayor
 * visible, en un teléfono nuevo no hay referencia, y con el cifrado activo una copia en claro más
 * nueva que la última cifrada lleva advertencia. Nunca se quita ni se bloquea una copia.
 */
class RollbackRuleTest {
    private fun copy(key: String, generation: Long?, encrypted: Boolean) = BackupCopySummary(
        key = key,
        createdAt = null,
        sizeBytes = 1_000,
        origin = CopyOrigin.THIS_PHONE,
        slot = null,
        records = null,
        newest = false,
        pinned = false,
        withDifferences = false,
        restorable = true,
        generation = generation,
        encrypted = encrypted,
    )

    @Test
    fun copiesAreMissingOnlyWhenAHigherGenerationWasSeen() {
        assertFalse(RollbackRule.missingNewer(visibleMax = 7, highestSeen = null))
        assertFalse(RollbackRule.missingNewer(visibleMax = 7, highestSeen = 7))
        assertFalse(RollbackRule.missingNewer(visibleMax = 8, highestSeen = 7))
        assertTrue(RollbackRule.missingNewer(visibleMax = 6, highestSeen = 7))
        // Si ya no se ve ninguna, faltan todas las que se vieron.
        assertTrue(RollbackRule.missingNewer(visibleMax = null, highestSeen = 7))
    }

    @Test
    fun aPlainCopyNewerThanTheLastEncryptedOneIsWarnedOnlyWithEncryptionOn() {
        val copies =
            listOf(
                copy("plain-9", 9, encrypted = false),
                copy("encrypted-8", 8, encrypted = true),
                copy("plain-7", 7, encrypted = false),
                copy("plain-unknown", null, encrypted = false),
            )

        val on = RollbackRule.annotate(copies, encryptionActive = true, anchorKnown = true)
        assertEquals(
            listOf(true, false, false, false),
            on.map { it.plainNewerThanEncrypted },
        )

        val off = RollbackRule.annotate(copies, encryptionActive = false, anchorKnown = true)
        assertTrue(off.none { it.plainNewerThanEncrypted })
        // Avisar no quita ni cambia ninguna copia.
        assertEquals(copies.map { it.key }, off.map { it.key })
        assertTrue(off.all { it.restorable })
    }

    @Test
    fun withoutEncryptedCopiesThereIsNothingToCompare() {
        val copies = listOf(copy("plain-3", 3, encrypted = false))

        val annotated = RollbackRule.annotate(copies, encryptionActive = true, anchorKnown = false)

        assertFalse(annotated.single().plainNewerThanEncrypted)
        assertFalse(annotated.single().anchorKnown)
    }
}
